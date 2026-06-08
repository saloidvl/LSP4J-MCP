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
import java.util.concurrent.TimeUnit;

public interface SupervisorClient {
    SupervisorResponse acquire(Path workspacePath, String jdtlsCommand) throws Exception;

    void release(String leaseId) throws Exception;

    void heartbeat(String leaseId) throws Exception;

    static SupervisorClient connectOrStart() throws Exception {
        Path socketPath = SocketPaths.supervisorSocketPath();
        if (!isSocketReachable(socketPath)) {
            startSupervisorProcess();
            long deadline = System.nanoTime() + RuntimeConstants.SUPERVISOR_ACQUIRE_TIMEOUT.toNanos();
            while (!isSocketReachable(socketPath) && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            if (!isSocketReachable(socketPath)) {
                throw new IOException("Supervisor failed to become reachable within "
                    + RuntimeConstants.SUPERVISOR_ACQUIRE_TIMEOUT.toSeconds() + "s");
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

    private static void startSupervisorProcess() throws IOException {
        Files.createDirectories(SocketPaths.supervisorSocketDirectory());

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        new ProcessBuilder(
            javaBin,
            "-cp",
            classpath,
            SupervisorMain.class.getName()
        )
            .redirectErrorStream(true)
            .start();
    }

    final class SocketSupervisorClient implements SupervisorClient {
        private final Path socketPath;
        private final Gson gson = new Gson();

        public SocketSupervisorClient(Path socketPath) {
            this.socketPath = socketPath;
        }

        @Override
        public SupervisorResponse acquire(Path workspacePath, String jdtlsCommand) throws Exception {
            RepoWorkspace workspace = RepoWorkspace.fromPath(workspacePath);
            return send(new SupervisorRequest(
                SupervisorCommand.ACQUIRE_WORKER,
                workspace.repoId(),
                workspace.canonicalPath().toString(),
                jdtlsCommand,
                null
            ));
        }

        @Override
        public void release(String leaseId) throws Exception {
            send(new SupervisorRequest(SupervisorCommand.RELEASE_LEASE, null, null, null, leaseId));
        }

        @Override
        public void heartbeat(String leaseId) throws Exception {
            send(new SupervisorRequest(SupervisorCommand.HEARTBEAT, null, null, null, leaseId));
        }

        private SupervisorResponse send(SupervisorRequest request) throws Exception {
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(socketPath));
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel)));
                     BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)))) {
                    writer.write(gson.toJson(request));
                    writer.newLine();
                    writer.flush();
                    return gson.fromJson(reader.readLine(), SupervisorResponse.class);
                }
            }
        }
    }
}
