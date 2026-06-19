package com.saloidvl.lsp4jmcp.supervisor;

import com.google.gson.Gson;
import com.saloidvl.lsp4jmcp.control.SupervisorCommand;
import com.saloidvl.lsp4jmcp.control.SupervisorRequest;
import com.saloidvl.lsp4jmcp.control.SupervisorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorMainIntegrationTest {

    private static final Gson GSON = new Gson();

    @Test
    void openLease_sameRepoReusesSingleStartedWorker(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        AtomicInteger launches = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(1);

        SupervisorMain supervisor = new SupervisorMain(
            socketPath,
            new WorkerRegistry(),
            (workspacePath, jdtlsCommand) -> {
                launches.incrementAndGet();
                return new WorkerProcessLauncher.StartedWorker(null, 321L, "127.0.0.1", 45123);
            },
            ready::countDown
        );

        Thread serverThread = new Thread(() -> {
            try {
                supervisor.run();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        // Open two connections, each gets same worker port
        try (SocketChannel ch1 = openLeaseChannel(socketPath, tempDir.resolve("repo-a"))) {
            SupervisorResponse first = readResponse(ch1);
            assertThat(first.ok()).isTrue();
            assertThat(first.port()).isEqualTo(45123);

            try (SocketChannel ch2 = openLeaseChannel(socketPath, tempDir.resolve("repo-a"))) {
                SupervisorResponse second = readResponse(ch2);
                assertThat(second.ok()).isTrue();
                assertThat(second.port()).isEqualTo(45123);
                assertThat(launches).hasValue(1);
            }
        }

        supervisor.close();
        serverThread.join(2000);
    }

    @Test
    void openLease_connectionCloseTriggersLeaseRelease(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        CountDownLatch ready = new CountDownLatch(1);
        WorkerRegistry registry = new WorkerRegistry();

        SupervisorMain supervisor = new SupervisorMain(
            socketPath,
            registry,
            (workspacePath, jdtlsCommand) ->
                new WorkerProcessLauncher.StartedWorker(null, 321L, "127.0.0.1", 45123),
            ready::countDown
        );

        Thread serverThread = new Thread(() -> {
            try {
                supervisor.run();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        try (SocketChannel ch = openLeaseChannel(socketPath, tempDir.resolve("repo-b"))) {
            SupervisorResponse resp = readResponse(ch);
            assertThat(resp.ok()).isTrue();
            assertThat(registry.get("repo-b").leaseCount()).isEqualTo(1);
        }
        // After close, give virtual thread a moment to process EOF
        Thread.sleep(100);
        assertThat(registry.get("repo-b").leaseCount()).isZero();

        supervisor.close();
        serverThread.join(2000);
    }

    @Test
    void ping_returnsOk(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        CountDownLatch ready = new CountDownLatch(1);

        SupervisorMain supervisor = new SupervisorMain(
            socketPath,
            new WorkerRegistry(),
            (workspacePath, jdtlsCommand) ->
                new WorkerProcessLauncher.StartedWorker(null, 1L, "127.0.0.1", 1),
            ready::countDown
        );

        Thread serverThread = new Thread(() -> {
            try { supervisor.run(); } catch (Exception ignored) {}
        });
        serverThread.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        SupervisorResponse response = sendRequest(socketPath,
            new SupervisorRequest(SupervisorCommand.PING, null, null, null));
        assertThat(response.ok()).isTrue();
        assertThat(response.message()).isEqualTo("pong");

        supervisor.close();
        serverThread.join(2000);
    }

    @Test
    void supervisorClose_interruptsActiveLeaseConnections(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        CountDownLatch ready = new CountDownLatch(1);
        WorkerRegistry registry = new WorkerRegistry();

        SupervisorMain supervisor = new SupervisorMain(
            socketPath,
            registry,
            (workspacePath, jdtlsCommand) ->
                new WorkerProcessLauncher.StartedWorker(null, 321L, "127.0.0.1", 45123),
            ready::countDown
        );

        Thread serverThread = new Thread(() -> {
            try { supervisor.run(); } catch (Exception ignored) {}
        });
        serverThread.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        // Open a lease but don't close it
        SocketChannel ch = openLeaseChannel(socketPath, tempDir.resolve("repo-c"));
        SupervisorResponse resp = readResponse(ch);
        assertThat(resp.ok()).isTrue();
        assertThat(registry.get("repo-c").leaseCount()).isEqualTo(1);

        // Close supervisor — should interrupt active channel, release lease
        supervisor.close();
        serverThread.join(2000);

        Thread.sleep(100);
        // Lease should have been released via finally block
        assertThat(registry.get("repo-c")).satisfiesAnyOf(
            r -> assertThat(r).isNull(),
            r -> assertThat(r.leaseCount()).isZero()
        );
        ch.close();
    }

    private static SocketChannel openLeaseChannel(Path socketPath, Path workspace) throws Exception {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(socketPath));
        Writer writer = new OutputStreamWriter(Channels.newOutputStream(channel));
        writer.write(GSON.toJson(new SupervisorRequest(
            SupervisorCommand.OPEN_LEASE,
            workspace.getFileName().toString(),
            workspace.toString(),
            "jdtls"
        )));
        writer.write("\n");
        writer.flush();
        return channel;
    }

    private static SupervisorResponse readResponse(SocketChannel channel) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
        return GSON.fromJson(reader.readLine(), SupervisorResponse.class);
    }

    private static SupervisorResponse sendRequest(Path socketPath, SupervisorRequest request) throws Exception {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            try (Writer writer = new OutputStreamWriter(Channels.newOutputStream(channel));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)))) {
                writer.write(GSON.toJson(request));
                writer.write("\n");
                writer.flush();
                return GSON.fromJson(reader.readLine(), SupervisorResponse.class);
            }
        }
    }
}
