package com.saloidvl.lsp4jmcp.supervisor;

import com.google.gson.Gson;
import com.saloidvl.lsp4jmcp.control.SupervisorCommand;
import com.saloidvl.lsp4jmcp.control.SupervisorRequest;
import com.saloidvl.lsp4jmcp.control.SupervisorResponse;
import com.saloidvl.lsp4jmcp.runtime.SocketPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SupervisorMain implements AutoCloseable {
    private final Path socketPath;
    private final WorkerRegistry registry;
    private final WorkerProcessLauncher workerProcessLauncher;
    private final Runnable onReady;
    private final Gson gson = new Gson();

    private ServerSocketChannel serverSocketChannel;
    private ScheduledExecutorService cleanupExecutor;
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
        new SupervisorMain().run();
    }

    public void run() throws Exception {
        Files.createDirectories(socketPath.getParent());
        Files.deleteIfExists(socketPath);

        serverSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverSocketChannel.bind(UnixDomainSocketAddress.of(socketPath));
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
        cleanupExecutor.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.SECONDS);
        onReady.run();

        while (!closed) {
            try (SocketChannel channel = serverSocketChannel.accept()) {
                if (channel != null) {
                    handleConnection(channel);
                }
            } catch (IOException e) {
                if (closed) {
                    break;
                }
                throw e;
            }
        }
    }

    private void handleConnection(SocketChannel channel) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel)))) {
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return;
            }

            SupervisorRequest request = gson.fromJson(line, SupervisorRequest.class);
            SupervisorResponse response = process(request);
            writer.write(gson.toJson(response));
            writer.newLine();
            writer.flush();
        }
    }

    private synchronized SupervisorResponse process(SupervisorRequest request) {
        return switch (request.command()) {
            case ACQUIRE_WORKER -> acquireWorker(request);
            case RELEASE_LEASE -> new SupervisorResponse(registry.release(request.leaseId()), "ok", null, null, null, null);
            case HEARTBEAT -> new SupervisorResponse(registry.heartbeat(request.leaseId()), "ok", null, null, null, null);
            case PING -> new SupervisorResponse(true, "pong", null, null, null, null);
        };
    }

    private SupervisorResponse acquireWorker(SupervisorRequest request) {
        WorkerRecord record = registry.get(request.repoId());
        if (record == null || record.state() != WorkerState.READY) {
            try {
                WorkerProcessLauncher.StartedWorker startedWorker = workerProcessLauncher.start(
                    Path.of(request.workspacePath()),
                    request.jdtlsCommand()
                );
                registry.registerReady(
                    request.repoId(),
                    Path.of(request.workspacePath()),
                    request.jdtlsCommand(),
                    startedWorker.workerPid(),
                    startedWorker.host(),
                    startedWorker.port()
                );
            } catch (Exception e) {
                return new SupervisorResponse(false, e.getMessage(), null, null, null, null);
            }
        }

        return registry.acquire(request.repoId());
    }

    private synchronized void cleanup() {
        Instant now = Instant.now();
        registry.expireLeases(now);
        List<WorkerRecord> idleWorkers = registry.collectIdleWorkers(now);
        for (WorkerRecord record : idleWorkers) {
            stopWorker(record);
            registry.remove(record.repoId());
        }
    }

    private void stopWorker(WorkerRecord record) {
        ProcessHandle.of(record.workerPid()).ifPresent(ProcessHandle::destroy);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
        if (serverSocketChannel != null) {
            serverSocketChannel.close();
        }
        Files.deleteIfExists(socketPath);
    }
}
