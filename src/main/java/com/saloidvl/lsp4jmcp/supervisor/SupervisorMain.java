package com.saloidvl.lsp4jmcp.supervisor;

import com.google.gson.Gson;
import com.saloidvl.lsp4jmcp.config.JdtlsSettingsSource;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SupervisorMain implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SupervisorMain.class);

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
                writeResponse(writer, new SupervisorResponse(true, "pong", null, null, null));
                return;
            }

            if (request.settingsInputs() == null) {
                writeResponse(writer, new SupervisorResponse(
                        false,
                        "OPEN_LEASE request is missing settingsInputs; incompatible control protocol",
                        null,
                        null,
                        null));
                return;
            }

            LeaseAcquisition acquisition = ensureWorkerReadyAndAcquireLease(request);
            SupervisorResponse response = acquisition.response();
            leaseHandle = acquisition.leaseHandle();
            if (leaseHandle != null) {
                leaseRepoId = request.repoId();
            }
            writeResponse(writer, response);

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

    private void writeResponse(BufferedWriter writer, SupervisorResponse response) throws IOException {
        writer.write(gson.toJson(response));
        writer.newLine();
        writer.flush();
    }

    private record LeaseAcquisition(SupervisorResponse response, Object leaseHandle) {
    }

    private LeaseAcquisition ensureWorkerReadyAndAcquireLease(SupervisorRequest request) {
        String repoId = request.repoId();
        Object repoLock = repoLocks.computeIfAbsent(repoId, k -> new Object());
        synchronized (repoLock) {
            Path workspace;
            JdtlsSettingsSource source;
            try {
                workspace = Path.of(request.workspacePath()).toAbsolutePath().normalize();
                source = captureSettingsSource(workspace, request);
            } catch (Exception exception) {
                return failed(exception.getMessage());
            }

            LeaseAcquisition existing = reuseOrStopExistingWorker(
                    repoId, workspace, source.fingerprint());
            if (existing != null) {
                return existing;
            }
            return launchCompatibleWorker(repoId, workspace, request, source);
        }
    }

    private LeaseAcquisition reuseOrStopExistingWorker(
            String repoId,
            Path workspace,
            String requestedFingerprint) {
        WorkerRecord existing;
        synchronized (this) {
            existing = registry.get(repoId);
            if (existing == null) {
                return null;
            }
            if (!isWorkerAlive(existing)) {
                registry.remove(repoId);
                return null;
            }
            if (existing.state() == WorkerState.STOPPING) {
                return failed("Repository worker pid " + existing.workerPid()
                        + " is still stopping; retry after it exits");
            }
            if (existing.settingsFingerprint().equals(requestedFingerprint)) {
                return acquire(existing);
            }
            if (existing.leaseCount() > 0) {
                return failed(activeSettingsMismatch(workspace, existing));
            }
            existing.setState(WorkerState.STOPPING);
        }
        if (!stopWorker(existing)) {
            return failed("Repository worker pid " + existing.workerPid()
                    + " could not be terminated; retry after it exits");
        }
        removeIfCurrent(repoId, existing);
        return null;
    }

    private boolean isWorkerAlive(WorkerRecord record) {
        return isWorkerAlive(record.process(), record.workerPid());
    }

    private boolean isWorkerAlive(Process process, long workerPid) {
        if (process != null) {
            return process.isAlive();
        }
        return ProcessHandle.of(workerPid)
                .map(ProcessHandle::isAlive)
                .orElse(false);
    }

    private LeaseAcquisition launchCompatibleWorker(
            String repoId,
            Path workspace,
            SupervisorRequest request,
            JdtlsSettingsSource initialSource) {
        JdtlsSettingsSource source = initialSource;
        for (int attempt = 0; attempt < 2; attempt++) {
            WorkerProcessLauncher.StartedWorker startedWorker;
            try {
                startedWorker = workerProcessLauncher.start(
                        workspace, request.jdtlsCommand(), request.settingsInputs());
            } catch (Exception exception) {
                return failed(exception.getMessage());
            }

            if (startedWorker.settingsFingerprint().equals(source.fingerprint())) {
                if (isWorkerAlive(startedWorker.process(), startedWorker.workerPid())) {
                    return registerReadyAndAcquire(repoId, workspace, request, startedWorker);
                }
                if (attempt == 1) {
                    return failed("Repository worker exited during startup; retry the connection");
                }
                continue;
            }
            if (!stopWorker(startedWorker.process(), startedWorker.workerPid())) {
                return quarantineMismatchedWorker(repoId, workspace, request, startedWorker);
            }
            if (attempt == 1) {
                return failed("JDTLS settings source changed repeatedly during worker startup; "
                        + "wait for edits to finish and reconnect");
            }
            try {
                source = captureSettingsSource(workspace, request);
            } catch (Exception exception) {
                return failed(exception.getMessage());
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private LeaseAcquisition quarantineMismatchedWorker(
            String repoId,
            Path workspace,
            SupervisorRequest request,
            WorkerProcessLauncher.StartedWorker startedWorker) {
        WorkerRecord quarantined;
        synchronized (this) {
            quarantined = registry.registerStopping(
                    repoId,
                    workspace,
                    request.jdtlsCommand(),
                    startedWorker.process(),
                    startedWorker.workerPid(),
                    startedWorker.host(),
                    startedWorker.port(),
                    startedWorker.settingsFingerprint());
        }
        watchWorkerExit(repoId, quarantined);
        return failed("Repository worker pid " + startedWorker.workerPid()
                + " could not be terminated after settings changed during startup; "
                + "retry after it exits");
    }

    private LeaseAcquisition registerReadyAndAcquire(
            String repoId,
            Path workspace,
            SupervisorRequest request,
            WorkerProcessLauncher.StartedWorker startedWorker) {
        WorkerRecord registered;
        LeaseAcquisition acquisition;
        synchronized (this) {
            registered = registry.registerReady(
                    repoId,
                    workspace,
                    request.jdtlsCommand(),
                    startedWorker.process(),
                    startedWorker.workerPid(),
                    startedWorker.host(),
                    startedWorker.port(),
                    startedWorker.settingsFingerprint());
            acquisition = acquire(registered);
        }
        watchWorkerExit(repoId, registered);
        return acquisition;
    }

    private JdtlsSettingsSource captureSettingsSource(
            Path workspace,
            SupervisorRequest request) throws IOException {
        return JdtlsSettingsSource.capture(
                workspace,
                request.settingsInputs(),
                Path.of(System.getProperty("java.home")));
    }

    private LeaseAcquisition acquire(WorkerRecord record) {
        Object leaseHandle = registry.acquireLease(record.repoId());
        if (leaseHandle == null) {
            return failed("Worker not ready");
        }
        return new LeaseAcquisition(
                new SupervisorResponse(
                        true, "ok", record.host(), record.port(), record.workerPid()),
                leaseHandle);
    }

    private LeaseAcquisition failed(String message) {
        return new LeaseAcquisition(
                new SupervisorResponse(false, message, null, null, null), null);
    }

    private String activeSettingsMismatch(Path workspace, WorkerRecord record) {
        return "JDTLS settings changed for repository '" + workspace
                + "', but the existing worker (pid " + record.workerPid() + ") is used by "
                + record.leaseCount()
                + " active MCP session(s). Close all MCP sessions connected to this repository, "
                + "then reconnect. The worker will be restarted automatically with the new settings "
                + "when no active sessions remain.";
    }

    private void watchWorkerExit(String repoId, WorkerRecord record) {
        CompletableFuture<?> exit;
        if (record.process() != null) {
            exit = record.process().onExit();
        } else {
            Optional<ProcessHandle> handle = ProcessHandle.of(record.workerPid());
            if (handle.isEmpty()) {
                removeIfCurrent(repoId, record);
                return;
            }
            exit = handle.get().onExit();
        }
        exit.thenRunAsync(() -> removeIfCurrent(repoId, record));
    }

    private void removeIfCurrent(String repoId, WorkerRecord record) {
        synchronized (this) {
            if (registry.get(repoId) == record) {
                registry.remove(repoId);
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
                () -> stopIdleWorker(repoId, record), delayMs, TimeUnit.MILLISECONDS);
            record.setPendingIdleShutdown(future);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor was shut down during supervisor close
        }
    }

    void stopIdleWorker(String repoId, WorkerRecord expectedRecord) {
        Object repoLock = repoLocks.computeIfAbsent(repoId, key -> new Object());
        synchronized (repoLock) {
            WorkerRecord record;
            synchronized (this) {
                record = registry.get(repoId);
                if (record == null
                        || record != expectedRecord
                        || record.state() != WorkerState.READY
                        || record.leaseCount() != 0) {
                    return;
                }
                record.setState(WorkerState.STOPPING);
            }
            if (stopWorker(record)) {
                removeIfCurrent(repoId, record);
            } else {
                LOG.warn("Repository worker pid {} is still stopping", record.workerPid());
            }
        }
    }

    private boolean stopWorker(WorkerRecord record) {
        return stopWorker(record.process(), record.workerPid());
    }

    private boolean stopWorker(Process process, long pid) {
        if (process == null) {
            return ProcessHandle.of(pid).map(this::stopProcessHandle).orElse(true);
        }
        process.destroy();
        try {
            if (!process.waitFor(
                    RuntimeConstants.WORKER_GRACEFUL_SHUTDOWN_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return process.waitFor(
                        RuntimeConstants.WORKER_FORCE_KILL_TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS);
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        }
    }

    private boolean stopProcessHandle(ProcessHandle handle) {
        try {
            handle.destroy();
            try {
                handle.onExit().get(
                        RuntimeConstants.WORKER_GRACEFUL_SHUTDOWN_TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException timeout) {
                handle.destroyForcibly();
                try {
                    handle.onExit().get(
                            RuntimeConstants.WORKER_FORCE_KILL_TIMEOUT.toMillis(),
                            TimeUnit.MILLISECONDS);
                    return true;
                } catch (TimeoutException stillRunning) {
                    return false;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            handle.destroyForcibly();
            return false;
        } catch (ExecutionException exception) {
            return !handle.isAlive();
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
