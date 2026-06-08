package com.saloidvl.lsp4jmcp.worker;

import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import com.saloidvl.lsp4jmcp.server.JavaMcpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

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

        run(workspace, jdtlsCommand, System.out, new DefaultWorkerRuntime() {
            @Override
            public ServerSocket openServerSocket() throws IOException {
                ServerSocket ss = super.openServerSocket();
                socketRef.set(ss);
                return ss;
            }
        });
    }

    static void run(Path workspace, String jdtlsCommand, PrintStream readyStream, WorkerRuntime runtime) throws Exception {
        try (WorkerSession session = runtime.openSession(workspace, jdtlsCommand);
             ServerSocket serverSocket = runtime.openServerSocket()) {
            session.initialize();

            int port = serverSocket.getLocalPort();
            readyStream.println("READY " + port);
            readyStream.flush();
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
        }
    }

    interface WorkerRuntime {
        WorkerSession openSession(Path workspace, String jdtlsCommand) throws Exception;

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

    private static class DefaultWorkerRuntime implements WorkerRuntime {
        @Override
        public WorkerSession openSession(Path workspace, String jdtlsCommand) {
            return new JdtlsWorkerSession(workspace, jdtlsCommand);
        }

        @Override
        public ServerSocket openServerSocket() throws IOException {
            return new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        }
    }

    private static final class JdtlsWorkerSession implements WorkerSession {
        private final Path workspace;
        private final String jdtlsCommand;
        private final List<McpSyncServer> activeServers = new CopyOnWriteArrayList<>();

        private JdtlsClient client;

        private JdtlsWorkerSession(Path workspace, String jdtlsCommand) {
            this.workspace = workspace;
            this.jdtlsCommand = jdtlsCommand;
        }

        @Override
        public void initialize() throws Exception {
            this.client = JdtlsClient.createAndInitialize(workspace, jdtlsCommand);
        }

        @Override
        public void handle(Socket socket) throws Exception {
            McpSyncServer server = JavaMcpServer.create(
                socket.getInputStream(),
                socket.getOutputStream(),
                client,
                workspace
            );
            activeServers.add(server);
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
