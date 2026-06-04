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

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorMainIntegrationTest {

    private static final Gson GSON = new Gson();

    @Test
    void acquire_sameRepoReusesSingleStartedWorker(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        AtomicInteger launches = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(1);

        SupervisorMain supervisor = new SupervisorMain(
            socketPath,
            new WorkerRegistry(),
            (workspacePath, jdtlsCommand) -> {
                launches.incrementAndGet();
                return new WorkerProcessLauncher.StartedWorker(321L, "127.0.0.1", 45123);
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

        SupervisorResponse first = sendRequest(socketPath, new SupervisorRequest(
            SupervisorCommand.ACQUIRE_WORKER,
            "repo-1",
            tempDir.resolve("repo-a").toString(),
            "jdtls",
            null
        ));
        SupervisorResponse second = sendRequest(socketPath, new SupervisorRequest(
            SupervisorCommand.ACQUIRE_WORKER,
            "repo-1",
            tempDir.resolve("repo-a").toString(),
            "jdtls",
            null
        ));

        supervisor.close();
        serverThread.join(2000);

        assertThat(first.ok()).isTrue();
        assertThat(second.ok()).isTrue();
        assertThat(first.port()).isEqualTo(45123);
        assertThat(second.port()).isEqualTo(45123);
        assertThat(launches).hasValue(1);
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
