package com.saloidvl.lsp4jmcp.launcher;

import com.saloidvl.lsp4jmcp.control.SupervisorResponse;
import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import com.saloidvl.lsp4jmcp.supervisor.SupervisorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LauncherMain {
    private static final Logger LOG = LoggerFactory.getLogger(LauncherMain.class);

    private LauncherMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java ... LauncherMain <workspace-path> <jdtls-command>");
            System.exit(1);
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) ->
            LOG.error("Uncaught exception in thread '{}'", thread.getName(), ex));

        try {
            run(
                Path.of(args[0]).toAbsolutePath(),
                args[1],
                System.in,
                System.out,
                SupervisorClient.connectOrStart()
            );
        } catch (Exception e) {
            LOG.error("Fatal error in launcher", e);
            throw e;
        }
    }

    static void run(
            Path workspace,
            String jdtlsCommand,
            InputStream clientInput,
            OutputStream clientOutput,
            SupervisorClient supervisorClient) throws Exception {
        SupervisorResponse acquireResponse = supervisorClient.acquire(workspace, jdtlsCommand);
        if (!acquireResponse.ok()) {
            throw new IllegalStateException(acquireResponse.message());
        }

        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();

        try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(acquireResponse.host(), acquireResponse.port()),
                Math.toIntExact(RuntimeConstants.WORKER_TCP_CONNECT_TIMEOUT.toMillis())
            );

            heartbeatExecutor.scheduleAtFixedRate(
                () -> {
                    try {
                        supervisorClient.heartbeat(acquireResponse.leaseId());
                    } catch (Exception ignored) {
                    }
                },
                Math.max(1, RuntimeConstants.LEASE_HEARTBEAT_INTERVAL.toMillis()),
                Math.max(1, RuntimeConstants.LEASE_HEARTBEAT_INTERVAL.toMillis()),
                TimeUnit.MILLISECONDS
            );

            Thread upstream = new Thread(() -> copyClientToWorker(clientInput, socket));
            upstream.start();

            copy(socket.getInputStream(), clientOutput);
            upstream.join();
        } finally {
            heartbeatExecutor.shutdownNow();
            supervisorClient.release(acquireResponse.leaseId());
        }
    }

    private static void copyClientToWorker(InputStream clientInput, Socket socket) {
        try {
            copy(clientInput, socket.getOutputStream());
            socket.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            output.flush();
        }
    }
}
