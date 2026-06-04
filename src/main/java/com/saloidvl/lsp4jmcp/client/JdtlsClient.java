package com.saloidvl.lsp4jmcp.client;

import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LSP client that connects to JDTLS (Eclipse JDT Language Server).
 */
public class JdtlsClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JdtlsClient.class);
    private static final int TIMEOUT_SECONDS = 120;
    private static final int INIT_TIMEOUT_SECONDS = 180;
    private static final long DIAGNOSTICS_SUMMARY_INTERVAL_SECONDS = 30;

    public interface RuntimeSessionFactory {
        RuntimeSession start(Path workspaceRoot, Path dataDir, String jdtlsCommand,
                             JdtlsLanguageClient languageClient, long generation) throws Exception;
    }

    public static final class RuntimeSession {
        private final long generation;
        private final Process process;
        private final LanguageServer languageServer;
        private final Thread stderrThread;
        private final Set<String> openedDocuments;

        public RuntimeSession(long generation, Process process, LanguageServer languageServer,
                              Thread stderrThread, Set<String> openedDocuments) {
            this.generation = generation;
            this.process = process;
            this.languageServer = languageServer;
            this.stderrThread = stderrThread;
            this.openedDocuments = openedDocuments;
        }

        public long generation() {
            return generation;
        }

        public Process process() {
            return process;
        }

        public LanguageServer languageServer() {
            return languageServer;
        }

        public Thread stderrThread() {
            return stderrThread;
        }

        public Set<String> openedDocuments() {
            return openedDocuments;
        }
    }

    private final Path workspaceRoot;
    private final Path dataDir;
    private final String jdtlsCommand;
    private final RuntimeSessionFactory sessionFactory;
    private final JdtlsLanguageClient languageClient;
    private final Object stateLock = new Object();
    private final AtomicLong generationCounter = new AtomicLong();
    private final Set<Long> intentionallyClosingGenerations = ConcurrentHashMap.newKeySet();
    private final List<Long> recoveryAttemptTimestamps = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, Integer> repeatedSignalCounts = new ConcurrentHashMap<>();
    private final ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "jdtls-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService diagnosticsSummaryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "jdtls-diagnostics-summary");
        thread.setDaemon(true);
        return thread;
    });

    private volatile RuntimeSession session;
    private volatile boolean initialized;
    private volatile JdtlsClientState state = JdtlsClientState.STARTING;
    private volatile String stateMessage = "Starting";
    private volatile String lastRecoveryReason = "";
    private volatile boolean recoveryInFlight;
    private volatile boolean recoveryQueued;
    private volatile int recoveryActionExecutionCount;
    private volatile boolean closed;
    private volatile JdtlsClientState activeRecoveryState;
    private volatile JdtlsRecoveryAction pendingRecoveryAction = JdtlsRecoveryAction.NONE;
    private volatile String pendingRecoveryReason = "";

    long shutdownTimeoutMs = RuntimeConstants.JDTLS_GRACEFUL_SHUTDOWN_TIMEOUT.toMillis();
    long selfExitPollMs = RuntimeConstants.JDTLS_SELF_EXIT_POLL_TIMEOUT.toMillis();

    public JdtlsClient(Path workspaceRoot, String jdtlsCommand) throws IOException {
        this(workspaceRoot, jdtlsCommand, defaultRuntimeSessionFactory());
    }

    JdtlsClient(Path workspaceRoot, String jdtlsCommand, RuntimeSessionFactory sessionFactory) throws IOException {
        this.workspaceRoot = workspaceRoot;
        this.jdtlsCommand = jdtlsCommand;
        this.sessionFactory = sessionFactory;
        this.languageClient = new JdtlsLanguageClient();
        String workspaceHash = Integer.toHexString(workspaceRoot.toString().hashCode());
        this.dataDir = Path.of(System.getProperty("java.io.tmpdir"), "jdtls-data", workspaceHash);
        Files.createDirectories(this.dataDir);
        wireLanguageClient();
        startDiagnosticsSummaryLoop();
        startSession();
    }

    private static RuntimeSessionFactory defaultRuntimeSessionFactory() {
        return (workspaceRoot, dataDir, jdtlsCommand, languageClient, generation) -> {
            LOG.info("Starting JDTLS process: {} with workspace: {}", jdtlsCommand, workspaceRoot);
            LOG.info("JDTLS data directory: {}", dataDir);

            List<String> command = new ArrayList<>();
            for (String part : jdtlsCommand.split("\\s+")) {
                command.add(part);
            }
            command.add("-data");
            command.add(dataDir.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            pb.directory(workspaceRoot.toFile());

            Process process = pb.start();
            Thread stderrThread = createStderrThread(process);
            stderrThread.setDaemon(true);
            stderrThread.start();

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (!process.isAlive()) {
                throw new IOException("JDTLS process exited immediately with code: " + process.exitValue());
            }

            Launcher<LanguageServer> launcher = Launcher.createLauncher(
                languageClient,
                LanguageServer.class,
                process.getInputStream(),
                process.getOutputStream()
            );
            LanguageServer languageServer = launcher.getRemoteProxy();
            launcher.startListening();

            return new RuntimeSession(
                generation,
                process,
                languageServer,
                stderrThread,
                ConcurrentHashMap.newKeySet()
            );
        };
    }

    private static Thread createStderrThread(Process process) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("OutOfMemoryError") || line.contains("Cannot allocate") || line.contains("GC overhead")) {
                        LOG.error("JDTLS OOM: {}", line);
                    } else if (line.contains("ERROR") || line.contains("Exception") || line.contains("Error:")) {
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

    private void wireLanguageClient() {
        languageClient.setRecoverySignalHandler(this::handleRecoverySignalMessage);
        languageClient.setStatusListener(this::onLanguageClientStatusChanged);
    }

    private void onLanguageClientStatusChanged(JdtlsLanguageClient.LanguageClientSnapshot snapshot) {
        if (snapshot.ready()) {
            transitionTo(JdtlsClientState.READY, "JDTLS ready", lastRecoveryReason);
        } else if (recoveryInFlight && activeRecoveryState != null) {
            transitionTo(activeRecoveryState, snapshot.statusMessage(), lastRecoveryReason);
        } else if (initialized) {
            transitionTo(JdtlsClientState.INDEXING, snapshot.statusMessage(), lastRecoveryReason);
        } else {
            transitionTo(JdtlsClientState.STARTING, snapshot.statusMessage(), lastRecoveryReason);
        }
    }

    private void handleRecoverySignalMessage(String reason) {
        submitRecoverySignal(JdtlsRecoveryClassifier.classifyLogMessage(reason), reason);
    }

    private void startDiagnosticsSummaryLoop() {
        diagnosticsSummaryExecutor.scheduleWithFixedDelay(
            this::logDiagnosticsSummarySafely,
            DIAGNOSTICS_SUMMARY_INTERVAL_SECONDS,
            DIAGNOSTICS_SUMMARY_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void logDiagnosticsSummarySafely() {
        try {
            logDiagnosticsSummaryIfNeeded();
        } catch (RuntimeException ex) {
            LOG.debug("Failed to emit JDTLS diagnostics summary: {}", ex.getMessage());
        }
    }

    private void submitRecoverySignal(JdtlsRecoveryAction action, String reason) {
        if (action == JdtlsRecoveryAction.NONE || closed) {
            return;
        }
        String fingerprint = fingerprint(reason);
        logRecoverySignal(fingerprint, reason, repeatedSignalCounts.merge(fingerprint, 1, Integer::sum));
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            if (recoveryQueued || recoveryInFlight) {
                recordPendingRecoveryLocked(action, reason);
                return;
            }
            recoveryQueued = true;
        }
        try {
            recoveryExecutor.submit(() -> {
                recoveryQueued = false;
                handleRecoverySignal(action, reason, false);
            });
        } catch (RejectedExecutionException ex) {
            recoveryQueued = false;
            LOG.debug("Skipping recovery submission after shutdown: {}", ex.getMessage());
        }
    }

    void handleRecoverySignal(JdtlsRecoveryAction action, String reason) {
        handleRecoverySignal(action, reason, false);
    }

    private void handleRecoverySignal(JdtlsRecoveryAction action, String reason, boolean ignoreCooldown) {
        if (action == JdtlsRecoveryAction.NONE || closed) {
            return;
        }
        long now = System.currentTimeMillis();
        pruneRecoveryAttempts(now);
        if (!ignoreCooldown && withinRecoveryCooldown(now)) {
            if (!isRunning()) {
                transitionTo(JdtlsClientState.DEGRADED, "Automatic recovery suppressed", reason);
            }
            LOG.info("Skipping recovery for [{}] because cooldown is active", fingerprint(reason));
            return;
        }
        if (recoveryAttemptTimestamps.size() >= RuntimeConstants.JDTLS_MAX_RECOVERY_ATTEMPTS) {
            transitionTo(JdtlsClientState.DEGRADED, "Automatic recovery suppressed", reason);
            return;
        }
        if (!beginRecovery(action, reason)) {
            return;
        }

        try {
            recoveryAttemptTimestamps.add(now);
            recoveryActionExecutionCount++;
            restartInternal(action == JdtlsRecoveryAction.REINDEX, reason, false);
        } catch (Exception ex) {
            LOG.warn("Automatic JDTLS recovery failed: {}", ex.getMessage());
            transitionTo(JdtlsClientState.FAILED, "Automatic recovery failed", ex.getMessage());
        } finally {
            finishRecovery();
            schedulePendingRecoveryIfAny();
        }
    }

    private void pruneRecoveryAttempts(long now) {
        long windowStart = now - RuntimeConstants.JDTLS_RECOVERY_WINDOW.toMillis();
        recoveryAttemptTimestamps.removeIf(ts -> ts < windowStart);
    }

    private boolean withinRecoveryCooldown(long now) {
        if (recoveryAttemptTimestamps.isEmpty()) {
            return false;
        }
        long lastAttempt = recoveryAttemptTimestamps.get(recoveryAttemptTimestamps.size() - 1);
        return now - lastAttempt < RuntimeConstants.JDTLS_RECOVERY_COOLDOWN.toMillis();
    }

    private void logRecoverySignal(String fingerprint, String reason, int count) {
        if (count == 1) {
            LOG.warn("JDTLS recovery signal detected [{}]: {}", fingerprint, reason);
        } else {
            LOG.warn("Repeated JDTLS recovery signal [{}] count={}: {}", fingerprint, count, reason);
        }
    }

    private String fingerprint(String reason) {
        if (reason == null) {
            return "";
        }
        String normalized = reason.replaceAll("\\s+", " ").trim();
        if (normalized.contains("code 368") || normalized.contains("File not found") || normalized.contains("NoSuchFileException")) {
            return "stale-workspace-file-not-found";
        }
        return normalized;
    }

    private void transitionTo(JdtlsClientState nextState, String message, String reason) {
        synchronized (stateLock) {
            state = nextState;
            stateMessage = message != null ? message : "";
            lastRecoveryReason = reason != null ? reason : "";
        }
    }

    private synchronized void startSession() throws IOException {
        prepareLanguageClientForNewSession();
        try {
            RuntimeSession newSession = sessionFactory.start(
                workspaceRoot,
                dataDir,
                jdtlsCommand,
                languageClient,
                generationCounter.incrementAndGet()
            );
            this.session = newSession;
            registerExitWatcher(newSession);
        } catch (IOException e) {
            transitionTo(JdtlsClientState.FAILED, "Failed to start JDTLS", e.getMessage());
            throw e;
        } catch (Exception e) {
            transitionTo(JdtlsClientState.FAILED, "Failed to start JDTLS", e.getMessage());
            throw new IOException("Failed to start JDTLS", e);
        }
    }

    private void prepareLanguageClientForNewSession() {
        initialized = false;
        languageClient.resetForNewSession();
        if (!recoveryInFlight || activeRecoveryState == null) {
            transitionTo(JdtlsClientState.STARTING, "Starting", lastRecoveryReason);
        }
    }

    private void registerExitWatcher(RuntimeSession exitingSession) {
        Thread watcher = new Thread(() -> {
            try {
                exitingSession.process().waitFor();
                if (!intentionallyClosingGenerations.remove(exitingSession.generation())) {
                    onProcessExited(exitingSession.process().exitValue());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "jdtls-exit-watcher-" + exitingSession.generation());
        watcher.setDaemon(true);
        watcher.start();
    }

    private synchronized void onProcessExited(int exitCode) {
        JdtlsRecoveryAction action = JdtlsRecoveryClassifier.classifyProcessExited(exitCode);
        if (action == JdtlsRecoveryAction.RESTART) {
            submitRecoverySignal(action, "JDTLS process exited with code " + exitCode);
        } else {
            transitionTo(JdtlsClientState.FAILED, "JDTLS process exited", "exitCode=" + exitCode);
        }
    }

    public synchronized void initialize() throws ExecutionException, InterruptedException, TimeoutException {
        if (initialized) {
            return;
        }
        RuntimeSession current = requireSession();

        LOG.info("Sending initialize request to JDTLS...");
        InitializeParams params = new InitializeParams();
        params.setRootUri(workspaceRoot.toUri().toString());
        params.setCapabilities(createClientCapabilities());
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(
            workspaceRoot.toUri().toString(),
            workspaceRoot.getFileName().toString()
        )));

        try {
            InitializeResult result = current.languageServer().initialize(params)
                .get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            current.languageServer().initialized(new InitializedParams());
            initialized = true;
            transitionTo(JdtlsClientState.INDEXING,
                "Initializing workspace",
                lastRecoveryReason);

            ServerCapabilities caps = result.getCapabilities();
            LOG.info("JDTLS initialized successfully!");
            LOG.info("  - Workspace symbol provider: {}", caps.getWorkspaceSymbolProvider());
            LOG.info("  - Definition provider: {}", caps.getDefinitionProvider());
            LOG.info("  - References provider: {}", caps.getReferencesProvider());
        } catch (TimeoutException e) {
            transitionTo(JdtlsClientState.FAILED, "JDTLS initialization timed out", e.getMessage());
            throw e;
        } catch (ExecutionException e) {
            transitionTo(JdtlsClientState.FAILED, "JDTLS initialization failed", e.getMessage());
            throw e;
        }
    }

    private ClientCapabilities createClientCapabilities() {
        ClientCapabilities capabilities = new ClientCapabilities();

        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        SymbolCapabilities symbolCaps = new SymbolCapabilities();
        symbolCaps.setDynamicRegistration(true);
        workspace.setSymbol(symbolCaps);
        workspace.setWorkspaceFolders(true);
        capabilities.setWorkspace(workspace);

        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
        DefinitionCapabilities defCaps = new DefinitionCapabilities();
        defCaps.setDynamicRegistration(true);
        textDocument.setDefinition(defCaps);
        ReferencesCapabilities refCaps = new ReferencesCapabilities();
        refCaps.setDynamicRegistration(true);
        textDocument.setReferences(refCaps);
        DocumentSymbolCapabilities docSymbolCaps = new DocumentSymbolCapabilities();
        docSymbolCaps.setDynamicRegistration(true);
        textDocument.setDocumentSymbol(docSymbolCaps);
        capabilities.setTextDocument(textDocument);

        return capabilities;
    }

    public List<? extends SymbolInformation> findWorkspaceSymbols(String query)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            RuntimeSession current = requireSession();
            WorkspaceSymbolParams params = new WorkspaceSymbolParams(query);
            var result = current.languageServer().getWorkspaceService().symbol(params)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result == null) {
                return List.<SymbolInformation>of();
            }
            if (result.isLeft()) {
                return result.getLeft() != null ? result.getLeft() : List.<SymbolInformation>of();
            }
            if (result.getRight() == null) {
                return List.<SymbolInformation>of();
            }
            return result.getRight().stream().map(this::toSymbolInformation).toList();
        }));
    }

    private SymbolInformation toSymbolInformation(WorkspaceSymbol ws) {
        SymbolInformation si = new SymbolInformation();
        si.setName(ws.getName());
        si.setKind(ws.getKind());
        si.setContainerName(ws.getContainerName());
        if (ws.getLocation().isLeft()) {
            si.setLocation(ws.getLocation().getLeft());
        }
        return si;
    }

    public List<? extends Location> findReferences(String uri, int line, int character)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            RuntimeSession current = requireSession();
            ensureDocumentOpen(current, uri);
            ReferenceParams params = new ReferenceParams();
            params.setTextDocument(new TextDocumentIdentifier(uri));
            params.setPosition(new Position(line, character));
            params.setContext(new ReferenceContext(true));
            var result = current.languageServer().getTextDocumentService()
                .references(params)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result != null ? result : List.<Location>of();
        }));
    }

    public List<? extends Location> findDefinition(String uri, int line, int character)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            RuntimeSession current = requireSession();
            ensureDocumentOpen(current, uri);
            DefinitionParams params = new DefinitionParams();
            params.setTextDocument(new TextDocumentIdentifier(uri));
            params.setPosition(new Position(line, character));
            var result = current.languageServer().getTextDocumentService()
                .definition(params)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result == null) {
                return List.<Location>of();
            }
            if (result.isLeft()) {
                return result.getLeft();
            }
            return result.getRight().stream()
                .map(link -> new Location(link.getTargetUri(), link.getTargetRange()))
                .toList();
        }));
    }

    public List<? extends DocumentSymbol> getDocumentSymbols(String uri)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            RuntimeSession current = requireSession();
            DocumentSymbolParams params = new DocumentSymbolParams();
            params.setTextDocument(new TextDocumentIdentifier(uri));
            var result = current.languageServer().getTextDocumentService()
                .documentSymbol(params)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result == null || result.isEmpty()) {
                return List.<DocumentSymbol>of();
            }
            var first = result.get(0);
            if (first.isRight()) {
                return result.stream().map(Either::getRight).toList();
            }
            return result.stream().map(either -> toDocumentSymbol(either.getLeft())).toList();
        }));
    }

    private DocumentSymbol toDocumentSymbol(SymbolInformation si) {
        DocumentSymbol ds = new DocumentSymbol();
        ds.setName(si.getName());
        ds.setKind(si.getKind());
        ds.setRange(si.getLocation().getRange());
        ds.setSelectionRange(si.getLocation().getRange());
        return ds;
    }

    private void ensureDocumentOpen(RuntimeSession current, String uri) throws IOException {
        if (current.openedDocuments().add(uri)) {
            try {
                Path filePath = Path.of(URI.create(uri));
                String content = Files.readString(filePath);
                TextDocumentItem item = new TextDocumentItem(uri, "java", 1, content);
                current.languageServer().getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
            } catch (Exception e) {
                current.openedDocuments().remove(uri);
                throw new IOException("Failed to open document: " + uri, e);
            }
        }
    }

    private void ensureAvailableForRequests() {
        if (state == JdtlsClientState.RECOVERING_RESTART || state == JdtlsClientState.RECOVERING_REINDEX) {
            throw new IllegalStateException("JDTLS is in status=" + state.name().toLowerCase()
                + ". Check indexing_status and retry after recovery completes.");
        }
        if (state == JdtlsClientState.DEGRADED || state == JdtlsClientState.FAILED) {
            throw new IllegalStateException("JDTLS is in status=" + state.name().toLowerCase()
                + ". Use restart_jdtls or reindex_workspace after checking indexing_status.");
        }
        if (!initialized) {
            throw new IllegalStateException("JDTLS client not initialized. Call initialize() first.");
        }
        if (!isRunning()) {
            throw new IllegalStateException("JDTLS process is no longer running.");
        }
    }

    private <T> T withRuntimeRecovery(Callable<T> call)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        try {
            return call.call();
        } catch (IllegalStateException ex) {
            if (looksLikeDeadProcessOrUninitializedRuntime(ex)) {
                handleRecoverySignal(JdtlsRecoveryAction.RESTART, ex.getMessage());
            }
            throw ex;
        } catch (ExecutionException | TimeoutException | IOException ex) {
            handleRecoverySignal(JdtlsRecoveryClassifier.classifyThrowable(ex), ex.getMessage());
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            handleRecoverySignal(JdtlsRecoveryClassifier.classifyThrowable(ex), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof IOException io) {
                throw io;
            }
            if (ex instanceof ExecutionException ee) {
                throw ee;
            }
            if (ex instanceof InterruptedException ie) {
                throw ie;
            }
            if (ex instanceof TimeoutException te) {
                throw te;
            }
            throw new RuntimeException(ex);
        }
    }

    private boolean looksLikeDeadProcessOrUninitializedRuntime(IllegalStateException ex) {
        String message = ex.getMessage();
        return message != null && (message.contains("not initialized") || message.contains("no longer running"));
    }

    private <T> T withDiagnosticsSummary(Callable<T> call)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        try {
            return call.call();
        } catch (ExecutionException | InterruptedException | TimeoutException | IOException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            logDiagnosticsSummaryIfNeeded();
        }
    }

    private void logDiagnosticsSummaryIfNeeded() {
        JdtlsLanguageClient.DiagnosticsSnapshot snapshot = languageClient.pollDiagnosticsSnapshot();
        if (snapshot.batches() > 0) {
            LOG.debug("JDTLS diagnostics summary: batches={}, entries={}", snapshot.batches(), snapshot.entries());
        }
    }

    public String restartJdtls() throws Exception {
        if (!beginRecovery(JdtlsRecoveryAction.RESTART, "manual restart requested")) {
            return "status=" + state.name().toLowerCase() + "; message=recovery already in progress";
        }
        try {
            return restartInternal(false, "manual restart requested", true);
        } finally {
            finishRecovery();
        }
    }

    public String reindexWorkspace() throws Exception {
        if (!beginRecovery(JdtlsRecoveryAction.REINDEX, "manual reindex requested")) {
            return "status=" + state.name().toLowerCase() + "; message=recovery already in progress";
        }
        try {
            return restartInternal(true, "manual reindex requested", true);
        } finally {
            finishRecovery();
        }
    }

    private String restartInternal(boolean cleanDataDir, String reason, boolean manual) throws Exception {
        try {
            closeCurrentSession();
            abortIfClosed();
            if (cleanDataDir) {
                deleteDirectory(dataDir);
                Files.createDirectories(dataDir);
            }
            startSession();
            if (closed) {
                closeCurrentSession();
                abortIfClosed();
            }
            initialize();
            if (closed) {
                closeCurrentSession();
                abortIfClosed();
            }
            return getIndexingStatus();
        } catch (Exception ex) {
            transitionTo(JdtlsClientState.FAILED,
                manual ? "JDTLS recovery failed" : "Automatic recovery failed",
                ex.getMessage());
            throw ex;
        }
    }

    private void abortIfClosed() throws IOException {
        if (closed) {
            throw new IOException("JDTLS client is closed");
        }
    }

    private boolean beginRecovery(JdtlsRecoveryAction action, String reason) {
        synchronized (stateLock) {
            if (closed || recoveryInFlight) {
                return false;
            }
            recoveryInFlight = true;
            activeRecoveryState = action == JdtlsRecoveryAction.REINDEX
                ? JdtlsClientState.RECOVERING_REINDEX
                : JdtlsClientState.RECOVERING_RESTART;
            state = activeRecoveryState;
            stateMessage = reason;
            lastRecoveryReason = reason != null ? reason : "";
            return true;
        }
    }

    private void finishRecovery() {
        synchronized (stateLock) {
            recoveryInFlight = false;
            activeRecoveryState = null;
            if (!closed
                    && initialized
                    && (state == JdtlsClientState.RECOVERING_RESTART || state == JdtlsClientState.RECOVERING_REINDEX)) {
                state = JdtlsClientState.INDEXING;
                if (stateMessage == null || stateMessage.isBlank()) {
                    stateMessage = "Initializing workspace";
                }
            }
        }
    }

    private void recordPendingRecoveryLocked(JdtlsRecoveryAction action, String reason) {
        if (action.ordinal() > pendingRecoveryAction.ordinal()) {
            pendingRecoveryAction = action;
            pendingRecoveryReason = reason;
        }
    }

    private void schedulePendingRecoveryIfAny() {
        JdtlsRecoveryAction action;
        String reason;
        synchronized (stateLock) {
            if (closed || pendingRecoveryAction == JdtlsRecoveryAction.NONE || recoveryQueued || recoveryInFlight) {
                return;
            }
            action = pendingRecoveryAction;
            reason = pendingRecoveryReason;
            pendingRecoveryAction = JdtlsRecoveryAction.NONE;
            pendingRecoveryReason = "";
            recoveryQueued = true;
        }
        try {
            recoveryExecutor.submit(() -> {
                recoveryQueued = false;
                handleRecoverySignal(action, reason, true);
            });
        } catch (RejectedExecutionException ex) {
            recoveryQueued = false;
            LOG.debug("Skipping pending recovery submission after shutdown: {}", ex.getMessage());
        }
    }

    private void closeCurrentSession() {
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
        long deadline = System.currentTimeMillis() + selfExitPollMs;
        while (current.process().isAlive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (current.process().isAlive()) {
            current.process().destroyForcibly();
        }
        current.stderrThread().interrupt();
    }

    private RuntimeSession requireSession() {
        if (session == null) {
            throw new IllegalStateException("JDTLS session is not available");
        }
        return session;
    }

    public boolean isRunning() {
        return session != null && session.process().isAlive();
    }

    public JdtlsLanguageClient getLanguageClient() {
        return languageClient;
    }

    public String getIndexingStatus() {
        return "status=" + state.name().toLowerCase()
            + "; message=" + stateMessage
            + languageClient.currentProgressSuffix()
            + (lastRecoveryReason.isEmpty() ? "" : "; reason=" + lastRecoveryReason);
    }

    public static JdtlsClient createAndInitialize(Path workspaceRoot, String jdtlsCommand) throws Exception {
        return createAndInitialize(workspaceRoot, jdtlsCommand, defaultRuntimeSessionFactory());
    }

    static JdtlsClient createAndInitialize(Path workspaceRoot, String jdtlsCommand, RuntimeSessionFactory factory) throws Exception {
        JdtlsClient client = new JdtlsClient(workspaceRoot, jdtlsCommand, factory);
        try {
            client.initialize();
            return client;
        } catch (Exception firstFailure) {
            LOG.warn("JDTLS initialization failed: {}. Cleaning data directory and retrying...", firstFailure.getMessage());
            client.close();
            deleteDirectory(client.dataDir);
            Files.createDirectories(client.dataDir);
        }
        JdtlsClient retry = new JdtlsClient(workspaceRoot, jdtlsCommand, factory);
        retry.initialize();
        return retry;
    }

    private static void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to delete JDTLS data directory {}: {}", dir, e.getMessage());
        }
    }

    Path dataDirForTests() {
        return dataDir;
    }

    void forceStateForTests(JdtlsClientState state, String message, String reason) {
        transitionTo(state, message, reason);
    }

    int recoveryActionExecutionCountForTests() {
        return recoveryActionExecutionCount;
    }

    void setInitializedForTests(boolean initialized) {
        this.initialized = initialized;
    }

    void setRunningForTests(boolean running) {
        if (!running && session != null) {
            intentionallyClosingGenerations.add(session.generation());
            session.process().destroyForcibly();
        }
    }

    void submitRecoverySignalForTests(JdtlsRecoveryAction action, String reason) {
        submitRecoverySignal(action, reason);
    }

    Future<String> startManualRecoveryInBackgroundForTests(boolean reindex) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reindex ? reindexWorkspace() : restartJdtls();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    void awaitRecoveryTasksForTests() throws Exception {
        recoveryExecutor.submit(() -> {
        }).get(1, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void close() {
        closed = true;
        diagnosticsSummaryExecutor.shutdownNow();
        recoveryExecutor.shutdownNow();
        closeCurrentSession();
        transitionTo(JdtlsClientState.FAILED, "Closed", lastRecoveryReason);
        session = null;
    }
}
