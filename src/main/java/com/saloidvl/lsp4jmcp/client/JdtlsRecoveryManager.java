package com.saloidvl.lsp4jmcp.client;

import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class JdtlsRecoveryManager {
    private static final Logger LOG = LoggerFactory.getLogger(JdtlsRecoveryManager.class);

    public interface RecoveryActions {
        void executeRestart(boolean cleanDataDir, String reason) throws Exception;
        boolean isClosed();
        boolean isRunning();
        boolean isInitialized();
    }

    private final RecoveryActions actions;

    // All fields package-private so JdtlsClientTestAccess can reach them without reflection.
    final Object stateLock = new Object();
    volatile JdtlsClientState state = JdtlsClientState.STARTING;
    volatile String stateMessage = "Starting";
    volatile String lastRecoveryReason = "";
    volatile boolean recoveryInFlight;
    volatile boolean recoveryQueued;
    volatile AtomicInteger recoveryActionExecutionCount = new AtomicInteger(0);
    volatile JdtlsClientState activeRecoveryState;
    volatile JdtlsRecoveryAction pendingRecoveryAction = JdtlsRecoveryAction.NONE;
    volatile String pendingRecoveryReason = "";
    volatile Runnable recoveryTaskStartHookForTests = null;
    final List<Long> recoveryAttemptTimestamps = new CopyOnWriteArrayList<>();
    final ConcurrentMap<String, Integer> repeatedSignalCounts = new ConcurrentHashMap<>();
    final ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jdtls-recovery");
        t.setDaemon(true);
        return t;
    });

    JdtlsRecoveryManager(RecoveryActions actions) {
        this.actions = actions;
    }

    void submitSignal(JdtlsRecoveryAction action, String reason) {
        if (action == JdtlsRecoveryAction.NONE || actions.isClosed()) {
            return;
        }
        String fp = fingerprint(reason);
        logRecoverySignal(fp, reason, repeatedSignalCounts.merge(fp, 1, Integer::sum));
        synchronized (stateLock) {
            if (actions.isClosed()) {
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
                Runnable hook = recoveryTaskStartHookForTests;
                if (hook != null) hook.run();
                handleSignal(action, reason, false);
            });
        } catch (RejectedExecutionException ex) {
            recoveryQueued = false;
            LOG.debug("Skipping recovery submission after shutdown: {}", ex.getMessage());
        }
    }

    void handleSignal(JdtlsRecoveryAction action, String reason) {
        handleSignal(action, reason, false);
    }

    void handleSignal(JdtlsRecoveryAction action, String reason, boolean ignoreCooldown) {
        if (action == JdtlsRecoveryAction.NONE || actions.isClosed()) {
            return;
        }
        if (!beginRecovery(action, reason)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            pruneRecoveryAttempts(now);
            if (!ignoreCooldown && withinRecoveryCooldown(now)) {
                if (!actions.isRunning()) {
                    transitionTo(JdtlsClientState.DEGRADED, "Automatic recovery suppressed", reason);
                }
                LOG.info("Skipping recovery for [{}] because cooldown is active", fingerprint(reason));
                return;
            }
            if (recoveryAttemptTimestamps.size() >= RuntimeConstants.JDTLS_MAX_RECOVERY_ATTEMPTS) {
                transitionTo(JdtlsClientState.DEGRADED, "Automatic recovery suppressed", reason);
                return;
            }
            recoveryAttemptTimestamps.add(now);
            recoveryActionExecutionCount.incrementAndGet();
            actions.executeRestart(action == JdtlsRecoveryAction.REINDEX, reason);
        } catch (Exception ex) {
            LOG.warn("Automatic JDTLS recovery failed: {}", ex.getMessage());
            transitionTo(JdtlsClientState.FAILED, "Automatic recovery failed", ex.getMessage());
        } finally {
            finishRecovery();
            schedulePendingRecoveryIfAny();
        }
    }

    boolean beginRecovery(JdtlsRecoveryAction action, String reason) {
        synchronized (stateLock) {
            if (actions.isClosed() || recoveryInFlight) {
                return false;
            }
            recoveryQueued = false;
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

    void finishRecovery() {
        synchronized (stateLock) {
            recoveryInFlight = false;
            activeRecoveryState = null;
            if (!actions.isClosed()
                    && actions.isInitialized()
                    && (state == JdtlsClientState.RECOVERING_RESTART
                        || state == JdtlsClientState.RECOVERING_REINDEX)) {
                state = JdtlsClientState.INDEXING;
                if (stateMessage == null || stateMessage.isBlank()) {
                    stateMessage = "Initializing workspace";
                }
            }
        }
    }

    public void transitionTo(JdtlsClientState nextState, String message, String reason) {
        synchronized (stateLock) {
            state = nextState;
            stateMessage = message != null ? message : "";
            lastRecoveryReason = reason != null ? reason : "";
        }
    }

    public void transitionToIfOpen(JdtlsClientState nextState, String message, String reason) {
        synchronized (stateLock) {
            if (actions.isClosed()) {
                return;
            }
            state = nextState;
            stateMessage = message != null ? message : "";
            lastRecoveryReason = reason != null ? reason : "";
        }
    }

    public JdtlsClientState getState() { return state; }
    public String getStateMessage() { return stateMessage; }
    public String getLastRecoveryReason() { return lastRecoveryReason; }
    public boolean isRecoveryInFlight() { return recoveryInFlight; }
    public JdtlsClientState getActiveRecoveryState() { return activeRecoveryState; }

    void shutdown() {
        recoveryExecutor.shutdownNow();
    }

    private void schedulePendingRecoveryIfAny() {
        JdtlsRecoveryAction action;
        String reason;
        synchronized (stateLock) {
            if (actions.isClosed() || pendingRecoveryAction == JdtlsRecoveryAction.NONE
                    || recoveryQueued || recoveryInFlight) {
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
                Runnable hook = recoveryTaskStartHookForTests;
                if (hook != null) hook.run();
                handleSignal(action, reason, true);
            });
        } catch (RejectedExecutionException ex) {
            recoveryQueued = false;
            LOG.debug("Skipping pending recovery submission after shutdown: {}", ex.getMessage());
        }
    }

    private void recordPendingRecoveryLocked(JdtlsRecoveryAction action, String reason) {
        JdtlsRecoveryAction activeAction = JdtlsRecoveryAction.NONE;
        if (activeRecoveryState == JdtlsClientState.RECOVERING_REINDEX) {
            activeAction = JdtlsRecoveryAction.REINDEX;
        } else if (activeRecoveryState == JdtlsClientState.RECOVERING_RESTART) {
            activeAction = JdtlsRecoveryAction.RESTART;
        }
        if (action.ordinal() <= activeAction.ordinal()) {
            return;
        }
        if (action.ordinal() > pendingRecoveryAction.ordinal()) {
            pendingRecoveryAction = action;
            pendingRecoveryReason = reason;
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
        long last = recoveryAttemptTimestamps.get(recoveryAttemptTimestamps.size() - 1);
        return now - last < RuntimeConstants.JDTLS_RECOVERY_COOLDOWN.toMillis();
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
        if (normalized.contains("code 368") || normalized.contains("File not found")
                || normalized.contains("NoSuchFileException")) {
            return "stale-workspace-file-not-found";
        }
        return normalized;
    }
}
