package com.saloidvl.lsp4jmcp.launcher;

import com.saloidvl.lsp4jmcp.control.SupervisorResponse;
import com.saloidvl.lsp4jmcp.supervisor.SupervisorClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LauncherMainIntegrationTest {

    @Test
    void run_acquiresWorkerProxiesBytesAndReleasesLease(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        AtomicBoolean released = new AtomicBoolean(false);
        CountDownLatch workerHandled = new CountDownLatch(1);

        try (ServerSocket workerSocket = new ServerSocket(0)) {
            Thread workerThread = new Thread(() -> {
                try (Socket socket = workerSocket.accept()) {
                    socket.getInputStream().readNBytes(4);
                    socket.getOutputStream().write("pong".getBytes());
                    socket.getOutputStream().flush();
                    workerHandled.countDown();
                } catch (Exception ignored) {
                }
            });
            workerThread.start();

            SupervisorClient supervisorClient = new SupervisorClient() {
                @Override
                public SupervisorResponse acquire(Path workspacePath, String jdtlsCommand) {
                    return new SupervisorResponse(
                        true,
                        "ok",
                        "lease-1",
                        "127.0.0.1",
                        workerSocket.getLocalPort(),
                        101L
                    );
                }

                @Override
                public void release(String leaseId) {
                    released.set(true);
                }

                @Override
                public void heartbeat(String leaseId) {
                }
            };

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            LauncherMain.run(
                workspace,
                "jdtls",
                new ByteArrayInputStream("ping".getBytes()),
                output,
                supervisorClient
            );

            assertThat(workerHandled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(output.toString()).isEqualTo("pong");
            assertThat(released).isTrue();
        }
    }
}
