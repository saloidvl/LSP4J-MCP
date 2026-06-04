package com.saloidvl.lsp4jmcp.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RepoWorkerMainTest {

    @Test
    void run_initializesBackendBeforePrintingReadyAndAcceptsConnections(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean initialized = new AtomicBoolean(false);
        AtomicBoolean handledConnection = new AtomicBoolean(false);
        AtomicReference<ServerSocket> serverSocketRef = new AtomicReference<>();
        CountDownLatch readyLatch = new CountDownLatch(1);

        RepoWorkerMain.WorkerRuntime runtime = new RepoWorkerMain.WorkerRuntime() {
            @Override
            public RepoWorkerMain.WorkerSession openSession(Path workspacePath, String jdtlsCommand) {
                return new RepoWorkerMain.WorkerSession() {
                    @Override
                    public void initialize() {
                        initialized.set(true);
                    }

                    @Override
                    public void handle(Socket socket) throws IOException {
                        handledConnection.set(true);
                        socket.close();
                    }

                    @Override
                    public void close() {
                    }
                };
            }

            @Override
            public ServerSocket openServerSocket() throws IOException {
                ServerSocket serverSocket = new ServerSocket(0);
                serverSocketRef.set(serverSocket);
                return serverSocket;
            }

            @Override
            public void onReady(int port) {
                readyLatch.countDown();
            }
        };

        Thread workerThread = new Thread(() -> {
            try {
                RepoWorkerMain.run(workspace, "fake-jdtls", new PrintStream(output, true), runtime);
            } catch (Exception ignored) {
            }
        });

        workerThread.start();

        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(initialized).isTrue();

        int port = serverSocketRef.get().getLocalPort();
        assertThat(output.toString()).contains("READY " + port);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(1);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!handledConnection.get() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(handledConnection).isTrue();

        serverSocketRef.get().close();
        workerThread.interrupt();
        workerThread.join(2000);

        assertThat(workerThread.isAlive()).isFalse();
    }
}
