package com.saloidvl.lsp4jmcp.client;

import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TypeDefinitionCapabilities;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.TypeHierarchyCapabilities;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LSP client that connects to JDTLS (Eclipse JDT Language Server).
 */
public class JdtlsClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JdtlsClient.class);
    private static final int TIMEOUT_SECONDS = 120;
    private static final int INIT_TIMEOUT_SECONDS = 180;
    private static final long DIAGNOSTICS_SUMMARY_INTERVAL_SECONDS = 30;

    /**
     * Returns the first 16 hex characters of the SHA-256 digest of the given workspace path.
     */
    static String computeWorkspaceHash(String workspacePath) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(workspacePath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the Java SE spec and will always be available.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private final Path workspaceRoot;
    private final Path dataDir;
    private final String jdtlsCommand;
    private final JdtlsLanguageClient languageClient;
    private final DiagnosticsCache diagnosticsCache;
    final JdtlsRecoveryManager recovery;
    private final JdtlsSessionManager sessions;
    private final ScheduledExecutorService diagnosticsSummaryExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "jdtls-diagnostics-summary");
            thread.setDaemon(true);
            return thread;
        });

    volatile boolean initialized;
    private volatile boolean closed;
    private volatile Thread asyncInitThread;

    long shutdownTimeoutMs = RuntimeConstants.JDTLS_GRACEFUL_SHUTDOWN_TIMEOUT.toMillis();
    long selfExitPollMs = RuntimeConstants.JDTLS_SELF_EXIT_POLL_TIMEOUT.toMillis();

    public JdtlsClient(Path workspaceRoot, String jdtlsCommand) throws IOException {
        this(workspaceRoot, jdtlsCommand, JdtlsSessionManager.defaultFactory(), new DiagnosticsCache());
    }

    public JdtlsClient(Path workspaceRoot, String jdtlsCommand, Optional<Path> lombokJar) throws IOException {
        this(workspaceRoot, jdtlsCommand, JdtlsSessionManager.defaultFactory(lombokJar), new DiagnosticsCache());
    }

    private JdtlsClient(
        Path workspaceRoot, String jdtlsCommand,
        JdtlsSessionManager.RuntimeSessionFactory sessionFactory,
        DiagnosticsCache diagnosticsCache) throws IOException {
        this.workspaceRoot = workspaceRoot;
        this.jdtlsCommand = jdtlsCommand;
        this.diagnosticsCache = diagnosticsCache;
        this.languageClient = new JdtlsLanguageClient();
        this.languageClient.setDiagnosticsCache(diagnosticsCache);
        this.languageClient.setWorkspaceRoot(workspaceRoot);
        String workspaceHash = computeWorkspaceHash(workspaceRoot.toString());
        this.dataDir = Path.of(System.getProperty("java.io.tmpdir"), "jdtls-data", workspaceHash);
        Files.createDirectories(this.dataDir);
        this.recovery = new JdtlsRecoveryManager(new JdtlsRecoveryManager.RecoveryActions() {
            @Override
            public void executeRestart(boolean cleanDataDir, String reason) throws Exception {
                JdtlsClient.this.restartInternal(cleanDataDir, reason, false);
            }

            @Override
            public boolean isClosed() {
                return closed;
            }

            @Override
            public boolean isRunning() {
                return JdtlsClient.this.isRunning();
            }

            @Override
            public boolean isInitialized() {
                return initialized;
            }
        });
        this.sessions = new JdtlsSessionManager(
            sessionFactory,
            exitCode -> {
                JdtlsRecoveryAction action = JdtlsRecoveryClassifier.classifyProcessExited(exitCode);
                if (action == JdtlsRecoveryAction.RESTART) {
                    recovery.submitSignal(action, "JDTLS process exited with code " + exitCode);
                } else {
                    recovery.transitionTo(
                        JdtlsClientState.FAILED,
                        "JDTLS process exited",
                        "exitCode=" + exitCode
                    );
                }
            }
        );
        wireLanguageClient();
        startDiagnosticsSummaryLoop();
        startSession();
    }

    public Path getDataDir() {
        return dataDir;
    }

    public JdtlsRecoveryManager getRecoveryManager() {
        return recovery;
    }

    public JdtlsSessionManager getSessionManager() {
        return sessions;
    }

    public Thread getAsyncInitThread() {
        return asyncInitThread;
    }

    private void wireLanguageClient() {
        languageClient.setRecoverySignalHandler(this::handleRecoverySignalMessage);
        languageClient.setStatusListener(this::onLanguageClientStatusChanged);
    }

    private void onLanguageClientStatusChanged(JdtlsLanguageClient.LanguageClientSnapshot snapshot) {
        if (snapshot.ready()) {
            transitionToIfOpen(JdtlsClientState.READY, "JDTLS ready", recovery.getLastRecoveryReason());
            return;
        }
        JdtlsClientState recoveryState;
        String recoveryReason;
        synchronized (recovery.stateLock) {
            recoveryState = recovery.isRecoveryInFlight() ? recovery.getActiveRecoveryState() : null;
            recoveryReason = recovery.getLastRecoveryReason();
        }
        if (recoveryState != null) {
            transitionToIfOpen(recoveryState, snapshot.statusMessage(), recoveryReason);
        } else if (initialized) {
            transitionToIfOpen(JdtlsClientState.INDEXING, snapshot.statusMessage(), recoveryReason);
        } else {
            transitionToIfOpen(JdtlsClientState.STARTING, snapshot.statusMessage(), recoveryReason);
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
        recovery.submitSignal(action, reason);
    }

    private void transitionTo(JdtlsClientState nextState, String message, String reason) {
        recovery.transitionTo(nextState, message, reason);
    }

    private void transitionToIfOpen(JdtlsClientState nextState, String message, String reason) {
        recovery.transitionToIfOpen(nextState, message, reason);
    }

    private synchronized void startSession() throws IOException {
        if (closed) {
            return;
        }
        prepareLanguageClientForNewSession();
        try {
            sessions.createSession(workspaceRoot, dataDir, jdtlsCommand, languageClient);
        } catch (IOException e) {
            recovery.transitionTo(JdtlsClientState.FAILED, "Failed to start JDTLS", e.getMessage());
            throw e;
        } catch (Exception e) {
            recovery.transitionTo(JdtlsClientState.FAILED, "Failed to start JDTLS", e.getMessage());
            throw new IOException("Failed to start JDTLS", e);
        }
    }

    private void prepareLanguageClientForNewSession() {
        initialized = false;
        languageClient.resetForNewSession();
        if (!recovery.isRecoveryInFlight() || recovery.getActiveRecoveryState() == null) {
            transitionTo(JdtlsClientState.STARTING, "Starting", recovery.getLastRecoveryReason());
        }
    }

    public synchronized void initialize() throws ExecutionException, InterruptedException, TimeoutException {
        if (initialized) {
            return;
        }
        JdtlsSessionManager.RuntimeSession current = sessions.requireSession();

        LOG.info("Sending initialize request to JDTLS...");
        InitializeParams params = new InitializeParams();
        params.setRootUri(workspaceRoot.toUri().toString());
        params.setCapabilities(createClientCapabilities());
        params.setInitializationOptions(Map.of(
            "extendedClientCapabilities", Map.of("classFileContentsSupport", true)
        ));
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(
            workspaceRoot.toUri().toString(),
            workspaceRoot.getFileName().toString()
        )));
        LOG.debug(
            "Initialize params: rootUri={}, workspaceFolders={}",
            params.getRootUri(), params.getWorkspaceFolders());

        try {
            InitializeResult result = current.languageServer().initialize(params)
                .get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            current.languageServer().initialized(new InitializedParams());
            initialized = true;
            transitionTo(
                JdtlsClientState.INDEXING,
                "Initializing workspace",
                recovery.getLastRecoveryReason());

            ServerCapabilities caps = result.getCapabilities();
            LOG.info("JDTLS initialized successfully!");
            LOG.debug("  - Workspace symbol provider: {}", caps.getWorkspaceSymbolProvider());
            LOG.debug("  - Definition provider: {}", caps.getDefinitionProvider());
            LOG.debug("  - References provider: {}", caps.getReferencesProvider());
        } catch (TimeoutException e) {
            transitionToIfOpen(JdtlsClientState.FAILED, "JDTLS initialization timed out", e.getMessage());
            throw e;
        } catch (ExecutionException e) {
            transitionToIfOpen(JdtlsClientState.FAILED, "JDTLS initialization failed", e.getMessage());
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
        docSymbolCaps.setHierarchicalDocumentSymbolSupport(true);
        textDocument.setDocumentSymbol(docSymbolCaps);
        TypeDefinitionCapabilities typeDefinitionCaps = new TypeDefinitionCapabilities();
        typeDefinitionCaps.setDynamicRegistration(true);
        textDocument.setTypeDefinition(typeDefinitionCaps);
        TypeHierarchyCapabilities typeHierarchyCaps = new TypeHierarchyCapabilities();
        typeHierarchyCaps.setDynamicRegistration(true);
        textDocument.setTypeHierarchy(typeHierarchyCaps);
        capabilities.setTextDocument(textDocument);

        return capabilities;
    }

    public List<? extends SymbolInformation> findWorkspaceSymbols(String query)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
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
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
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
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
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

    public List<? extends Location> findImplementations(String uri, int line, int character)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            ensureDocumentOpen(current, uri);
            ImplementationParams params = new ImplementationParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character)
            );
            var result = current.languageServer().getTextDocumentService()
                .implementation(params).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result == null) {
                return List.<Location>of();
            }
            return result.isLeft() ? result.getLeft() : List.<Location>of();
        }));
    }

    public Hover getHover(String uri, int line, int character)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            ensureDocumentOpen(current, uri);
            HoverParams params = new HoverParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character)
            );
            return current.languageServer().getTextDocumentService()
                .hover(params).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }));
    }

    public List<CallHierarchyIncomingCall> findIncomingCalls(String uri, int line, int character)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            ensureDocumentOpen(current, uri);
            CallHierarchyPrepareParams prepareParams = new CallHierarchyPrepareParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character)
            );
            List<CallHierarchyItem> items = current.languageServer().getTextDocumentService()
                .prepareCallHierarchy(prepareParams).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (items == null || items.isEmpty()) {
                return null;
            }
            CallHierarchyIncomingCallsParams incomingParams =
                new CallHierarchyIncomingCallsParams(items.get(0));
            List<CallHierarchyIncomingCall> calls = current.languageServer().getTextDocumentService()
                .callHierarchyIncomingCalls(incomingParams).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return calls != null ? calls : List.of();
        }));
    }

    public List<CallHierarchyOutgoingCall> findOutgoingCalls(String uri, int line, int character)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            ensureDocumentOpen(current, uri);
            CallHierarchyPrepareParams prepareParams = new CallHierarchyPrepareParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character)
            );
            List<CallHierarchyItem> items = current.languageServer().getTextDocumentService()
                .prepareCallHierarchy(prepareParams).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (items == null || items.isEmpty()) {
                return null;
            }
            CallHierarchyOutgoingCallsParams outgoingParams =
                new CallHierarchyOutgoingCallsParams(items.get(0));
            List<CallHierarchyOutgoingCall> calls = current.languageServer().getTextDocumentService()
                .callHierarchyOutgoingCalls(outgoingParams).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return calls != null ? calls : List.of();
        }));
    }

    public List<? extends DocumentSymbol> getDocumentSymbols(String uri)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
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

    public void buildWorkspace()
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            BuildWorkspaceStatus status = current.languageServer()
                .buildWorkspace(Either.forLeft(true))
                .get(RuntimeConstants.BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (status == BuildWorkspaceStatus.FAILED) {
                throw new IOException("java/buildWorkspace returned FAILED");
            }
            if (status == BuildWorkspaceStatus.WITH_ERROR || status == BuildWorkspaceStatus.CANCELLED) {
                LOG.warn("java/buildWorkspace returned {}", status);
            }
            return null;
        }));
    }

    public void buildIncremental()
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            BuildWorkspaceStatus status = current.languageServer()
                .buildWorkspace(Either.forLeft(false))
                .get(RuntimeConstants.BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (status == BuildWorkspaceStatus.FAILED) {
                throw new IOException("java/buildWorkspace (incremental) returned FAILED");
            }
            if (status == BuildWorkspaceStatus.WITH_ERROR || status == BuildWorkspaceStatus.CANCELLED) {
                LOG.warn("java/buildWorkspace (incremental) returned {}", status);
            }
            return null;
        }));
    }

    public Object resolveStackTraceLocation(String stackFrame)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            return executeWorkspaceCommand(
                current,
                "java.project.resolveStackTraceLocation",
                List.of(stackFrame),
                TIMEOUT_SECONDS);
        }));
    }

    public String decompileClass(String classUri)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            Object result = executeWorkspaceCommand(current, "java.decompile", List.of(classUri), TIMEOUT_SECONDS);
            return result != null ? result.toString() : "";
        }));
    }

    public Object getProjects()
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            return executeWorkspaceCommand(current, "java.project.getAll", List.of(), TIMEOUT_SECONDS);
        }));
    }

    public Object getClasspath(String fileUri)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            return executeWorkspaceCommand(
                current,
                "java.project.getSettings",
                List.of(
                    fileUri, List.of(
                        "org.eclipse.jdt.ls.core.sourcePaths",
                        "org.eclipse.jdt.ls.core.referencedLibraries"
                    )),
                TIMEOUT_SECONDS
            );
        }));
    }

    public List<? extends Location> getTypeDefinition(String uri, int line, int character)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            ensureDocumentOpen(current, uri);
            TypeDefinitionParams params = new TypeDefinitionParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character)
            );
            var result = current.languageServer().getTextDocumentService()
                .typeDefinition(params).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    public TypeHierarchyData getTypeHierarchy(String uri, int line, int character)
        throws ExecutionException, InterruptedException, TimeoutException, IOException {
        return withDiagnosticsSummary(() -> withRuntimeRecovery(() -> {
            ensureAvailableForRequests();
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            ensureDocumentOpen(current, uri);

            TypeHierarchyPrepareParams prepareParams = new TypeHierarchyPrepareParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character)
            );
            List<TypeHierarchyItem> items = current.languageServer().getTextDocumentService()
                .prepareTypeHierarchy(prepareParams).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (items == null || items.isEmpty()) {
                return null;
            }

            TypeHierarchyItem item = items.get(0);
            List<TypeHierarchyItem> supertypes = current.languageServer().getTextDocumentService()
                .typeHierarchySupertypes(new TypeHierarchySupertypesParams(item))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<TypeHierarchyItem> subtypes = current.languageServer().getTextDocumentService()
                .typeHierarchySubtypes(new TypeHierarchySubtypesParams(item))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            return new TypeHierarchyData(
                item,
                supertypes != null ? supertypes : List.of(),
                subtypes != null ? subtypes : List.of()
            );
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

    private void ensureDocumentOpen(JdtlsSessionManager.RuntimeSession current, String uri) throws IOException {
        if (current.openedDocuments().add(uri)) {
            try {
                Path filePath = Path.of(URI.create(uri));
                String content = Files.readString(filePath);
                TextDocumentItem item = new TextDocumentItem(uri, "java", 1, content);
                LOG.debug("Sending didOpen: uri={}, bytes={}", uri, content.length());
                current.languageServer().getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
            } catch (Exception e) {
                current.openedDocuments().remove(uri);
                throw new IOException("Failed to open document: " + uri, e);
            }
        }
    }

    private Object executeWorkspaceCommand(
        JdtlsSessionManager.RuntimeSession current, String command,
        List<Object> arguments, long timeoutSeconds)
        throws ExecutionException, InterruptedException, TimeoutException {
        ExecuteCommandParams params = new ExecuteCommandParams(command, arguments);
        LOG.debug("Executing JDTLS workspace command: {} args={}", command, LspSummarizer.commandArguments(arguments));
        Object result = current.languageServer().getWorkspaceService()
            .executeCommand(params).get(timeoutSeconds, TimeUnit.SECONDS);
        LOG.debug("JDTLS workspace command result: {} -> {}", command, LspSummarizer.commandValue(result));
        return result;
    }

    private void ensureAvailableForRequests() {
        JdtlsClientState currentState = recovery.getState();
        if (currentState == JdtlsClientState.STARTING) {
            throw new IllegalStateException(
                "JDTLS is still initializing. Check indexing_status.");
        }
        if (currentState == JdtlsClientState.RECOVERING_RESTART
            || currentState == JdtlsClientState.RECOVERING_REINDEX) {
            throw new IllegalStateException("JDTLS is in status=" + currentState.name().toLowerCase()
                                            + ". Check indexing_status and retry after recovery completes.");
        }
        if (currentState == JdtlsClientState.DEGRADED || currentState == JdtlsClientState.FAILED) {
            throw new IllegalStateException("JDTLS is in status=" + currentState.name().toLowerCase()
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
                recovery.handleSignal(JdtlsRecoveryAction.RESTART, ex.getMessage());
            }
            throw ex;
        } catch (ExecutionException | TimeoutException | IOException ex) {
            recovery.handleSignal(JdtlsRecoveryClassifier.classifyThrowable(ex), ex.getMessage());
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recovery.handleSignal(JdtlsRecoveryClassifier.classifyThrowable(ex), ex.getMessage());
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
        if (!recovery.beginRecovery(JdtlsRecoveryAction.RESTART, "manual restart requested")) {
            return "status=" + recovery.getState().name().toLowerCase() + "; message=recovery already in progress";
        }
        try {
            return restartInternal(false, "manual restart requested", true);
        } finally {
            recovery.finishRecovery();
        }
    }

    public String reindexWorkspace() throws Exception {
        if (!recovery.beginRecovery(JdtlsRecoveryAction.REINDEX, "manual reindex requested")) {
            return "status=" + recovery.getState().name().toLowerCase() + "; message=recovery already in progress";
        }
        try {
            if (closed) {
                return getIndexingStatus();
            }
            JdtlsSessionManager.RuntimeSession current = sessions.requireSession();
            BuildWorkspaceStatus status = current.languageServer()
                .buildWorkspace(Either.forLeft(true))
                .get(RuntimeConstants.BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (status == BuildWorkspaceStatus.FAILED) {
                throw new IOException("java/buildWorkspace CLEAN+FULL returned FAILED");
            }
            if (status == BuildWorkspaceStatus.WITH_ERROR || status == BuildWorkspaceStatus.CANCELLED) {
                LOG.warn("reindexWorkspace: java/buildWorkspace returned {}", status);
            }
            // buildWorkspace runs on an already-initialized JDTLS — no new ServiceReady arrives.
            // Transition to READY directly so finishRecovery() doesn't leave us stuck in INDEXING.
            transitionToIfOpen(JdtlsClientState.READY, "Reindex complete", recovery.getLastRecoveryReason());
        } catch (Exception ex) {
            transitionTo(JdtlsClientState.FAILED, "JDTLS reindex failed", ex.getMessage());
            throw ex;
        } finally {
            recovery.finishRecovery();
        }
        return getIndexingStatus();
    }

    private String restartInternal(boolean cleanDataDir, String reason, boolean manual) throws Exception {
        try {
            closeCurrentSession();
            diagnosticsCache.clear();
            if (closed) {
                return getIndexingStatus();
            }
            if (cleanDataDir) {
                deleteDirectory(dataDir);
                Files.createDirectories(dataDir);
            }
            startSession();
            if (closed) {
                closeCurrentSession();
                return getIndexingStatus();
            }
            initialize();
            if (closed) {
                closeCurrentSession();
                return getIndexingStatus();
            }
            return getIndexingStatus();
        } catch (Exception ex) {
            transitionTo(
                JdtlsClientState.FAILED,
                manual ? "JDTLS recovery failed" : "Automatic recovery failed",
                ex.getMessage());
            throw ex;
        }
    }

    private void closeCurrentSession() {
        sessions.closeCurrentSession(shutdownTimeoutMs, selfExitPollMs);
    }

    public boolean isRunning() {
        return sessions.isRunning();
    }

    public JdtlsLanguageClient getLanguageClient() {
        return languageClient;
    }

    public DiagnosticsCache getDiagnosticsCache() {
        return diagnosticsCache;
    }

    public String getIndexingStatus() {
        return "status=" + recovery.getState().name().toLowerCase()
               + "; message=" + recovery.getStateMessage()
               + languageClient.currentProgressSuffix()
               + (recovery.getLastRecoveryReason().isEmpty() ? "" : "; reason=" + recovery.getLastRecoveryReason());
    }

    public static JdtlsClient createAndInitialize(Path workspaceRoot, String jdtlsCommand) throws Exception {
        return createAndInitialize(workspaceRoot, jdtlsCommand, JdtlsSessionManager.defaultFactory());
    }

    public static JdtlsClient createAndInitialize(
        Path workspaceRoot, String jdtlsCommand,
        Optional<Path> lombokJar) throws Exception {
        return createAndInitialize(
            workspaceRoot, jdtlsCommand,
            JdtlsSessionManager.defaultFactory(lombokJar));
    }

    static JdtlsClient createAndInitialize(
        Path workspaceRoot, String jdtlsCommand,
        JdtlsSessionManager.RuntimeSessionFactory factory) throws Exception {
        JdtlsClient client = new JdtlsClient(workspaceRoot, jdtlsCommand, factory, new DiagnosticsCache());
        try {
            client.initialize();
            return client;
        } catch (Exception firstFailure) {
            LOG.warn(
                "JDTLS initialization failed: {}. Cleaning data directory and retrying...",
                firstFailure.getMessage());
            client.close();
            deleteDirectory(client.dataDir);
            Files.createDirectories(client.dataDir);
        }
        JdtlsClient retry = new JdtlsClient(workspaceRoot, jdtlsCommand, factory, new DiagnosticsCache());
        retry.initialize();
        return retry;
    }

    public static JdtlsClient createAndInitializeAsync(Path workspaceRoot, String jdtlsCommand)
        throws IOException {
        return createAndInitializeAsync(workspaceRoot, jdtlsCommand, JdtlsSessionManager.defaultFactory());
    }

    public static JdtlsClient createAndInitializeAsync(
        Path workspaceRoot, String jdtlsCommand,
        Optional<Path> lombokJar) throws IOException {
        return createAndInitializeAsync(
            workspaceRoot, jdtlsCommand,
            JdtlsSessionManager.defaultFactory(lombokJar));
    }

    static JdtlsClient createAndInitializeAsync(
        Path workspaceRoot, String jdtlsCommand,
        JdtlsSessionManager.RuntimeSessionFactory factory) throws IOException {
        JdtlsClient client = new JdtlsClient(
            workspaceRoot,
            jdtlsCommand,
            factory,
            new DiagnosticsCache()
        );
        Thread thread = new Thread(
            () -> {
                try {
                    client.initialize();
                } catch (Throwable firstEx) {
                    if (firstEx instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    if (client.closed) {
                        return;
                    }
                    LOG.warn(
                        "Async JDTLS init failed (was: {}): {}. Cleaning data dir and retrying...",
                        client.getIndexingStatus(),
                        firstEx.getMessage()
                    );
                    try {
                        client.restartInternal(true, "async init retry after first failure", true);
                    } catch (Throwable retryEx) {
                        if (retryEx instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        if (client.closed) {
                            return;
                        }
                        LOG.warn(
                            "Async JDTLS init retry also failed (was: {}): {}",
                            client.getIndexingStatus(),
                            retryEx.getMessage()
                        );
                        client.transitionToIfOpen(
                            JdtlsClientState.FAILED,
                            "Async initialization failed",
                            retryEx.getMessage()
                        );
                    }
                }
            }, "jdtls-async-init");
        thread.setDaemon(true);
        client.asyncInitThread = thread;
        thread.start();
        return client;
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

    @Override
    public void close() {
        closed = true;
        Thread asyncThread = asyncInitThread;
        if (asyncThread != null) {
            asyncThread.interrupt();
        }
        diagnosticsSummaryExecutor.shutdownNow();
        recovery.shutdown();
        closeCurrentSession();
        recovery.transitionTo(JdtlsClientState.FAILED, "Closed", recovery.getLastRecoveryReason());
        sessions.clearSession();
    }
}
