package com.saloidvl.lsp4jmcp.launcher;

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
    void run_openLeaseProxiesBytesAndReleasesLeaseOnClose(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        AtomicBoolean leaseClosed = new AtomicBoolean(false);
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

            SupervisorClient.Lease lease = new SupervisorClient.Lease() {
                @Override public String host() { return "127.0.0.1"; }
                @Override public int port() { return workerSocket.getLocalPort(); }
                @Override public long workerPid() { return 101L; }
                @Override public void close() { leaseClosed.set(true); }
            };

            SupervisorClient supervisorClient = (workspacePath, jdtlsCommand) -> lease;

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
            assertThat(leaseClosed).isTrue();
        }
    }
}
