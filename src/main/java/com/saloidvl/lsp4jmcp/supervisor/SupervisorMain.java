package com.saloidvl.lsp4jmcp.supervisor;

import com.google.gson.Gson;
import com.saloidvl.lsp4jmcp.control.SupervisorCommand;
import com.saloidvl.lsp4jmcp.control.SupervisorRequest;
import com.saloidvl.lsp4jmcp.control.SupervisorResponse;
import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import com.saloidvl.lsp4jmcp.runtime.SocketPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SupervisorMain implements AutoCloseable {
    private final Path socketPath;
    private final WorkerRegistry registry;
    private final WorkerProcessLauncher workerProcessLauncher;
    private final Runnable onReady;
    private final Gson gson = new Gson();
    private final Set<SocketChannel> activeChannels = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> repoLocks = new ConcurrentHashMap<>();

    private ServerSocketChannel serverSocketChannel;
    private ScheduledExecutorService cleanupExecutor;
    private FileChannel lockChannel;
    private FileLock supervisorLock;
    private volatile boolean closed;

    public SupervisorMain() {
        this(
            SocketPaths.supervisorSocketPath(),
            new WorkerRegistry(),
            new WorkerProcessLauncher.JvmWorkerProcessLauncher(),
            () -> { }
        );
    }

    SupervisorMain(
            Path socketPath,
            WorkerRegistry registry,
            WorkerProcessLauncher workerProcessLauncher,
            Runnable onReady) {
        this.socketPath = socketPath;
        this.registry = registry;
        this.workerProcessLauncher = workerProcessLauncher;
        this.onReady = onReady;
    }

    public static void main(String[] args) throws Exception {
        new SupervisorMain(
            SocketPaths.supervisorSocketPath(),
            new WorkerRegistry(),
            new WorkerProcessLauncher.JvmWorkerProcessLauncher(),
            () -> {
                System.out.println("READY");
                System.out.flush();
            }
        ).run();
    }

    public void run() throws Exception {
        Files.createDirectories(socketPath.getParent());
        Path lockFile = socketPath.resolveSibling(socketPath.getFileName().toString().replace(".sock", ".lock"));
        lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        supervisorLock = lockChannel.tryLock();
        if (supervisorLock == null) {
            lockChannel.close();
            lockChannel = null;
            return;
        }
        Files.deleteIfExists(socketPath);

        serverSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverSocketChannel.bind(UnixDomainSocketAddress.of(socketPath));
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
        onReady.run();

        while (!closed) {
            try {
                SocketChannel channel = serverSocketChannel.accept();
                if (channel != null) {
                    activeChannels.add(channel);
                    Thread.ofVirtual().start(() -> {
                        try {
                            handleConnection(channel);
                        } finally {
                            activeChannels.remove(channel);
                        }
                    });
                }
            } catch (IOException e) {
                if (closed) {
                    break;
                }
                throw e;
            }
        }
    }

    private void handleConnection(SocketChannel channel) {
        Object leaseHandle = null;
        String leaseRepoId = null;
        try (SocketChannel ch = channel;
             BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch)));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch)))) {
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return;
            }

            SupervisorRequest request = gson.fromJson(line, SupervisorRequest.class);

            if (request.command() == SupervisorCommand.PING) {
                writer.write(gson.toJson(new SupervisorResponse(true, "pong", null, null, null)));
                writer.newLine();
                writer.flush();
                return;
            }

            // OPEN_LEASE — worker startup outside global lock via per-repo lock
            SupervisorResponse response = ensureWorkerReady(request);
            if (response.ok()) {
                synchronized (this) {
                    leaseHandle = registry.acquireLease(request.repoId());
                    if (leaseHandle == null) {
                        response = new SupervisorResponse(false, "Worker not ready", null, null, null);
                    } else {
                        leaseRepoId = request.repoId();
                    }
                }
            }
            writer.write(gson.toJson(response));
            writer.newLine();
            writer.flush();

            if (!response.ok()) {
                return;
            }

            while (reader.readLine() != null) { /* drain until EOF = lease closed */ }

            synchronized (this) {
                registry.releaseLease(leaseHandle);
                leaseHandle = null;
                scheduleIdleShutdownIfNeeded(leaseRepoId);
            }
        } catch (Exception ignored) {
            // connection closed abruptly or JSON error
        } finally {
            if (leaseHandle != null) {
                synchronized (this) {
                    registry.releaseLease(leaseHandle);
                    scheduleIdleShutdownIfNeeded(leaseRepoId);
                }
            }
        }
    }

    private SupervisorResponse ensureWorkerReady(SupervisorRequest request) {
        String repoId = request.repoId();

        // Fast path: check under global lock first
        synchronized (this) {
            WorkerRecord record = registry.get(repoId);
            if (record != null && record.state() == WorkerState.READY) {
                return new SupervisorResponse(true, "ok", record.host(), record.port(), record.workerPid());
            }
        }

        // Slow path: start worker under per-repo lock to prevent duplicate launches
        Object repoLock = repoLocks.computeIfAbsent(repoId, k -> new Object());
        synchronized (repoLock) {
            // Double-check after acquiring per-repo lock
            synchronized (this) {
                WorkerRecord record = registry.get(repoId);
                if (record != null && record.state() == WorkerState.READY) {
                    return new SupervisorResponse(true, "ok", record.host(), record.port(), record.workerPid());
                }
            }

            // Launch worker outside global lock
            WorkerProcessLauncher.StartedWorker startedWorker;
            try {
                startedWorker = workerProcessLauncher.start(
                    Path.of(request.workspacePath()),
                    request.jdtlsCommand()
                );
            } catch (Exception e) {
                return new SupervisorResponse(false, e.getMessage(), null, null, null);
            }

            synchronized (this) {
                WorkerRecord registered = registry.registerReady(
                    repoId,
                    Path.of(request.workspacePath()),
                    request.jdtlsCommand(),
                    startedWorker.process(),
                    startedWorker.workerPid(),
                    startedWorker.host(),
                    startedWorker.port()
                );
                if (startedWorker.process() != null) {
                    startedWorker.process().onExit().thenRunAsync(() -> {
                        synchronized (SupervisorMain.this) {
                            WorkerRecord current = registry.get(repoId);
                            if (current == registered) {
                                registry.remove(repoId);
                            }
                        }
                    });
                }
                return new SupervisorResponse(true, "ok", registered.host(), registered.port(), registered.workerPid());
            }
        }
    }

    private void scheduleIdleShutdownIfNeeded(String repoId) {
        if (repoId == null || closed) return;
        WorkerRecord record = registry.get(repoId);
        if (record == null || record.leaseCount() != 0) return;
        long delayMs = RuntimeConstants.WORKER_IDLE_SHUTDOWN_DELAY.toMillis();
        try {
            ScheduledFuture<?> future = cleanupExecutor.schedule(
                () -> stopIdleWorker(repoId), delayMs, TimeUnit.MILLISECONDS);
            record.setPendingIdleShutdown(future);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor was shut down during supervisor close
        }
    }

    private void stopIdleWorker(String repoId) {
        synchronized (this) {
            WorkerRecord record = registry.get(repoId);
            if (record == null || record.leaseCount() != 0) return;
            stopWorker(record);
            registry.remove(repoId);
        }
    }

    private void stopWorker(WorkerRecord record) {
        if (record.process() != null) {
            record.process().destroy();
        } else {
            ProcessHandle.of(record.workerPid()).ifPresent(ProcessHandle::destroy);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
        for (SocketChannel ch : activeChannels) {
            try { ch.close(); } catch (IOException ignored) {}
        }
        if (serverSocketChannel != null) {
            serverSocketChannel.close();
        }
        Files.deleteIfExists(socketPath);
        if (supervisorLock != null) {
            try { supervisorLock.release(); } catch (IOException ignored) {}
        }
        if (lockChannel != null) {
            lockChannel.close();
        }
    }
}
