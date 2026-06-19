package com.saloidvl.lsp4jmcp.launcher;

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
        try (SupervisorClient.Lease lease = supervisorClient.openLease(workspace, jdtlsCommand)) {
            try (Socket socket = new Socket()) {
                socket.connect(
                    new InetSocketAddress(lease.host(), lease.port()),
                    Math.toIntExact(RuntimeConstants.WORKER_TCP_CONNECT_TIMEOUT.toMillis())
                );
                Thread upstream = Thread.startVirtualThread(() -> copyClientToWorker(clientInput, socket));
                copy(socket.getInputStream(), clientOutput);
                upstream.join();
            }
        }
    }

    private static void copyClientToWorker(InputStream clientInput, Socket socket) {
        try {
            clientInput.transferTo(socket.getOutputStream());
            socket.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        input.transferTo(output);
    }
}
