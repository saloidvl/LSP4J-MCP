package com.saloidvl.lsp4jmcp.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JdtlsSessionManager {
    private static final Logger LOG = LoggerFactory.getLogger(JdtlsSessionManager.class);

    public interface SessionEvents {
        void onUnexpectedExit(int exitCode);
    }

    public interface RuntimeSessionFactory {
        RuntimeSession start(
            Path workspaceRoot, Path dataDir, String jdtlsCommand,
            JdtlsLanguageClient languageClient, long generation) throws Exception;
    }

    public static final class RuntimeSession {
        private final long generation;
        private final Process process;
        private final JdtlsLanguageServer languageServer;
        private final RemoteEndpoint remoteEndpoint;
        private final Thread stderrThread;
        private final Set<String> openedDocuments;

        public RuntimeSession(
            long generation, Process process, JdtlsLanguageServer languageServer,
            RemoteEndpoint remoteEndpoint, Thread stderrThread,
            Set<String> openedDocuments) {
            this.generation = generation;
            this.process = process;
            this.languageServer = languageServer;
            this.remoteEndpoint = remoteEndpoint;
            this.stderrThread = stderrThread;
            this.openedDocuments = openedDocuments;
        }

        public long generation() { return generation; }
        public Process process() { return process; }
        public JdtlsLanguageServer languageServer() { return languageServer; }

        public RemoteEndpoint remoteEndpoint() {
            return remoteEndpoint;
        }
        public Thread stderrThread() { return stderrThread; }
        public Set<String> openedDocuments() { return openedDocuments; }
    }

    private final RuntimeSessionFactory sessionFactory;
    private final SessionEvents events;
    final Set<Long> intentionallyClosingGenerations = ConcurrentHashMap.newKeySet();
    private final AtomicLong generationCounter = new AtomicLong();
    volatile RuntimeSession session;

    JdtlsSessionManager(RuntimeSessionFactory factory, SessionEvents events) {
        this.sessionFactory = factory;
        this.events = events;
    }

    RuntimeSession createSession(
        Path workspaceRoot,
        Path dataDir,
        String jdtlsCommand,
        JdtlsLanguageClient languageClient) throws Exception {
        long generation = generationCounter.incrementAndGet();
        RuntimeSession s = sessionFactory.start(workspaceRoot, dataDir, jdtlsCommand, languageClient, generation);
        this.session = s;
        registerExitWatcher(s);
        return s;
    }

    private void registerExitWatcher(RuntimeSession exitingSession) {
        Thread watcher = new Thread(
            () -> {
                try {
                    exitingSession.process().waitFor();
                    if (!intentionallyClosingGenerations.remove(exitingSession.generation())) {
                        events.onUnexpectedExit(exitingSession.process().exitValue());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "jdtls-exit-watcher-" + exitingSession.generation());
        watcher.setDaemon(true);
        watcher.start();
    }

    void closeCurrentSession(long shutdownTimeoutMs, long selfExitPollMs) {
        RuntimeSession current = session;
        if (current == null) {
            return;
        }
        intentionallyClosingGenerations.add(current.generation());
        try {
            current.languageServer().shutdown().get(shutdownTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        try {
            current.languageServer().exit();
        } catch (Exception ignored) {
        }
        try {
            current.process().waitFor(selfExitPollMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (current.process().isAlive()) {
            current.process().destroyForcibly();
        }
        current.stderrThread().interrupt();
    }

    void clearSession() {
        session = null;
    }

    RuntimeSession requireSession() {
        if (session == null) {
            throw new IllegalStateException("JDTLS session is not available");
        }
        return session;
    }

    boolean isRunning() {
        return session != null && session.process().isAlive();
    }

    static RuntimeSessionFactory defaultFactory() {
        return defaultFactory(Optional.empty());
    }

    static RuntimeSessionFactory defaultFactory(Optional<Path> lombokJar) {
        return (workspaceRoot, dataDir, jdtlsCommand, languageClient, generation) -> {
            LOG.info("Starting JDTLS process: {} with workspace: {}", jdtlsCommand, workspaceRoot);
            LOG.debug("JDTLS data directory: {}", dataDir);

            List<String> command = buildCommand(jdtlsCommand, dataDir, lombokJar);
            LOG.info("JDTLS starting command: {}", command);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            pb.directory(workspaceRoot.toFile());

            Process process = pb.start();
            Thread stderrThread = createStderrThread(process);
            stderrThread.setDaemon(true);
            stderrThread.start();

            try {
                process.onExit().get(100, TimeUnit.MILLISECONDS);
                throw new IOException("JDTLS process exited immediately with code: " + process.exitValue());
            } catch (TimeoutException ignored) {
                // still alive after 100ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Launcher<JdtlsLanguageServer> launcher = Launcher.createLauncher(
                languageClient,
                JdtlsLanguageServer.class,
                process.getInputStream(),
                process.getOutputStream(),
                false,
                new PrintWriter(new DebugLogWriter("JDTLS LSP"), true)
            );
            JdtlsLanguageServer languageServer = launcher.getRemoteProxy();
            RemoteEndpoint remoteEndpoint = launcher.getRemoteEndpoint();
            launcher.startListening();

            return new RuntimeSession(
                generation,
                process,
                languageServer,
                remoteEndpoint,
                stderrThread,
                ConcurrentHashMap.newKeySet()
            );
        };
    }

    static List<String> buildCommand(String jdtlsCommand, Path dataDir, Optional<Path> lombokJar) {
        List<String> command = new ArrayList<>(Arrays.asList(jdtlsCommand.split("\\s+")));
        boolean skipVmargs = lombokJar.isPresent() && command.contains("-vmargs");
        if (skipVmargs) {
            LOG.warn("jdtlsCommand already contains -vmargs; skipping Lombok javaagent injection. "
                     + "Remove -vmargs from jdtlsCommand or set LOMBOK_JAR differently.");
        }
        command.add("-data");
        command.add(dataDir.toString());
        if (lombokJar.isPresent() && !skipVmargs) {
            command.add("--jvm-arg=-javaagent:" + lombokJar.get());
        }
        if ("DEBUG".equals(System.getenv("LOG_LEVEL"))) {
            LOG.debug("Enabling JDTLS debug logging, logs will be written to jdtls data directory");
            command.add("--jvm-arg=-Djdt.ls.debug=true");
        }
        return command;
    }

    static Thread createStderrThread(Process process) {
        return new Thread(
            () -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("OutOfMemoryError") || line.contains("Cannot allocate")
                            || line.contains("GC overhead")) {
                            LOG.error("JDTLS OOM: {}", line);
                        } else if (line.contains("ERROR") || line.contains("Exception")
                                   || line.contains("Error:")) {
                            LOG.warn("JDTLS stderr: {}", line);
                        } else {
                            LOG.debug("JDTLS stderr: {}", line);
                        }
                    }
                } catch (IOException e) {
                    LOG.warn("Error reading JDTLS stderr", e);
                }
            }, "jdtls-stderr");
    }

}
