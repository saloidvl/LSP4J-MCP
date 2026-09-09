package com.saloidvl.lsp4jmcp.worker;

import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.saloidvl.lsp4jmcp.client.LombokSupport;
import com.saloidvl.lsp4jmcp.config.JdtlsSettingsInputs;
import com.saloidvl.lsp4jmcp.config.JdtlsSettingsLoader;
import com.saloidvl.lsp4jmcp.config.JdtlsSettingsSnapshot;
import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import com.saloidvl.lsp4jmcp.server.JavaMcpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RepoWorkerMain {
    private static final Logger LOG = LoggerFactory.getLogger(RepoWorkerMain.class);

    private RepoWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java ... RepoWorkerMain <workspace-path> <jdtls-command>");
            System.exit(1);
        }

        Path workspace = Path.of(args[0]).toAbsolutePath();
        String jdtlsCommand = args[1];

        Thread mainThread = Thread.currentThread();
        AtomicReference<ServerSocket> socketRef = new AtomicReference<>();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Worker shutdown signal received, shutting down gracefully");
            ServerSocket ss = socketRef.get();
            if (ss != null && !ss.isClosed()) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
            long waitMs = RuntimeConstants.JDTLS_GRACEFUL_SHUTDOWN_TIMEOUT.toMillis()
                + RuntimeConstants.JDTLS_SELF_EXIT_POLL_TIMEOUT.toMillis()
                + 5_000;
            try {
                mainThread.join(waitMs);
            } catch (InterruptedException ignored) {
            }
        }, "worker-shutdown"));

        startConfigured(workspace, jdtlsCommand, System.getenv(), System.out, new DefaultWorkerRuntime() {
            @Override
            public ServerSocket openServerSocket() throws IOException {
                ServerSocket ss = super.openServerSocket();
                socketRef.set(ss);
                return ss;
            }
        });
    }

    static void startConfigured(
            Path workspace,
            String jdtlsCommand,
            Map<String, String> environment,
            PrintStream readyStream,
            WorkerRuntime runtime) throws Exception {
        JdtlsSettingsInputs inputs = JdtlsSettingsInputs.fromEnvironment(environment);
        JdtlsSettingsSnapshot snapshot;
        try {
            snapshot = JdtlsSettingsLoader.load(
                    workspace,
                    inputs,
                    Path.of(System.getProperty("java.home")),
                    Runtime.version().feature());
        } catch (Exception exception) {
            writeStartupError(readyStream, exception);
            return;
        }
        LOG.info(
                "Loaded JDTLS settings: fingerprint={}, envOverrides={}, settingsFileConfigured={}",
                snapshot.sourceFingerprint().substring(0, 12),
                Stream.of(
                                inputs.runtimeHome(),
                                inputs.gradleJavaHome(),
                                inputs.gradleAptEnabled(),
                                inputs.gradleOfflineEnabled(),
                                inputs.lombokSupportEnabled())
                        .filter(Objects::nonNull)
                        .count(),
                inputs.settingsFile() != null);
        run(workspace, jdtlsCommand, snapshot, readyStream, runtime);
    }

    static void run(
            Path workspace,
            String jdtlsCommand,
            JdtlsSettingsSnapshot snapshot,
            PrintStream readyStream,
            WorkerRuntime runtime) throws Exception {
        boolean ready = false;
        try (WorkerSession session = runtime.openSession(workspace, jdtlsCommand, snapshot);
             ServerSocket serverSocket = runtime.openServerSocket()) {
            session.initialize();

            int port = serverSocket.getLocalPort();
            readyStream.println("READY " + port + " " + snapshot.sourceFingerprint());
            readyStream.flush();
            ready = true;
            runtime.onReady(port);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket socket = serverSocket.accept();
                    session.handle(socket);
                } catch (SocketException e) {
                    if (serverSocket.isClosed() || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    throw e;
                }
            }
        } catch (Exception exception) {
            if (!ready) {
                writeStartupError(readyStream, exception);
                return;
            }
            throw exception;
        }
    }

    private static void writeStartupError(PrintStream readyStream, Exception exception) {
        readyStream.println("ERROR " + singleLineMessage(exception));
        readyStream.flush();
    }

    private static String singleLineMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    interface WorkerRuntime {
        WorkerSession openSession(
                Path workspace,
                String jdtlsCommand,
                JdtlsSettingsSnapshot snapshot) throws Exception;

        ServerSocket openServerSocket() throws IOException;

        default void onReady(int port) {
        }
    }

    interface WorkerSession extends AutoCloseable {
        void initialize() throws Exception;

        void handle(Socket socket) throws Exception;

        @Override
        void close() throws Exception;
    }

    @FunctionalInterface
    interface JdtlsClientFactory {
        JdtlsClient create(
                Path workspace,
                String jdtlsCommand,
                Optional<Path> lombokJar,
                JdtlsSettingsSnapshot settingsSnapshot) throws IOException;
    }

    static class DefaultWorkerRuntime implements WorkerRuntime {
        private final JdtlsClientFactory clientFactory;

        DefaultWorkerRuntime() {
            this(JdtlsClient::createAndInitializeAsync);
        }

        DefaultWorkerRuntime(JdtlsClientFactory clientFactory) {
            this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        }

        @Override
        public WorkerSession openSession(
                Path workspace,
                String jdtlsCommand,
                JdtlsSettingsSnapshot snapshot) {
            return new JdtlsWorkerSession(workspace, jdtlsCommand, snapshot, clientFactory);
        }

        @Override
        public ServerSocket openServerSocket() throws IOException {
            return new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        }
    }

    private static final class JdtlsWorkerSession implements WorkerSession {
        private final Path workspace;
        private final String jdtlsCommand;
        private final JdtlsSettingsSnapshot settingsSnapshot;
        private final JdtlsClientFactory clientFactory;
        private final List<McpSyncServer> activeServers = new CopyOnWriteArrayList<>();

        private JdtlsClient client;

        private JdtlsWorkerSession(
                Path workspace,
                String jdtlsCommand,
                JdtlsSettingsSnapshot settingsSnapshot,
                JdtlsClientFactory clientFactory) {
            this.workspace = workspace;
            this.jdtlsCommand = jdtlsCommand;
            this.settingsSnapshot = settingsSnapshot;
            this.clientFactory = clientFactory;
        }

        @Override
        public void initialize() throws Exception {
            Optional<Path> lombokJar = LombokSupport.detectAndFind(workspace);
            this.client = clientFactory.create(
                    workspace,
                    jdtlsCommand,
                    lombokJar,
                    settingsSnapshot);
        }

        @Override
        public void handle(Socket socket) throws Exception {
            CompletableFuture<Void> connectionDone = new CompletableFuture<>();
            InputStream tracked = new FilterInputStream(socket.getInputStream()) {
                private void signalDone() {
                    connectionDone.complete(null);
                }

                @Override
                public int read() throws IOException {
                    try {
                        int b = super.read();
                        if (b == -1) signalDone();
                        return b;
                    } catch (IOException e) {
                        signalDone();
                        throw e;
                    }
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    try {
                        int n = super.read(b, off, len);
                        if (n == -1) signalDone();
                        return n;
                    } catch (IOException e) {
                        signalDone();
                        throw e;
                    }
                }

                @Override
                public void close() throws IOException {
                    signalDone();
                    super.close();
                }
            };
            McpSyncServer server = JavaMcpServer.create(tracked, socket.getOutputStream(), client, workspace);
            activeServers.add(server);
            connectionDone.whenCompleteAsync((v, ex) -> {
                activeServers.remove(server);
                try { server.close(); } catch (Exception ignored) {}
                try { socket.close(); } catch (Exception ignored) {}
            });
        }

        @Override
        public void close() {
            for (McpSyncServer server : activeServers) {
                try {
                    server.close();
                } catch (Exception ignored) {
                }
            }
            if (client != null) {
                client.close();
            }
        }
    }
}
