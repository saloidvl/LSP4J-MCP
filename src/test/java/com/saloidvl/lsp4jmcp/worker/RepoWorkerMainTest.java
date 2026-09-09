package com.saloidvl.lsp4jmcp.worker;

import com.google.gson.JsonParser;
import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.saloidvl.lsp4jmcp.config.JdtlsSettingsInputs;
import com.saloidvl.lsp4jmcp.config.JdtlsSettingsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RepoWorkerMainTest {

    @Test
    void defaultRuntime_passesLoadedSnapshotToJdtlsClientFactory(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        JdtlsSettingsSnapshot snapshot = JdtlsSettingsSnapshot.of(
                JsonParser.parseString("{\"java\":{\"completion\":{\"maxResults\":1}}}")
                        .getAsJsonObject(),
                "loaded-settings");
        AtomicReference<Path> workspaceRef = new AtomicReference<>();
        AtomicReference<String> commandRef = new AtomicReference<>();
        AtomicReference<Optional<Path>> lombokRef = new AtomicReference<>();
        AtomicReference<JdtlsSettingsSnapshot> snapshotRef = new AtomicReference<>();
        RepoWorkerMain.DefaultWorkerRuntime runtime = new RepoWorkerMain.DefaultWorkerRuntime(
                (actualWorkspace, command, lombokJar, actualSnapshot) -> {
                    workspaceRef.set(actualWorkspace);
                    commandRef.set(command);
                    lombokRef.set(lombokJar);
                    snapshotRef.set(actualSnapshot);
                    return mock(JdtlsClient.class);
                });

        try (RepoWorkerMain.WorkerSession session =
                     runtime.openSession(workspace, "custom-jdtls", snapshot)) {
            session.initialize();
        }

        assertThat(workspaceRef).hasValue(workspace);
        assertThat(commandRef).hasValue("custom-jdtls");
        assertThat(lombokRef.get()).isNotNull();
        assertThat(snapshotRef.get().sourceFingerprint())
                .isEqualTo(snapshot.sourceFingerprint());
        assertThat(snapshotRef.get().settingsCopy()).isEqualTo(snapshot.settingsCopy());
    }

    @Test
    void run_initializesBackendBeforePrintingReadyAndAcceptsConnections(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean initialized = new AtomicBoolean(false);
        AtomicBoolean handledConnection = new AtomicBoolean(false);
        AtomicReference<ServerSocket> serverSocketRef = new AtomicReference<>();
        AtomicReference<JdtlsSettingsSnapshot> snapshotRef = new AtomicReference<>();
        CountDownLatch readyLatch = new CountDownLatch(1);

        RepoWorkerMain.WorkerRuntime runtime = new RepoWorkerMain.WorkerRuntime() {
            @Override
            public RepoWorkerMain.WorkerSession openSession(
                    Path workspacePath,
                    String jdtlsCommand,
                    JdtlsSettingsSnapshot snapshot) {
                snapshotRef.set(snapshot);
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
                RepoWorkerMain.startConfigured(
                        workspace,
                        "fake-jdtls",
                        Map.of(JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV, "false"),
                        new PrintStream(output, true),
                        runtime);
            } catch (Exception ignored) {
            }
        });

        workerThread.start();

        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(initialized).isTrue();

        int port = serverSocketRef.get().getLocalPort();
        assertThat(snapshotRef.get().settingsCopy().getAsJsonObject("java")
                .getAsJsonObject("import").getAsJsonObject("gradle")
                .getAsJsonObject("annotationProcessing").get("enabled").getAsBoolean()).isFalse();
        assertThat(output.toString()).contains(
                "READY " + port + " " + snapshotRef.get().sourceFingerprint());

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

    @Test
    void startConfigured_printsSingleLineErrorBeforeOpeningSession(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Map<String, String> env = Map.of(
                JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV, "yes");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean opened = new AtomicBoolean();

        RepoWorkerMain.startConfigured(
                workspace,
                "jdtls",
                env,
                new PrintStream(output, true),
                runtimeThatMarks(opened));

        assertThat(opened).isFalse();
        assertThat(output.toString()).startsWith("ERROR ")
                .contains(JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV);
        assertThat(output.toString().lines()).hasSize(1);
    }

    @Test
    void run_printsSingleLineErrorWhenSessionInitializationFails(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean sessionClosed = new AtomicBoolean();
        AtomicReference<ServerSocket> socketRef = new AtomicReference<>();
        RepoWorkerMain.WorkerRuntime runtime = new RepoWorkerMain.WorkerRuntime() {
            @Override
            public RepoWorkerMain.WorkerSession openSession(
                    Path path, String command, JdtlsSettingsSnapshot snapshot) {
                return failingSession("JDTLS init failed\nwith details", sessionClosed);
            }

            @Override
            public ServerSocket openServerSocket() throws IOException {
                ServerSocket socket = new ServerSocket(0);
                socketRef.set(socket);
                return socket;
            }
        };

        RepoWorkerMain.run(
                workspace, "jdtls", testSnapshot(), new PrintStream(output, true), runtime);

        assertThat(output.toString()).isEqualTo("ERROR JDTLS init failed with details\n");
        assertThat(sessionClosed).isTrue();
        assertThat(socketRef.get().isClosed()).isTrue();
    }

    @Test
    void run_printsSingleLineErrorWhenSocketCreationFails(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean sessionClosed = new AtomicBoolean();
        RepoWorkerMain.WorkerRuntime runtime = new RepoWorkerMain.WorkerRuntime() {
            @Override
            public RepoWorkerMain.WorkerSession openSession(
                    Path path, String command, JdtlsSettingsSnapshot snapshot) {
                return failingSession(null, sessionClosed);
            }

            @Override
            public ServerSocket openServerSocket() throws IOException {
                throw new IOException("Cannot bind worker\nsocket");
            }
        };

        RepoWorkerMain.run(
                workspace, "jdtls", testSnapshot(), new PrintStream(output, true), runtime);

        assertThat(output.toString()).isEqualTo("ERROR Cannot bind worker socket\n");
        assertThat(sessionClosed).isTrue();
    }

    private static RepoWorkerMain.WorkerSession failingSession(
            String initializationFailure,
            AtomicBoolean closed) {
        return new RepoWorkerMain.WorkerSession() {
            @Override
            public void initialize() {
                if (initializationFailure != null) {
                    throw new IllegalStateException(initializationFailure);
                }
            }

            @Override
            public void handle(Socket socket) {
                throw new AssertionError("startup failure must not accept connections");
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    private static JdtlsSettingsSnapshot testSnapshot() {
        return JdtlsSettingsSnapshot.of(
                JsonParser.parseString("{\"java\":{}}").getAsJsonObject(),
                "test-settings");
    }

    private static RepoWorkerMain.WorkerRuntime runtimeThatMarks(AtomicBoolean opened) {
        return new RepoWorkerMain.WorkerRuntime() {
            @Override
            public RepoWorkerMain.WorkerSession openSession(
                    Path workspace,
                    String command,
                    JdtlsSettingsSnapshot snapshot) {
                opened.set(true);
                throw new AssertionError("invalid settings must fail before opening a session");
            }

            @Override
            public ServerSocket openServerSocket() {
                throw new AssertionError("invalid settings must fail before opening a socket");
            }
        };
    }
}
