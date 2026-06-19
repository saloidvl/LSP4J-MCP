package com.saloidvl.lsp4jmcp.supervisor;

import com.google.gson.Gson;
import com.saloidvl.lsp4jmcp.control.SupervisorCommand;
import com.saloidvl.lsp4jmcp.control.SupervisorRequest;
import com.saloidvl.lsp4jmcp.control.SupervisorResponse;
import com.saloidvl.lsp4jmcp.runtime.RepoWorkspace;
import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import com.saloidvl.lsp4jmcp.runtime.SocketPaths;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public interface SupervisorClient {
    Lease openLease(Path workspacePath, String jdtlsCommand) throws Exception;

    interface Lease extends AutoCloseable {
        String host();
        int port();
        long workerPid();
        @Override void close() throws Exception;
    }

    static SupervisorClient connectOrStart() throws Exception {
        Path socketPath = SocketPaths.supervisorSocketPath();
        if (!isSocketReachable(socketPath)) {
            try {
                awaitSupervisorReady(startSupervisorProcess());
            } catch (IOException e) {
                if (!isSocketReachable(socketPath)) {
                    throw e;
                }
            }
        }
        return new SocketSupervisorClient(socketPath);
    }

    private static boolean isSocketReachable(Path socketPath) {
        if (!Files.exists(socketPath)) {
            return false;
        }
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socketPath));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Process startSupervisorProcess() throws IOException {
        Files.createDirectories(SocketPaths.supervisorSocketDirectory());
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        return new ProcessBuilder(
            javaBin,
            "-cp",
            classpath,
            SupervisorMain.class.getName()
        ).start();
    }

    private static void awaitSupervisorReady(Process process) throws Exception {
        StringBuilder stderrBuf = new StringBuilder();
        Thread stderrDrainer = Thread.ofVirtual().start(() -> {
            try (var r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                r.lines().forEach(l -> stderrBuf.append(l).append(System.lineSeparator()));
            } catch (IOException ignored) {
            }
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            Future<String> future = executor.submit(reader::readLine);
            String line;
            try {
                line = future.get(RuntimeConstants.SUPERVISOR_ACQUIRE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                process.destroyForcibly();
                throw new IOException("Supervisor did not become ready within "
                    + RuntimeConstants.SUPERVISOR_ACQUIRE_TIMEOUT.toSeconds() + "s");
            }
            if (!"READY".equals(line)) {
                process.destroyForcibly();
                try {
                    stderrDrainer.join(1_000);
                } catch (InterruptedException ignored) {
                }
                String stderr = stderrBuf.toString().strip();
                throw new IOException("Supervisor emitted unexpected output: " + line
                                      + (stderr.isEmpty() ? "" : "; stderr: " + stderr));
            }
        }
    }

    final class SocketSupervisorClient implements SupervisorClient {
        private final Path socketPath;
        private final Gson gson = new Gson();

        public SocketSupervisorClient(Path socketPath) {
            this.socketPath = socketPath;
        }

        @Override
        public Lease openLease(Path workspacePath, String jdtlsCommand) throws Exception {
            RepoWorkspace workspace = RepoWorkspace.fromPath(workspacePath);
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                channel.connect(UnixDomainSocketAddress.of(socketPath));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel)));
                BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
                writer.write(gson.toJson(new SupervisorRequest(
                    SupervisorCommand.OPEN_LEASE,
                    workspace.repoId(),
                    workspace.canonicalPath().toString(),
                    jdtlsCommand
                )));
                writer.newLine();
                writer.flush();
                SupervisorResponse response = gson.fromJson(reader.readLine(), SupervisorResponse.class);
                if (!response.ok()) {
                    channel.close();
                    throw new IllegalStateException(response.message());
                }
                return new SocketLease(channel, response);
            } catch (Exception e) {
                channel.close();
                throw e;
            }
        }
    }

    final class SocketLease implements Lease {
        private final SocketChannel channel;
        private final String host;
        private final int port;
        private final long workerPid;

        SocketLease(SocketChannel channel, SupervisorResponse response) {
            this.channel = channel;
            this.host = response.host();
            this.port = response.port();
            this.workerPid = response.workerPid();
        }

        @Override
        public String host() {
            return host;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public long workerPid() {
            return workerPid;
        }

        @Override
        public void close() throws Exception {
            channel.close();
        }
    }
}
