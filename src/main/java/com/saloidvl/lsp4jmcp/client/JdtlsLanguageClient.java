package com.saloidvl.lsp4jmcp.client;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Language client implementation that receives callbacks from JDTLS.
 * Implements all required methods to avoid UnsupportedOperationExceptions.
 */
public class JdtlsLanguageClient implements LanguageClient {
    private static final Logger LOG = LoggerFactory.getLogger(JdtlsLanguageClient.class);
    
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile String currentStatus = "Starting";
    private volatile boolean ready = false;
    private final AtomicInteger lastLoggedPct = new AtomicInteger(-1);
    private final AtomicInteger diagnosticsBatchCount = new AtomicInteger();
    private final AtomicInteger diagnosticsEntryCount = new AtomicInteger();
    private volatile Consumer<String> recoverySignalHandler = reason -> {};
    private volatile Consumer<LanguageClientSnapshot> statusListener = snapshot -> {};

    // Current progress snapshot — readable via getIndexingProgress()
    private volatile String progressTitle = "";
    private volatile String progressMessage = "";
    private volatile int progressPct = -1;
    
    /**
     * JDTLS-specific status notification.
     * This is called by JDTLS to report its status (e.g., "Starting", "Ready").
     */
    @JsonNotification("language/status")
    public void languageStatus(StatusReport status) {
        LOG.info("JDTLS status [{}]: {}", status.getType(), status.getMessage());
        currentStatus = status.getMessage();
        
        if ("ServiceReady".equals(status.getType()) ||
            (status.getMessage() != null && status.getMessage().contains("Ready"))) {
            LOG.info("JDTLS is ready!");
            ready = true;
            readyLatch.countDown();
        }
        statusListener.accept(snapshot());
    }
    
    /**
     * Wait for JDTLS to report ready status.
     * @param timeout timeout value
     * @param unit timeout unit
     * @return true if JDTLS became ready, false if timeout occurred
     */
    public boolean waitForReady(long timeout, TimeUnit unit) throws InterruptedException {
        return readyLatch.await(timeout, unit);
    }
    
    public String getCurrentStatus() {
        return currentStatus;
    }

    public boolean isReady() {
        return ready;
    }

    /** Returns a human-readable snapshot of the current indexing progress. */
    public String getIndexingProgress() {
        if (ready) {
            return "ready";
        }
        StringBuilder sb = new StringBuilder("indexing");
        if (!progressTitle.isEmpty()) sb.append(" [").append(progressTitle).append("]");
        if (!progressMessage.isEmpty()) sb.append(": ").append(progressMessage);
        if (progressPct >= 0) sb.append(" (").append(progressPct).append("%)");
        sb.append(" — status: ").append(currentStatus);
        return sb.toString();
    }

    public String currentProgressSuffix() {
        StringBuilder sb = new StringBuilder();
        if (!progressTitle.isEmpty()) {
            sb.append("; title=").append(progressTitle);
        }
        if (!progressMessage.isEmpty()) {
            sb.append("; progress_message=").append(progressMessage);
        }
        if (progressPct >= 0) {
            sb.append("; progress=").append(progressPct).append("%");
        }
        return sb.toString();
    }

    public void resetForNewSession() {
        readyLatch = new CountDownLatch(1);
        currentStatus = "Starting";
        ready = false;
        progressTitle = "";
        progressMessage = "";
        progressPct = -1;
        lastLoggedPct.set(-1);
        diagnosticsBatchCount.set(0);
        diagnosticsEntryCount.set(0);
        statusListener.accept(snapshot());
    }

    public DiagnosticsSnapshot drainDiagnosticsSnapshotForTests() {
        return pollDiagnosticsSnapshot();
    }

    public DiagnosticsSnapshot pollDiagnosticsSnapshot() {
        return new DiagnosticsSnapshot(
            diagnosticsBatchCount.getAndSet(0),
            diagnosticsEntryCount.getAndSet(0)
        );
    }

    void setProgressForTests(String title, String message, int pct) {
        progressTitle = title;
        progressMessage = message;
        progressPct = pct;
    }

    public void setRecoverySignalHandler(Consumer<String> handler) {
        this.recoverySignalHandler = Objects.requireNonNull(handler);
    }

    public void setStatusListener(Consumer<LanguageClientSnapshot> listener) {
        this.statusListener = Objects.requireNonNull(listener);
    }

    private LanguageClientSnapshot snapshot() {
        return new LanguageClientSnapshot(ready, currentStatus, progressMessage, progressPct);
    }

    public record DiagnosticsSnapshot(int batches, int entries) {
    }

    public record LanguageClientSnapshot(boolean ready, String statusMessage, String progressMessage, int progressPct) {
    }
    
    /**
     * JDTLS status report object.
     */
    public static class StatusReport {
        private String type;
        private String message;
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    @Override
    public void telemetryEvent(Object object) {
        LOG.debug("Telemetry event: {}", object);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        diagnosticsBatchCount.incrementAndGet();
        diagnosticsEntryCount.addAndGet(diagnostics.getDiagnostics().size());
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        LOG.info("JDTLS message [{}]: {}",
            messageParams.getType(),
            messageParams.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        LOG.info("JDTLS message request: {}", requestParams.getMessage());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        if (message.getType() == MessageType.Error || message.getType() == MessageType.Warning) {
            recoverySignalHandler.accept(message.getMessage());
        }
        switch (message.getType()) {
            case Error -> LOG.error("JDTLS: {}", message.getMessage());
            case Warning -> LOG.warn("JDTLS: {}", message.getMessage());
            case Info -> LOG.info("JDTLS: {}", message.getMessage());
            case Log -> LOG.debug("JDTLS: {}", message.getMessage());
        }
    }

    /**
     * Handle capability registration requests from the server.
     * JDTLS uses dynamic capability registration for features like workspace symbols.
     */
    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        LOG.debug("Register capability request: {}", params.getRegistrations().size());
        for (Registration registration : params.getRegistrations()) {
            LOG.debug("  - Registered: {} (method: {})",
                registration.getId(),
                registration.getMethod());
        }
        // Accept all capability registrations
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handle capability unregistration requests from the server.
     */
    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        LOG.debug("Unregister capability request: {}", params.getUnregisterations().size());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handle workspace folder requests from the server.
     */
    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        LOG.debug("Workspace folders requested");
        // Return empty list - the folders are set during initialization
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Handle configuration requests from the server.
     */
    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
        LOG.debug("Configuration requested for {} items", params.getItems().size());
        // Return empty config for each item
        return CompletableFuture.completedFuture(
            params.getItems().stream()
                .map(item -> (Object) null)
                .toList()
        );
    }

    /**
     * Handle apply edit requests from the server.
     */
    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        LOG.debug("Apply edit requested");
        // We don't support editing, but acknowledge the request
        return CompletableFuture.completedFuture(
            new ApplyWorkspaceEditResponse(false)
        );
    }

    /**
     * Handle semantic tokens refresh requests.
     */
    @Override
    public CompletableFuture<Void> refreshSemanticTokens() {
        LOG.debug("Refresh semantic tokens requested");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handle code lens refresh requests.
     */
    @Override
    public CompletableFuture<Void> refreshCodeLenses() {
        LOG.debug("Refresh code lenses requested");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handle inlay hint refresh requests.
     */
    @Override
    public CompletableFuture<Void> refreshInlayHints() {
        LOG.debug("Refresh inlay hints requested");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handle inline value refresh requests.
     */
    @Override
    public CompletableFuture<Void> refreshInlineValues() {
        LOG.debug("Refresh inline values requested");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handle diagnostic refresh requests.
     */
    @Override
    public CompletableFuture<Void> refreshDiagnostics() {
        LOG.debug("Refresh diagnostics requested");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handle create files requests.
     */
    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        LOG.debug("Create progress: {}", params.getToken());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handle progress notifications.
     */
    @Override
    public void notifyProgress(ProgressParams params) {
        var value = params.getValue();
        if (value == null || !value.isLeft()) {
            return;
        }
        WorkDoneProgressNotification notification = value.getLeft();
        if (notification instanceof WorkDoneProgressBegin begin) {
            progressTitle = begin.getTitle() != null ? begin.getTitle() : "";
            progressMessage = begin.getMessage() != null ? begin.getMessage() : "";
            progressPct = begin.getPercentage() != null ? begin.getPercentage() : -1;
            lastLoggedPct.set(progressPct);
            LOG.info("JDTLS [{}] started: {}{}", progressTitle,
                progressMessage.isEmpty() ? "" : progressMessage + " ",
                progressPct >= 0 ? progressPct + "%" : "");
        } else if (notification instanceof WorkDoneProgressReport report) {
            progressMessage = report.getMessage() != null ? report.getMessage() : progressMessage;
            int pct = report.getPercentage() != null ? report.getPercentage() : -1;
            progressPct = pct;
            if (pct < 0 || pct - lastLoggedPct.get() >= 10) {
                LOG.info("JDTLS indexing: {}{}",
                    progressMessage.isEmpty() ? "" : progressMessage + " ",
                    pct >= 0 ? pct + "%" : "");
                lastLoggedPct.set(pct);
            }
        } else if (notification instanceof WorkDoneProgressEnd end) {
            progressMessage = end.getMessage() != null ? end.getMessage() : "";
            progressPct = -1;
            lastLoggedPct.set(-1);
            LOG.info("JDTLS task done: {}", progressMessage.isEmpty() ? "OK" : progressMessage);
        }
        statusListener.accept(snapshot());
    }

    /**
     * Handle showDocument requests.
     */
    @Override
    public CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
        LOG.debug("Show document: {}", params.getUri());
        return CompletableFuture.completedFuture(new ShowDocumentResult(false));
    }
}
