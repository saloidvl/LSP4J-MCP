package com.saloidvl.lsp4jmcp.client;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class JdtlsClientTestAccess {
    private final JdtlsClient client;

    JdtlsClientTestAccess(JdtlsClient client) {
        this.client = client;
    }

    Path dataDirForTests() {
        return client.getDataDir();
    }

    void forceStateForTests(JdtlsClientState state, String message, String reason) {
        client.getRecoveryManager().transitionTo(state, message, reason);
    }

    int recoveryActionExecutionCountForTests() {
        return client.getRecoveryManager().recoveryActionExecutionCount.get();
    }

    void setInitializedForTests(boolean initialized) {
        client.initialized = initialized;
    }

    void forceRecoveryStateForTests(boolean inFlight, JdtlsClientState activeState) {
        synchronized (client.getRecoveryManager().stateLock) {
            client.getRecoveryManager().recoveryInFlight = inFlight;
            client.getRecoveryManager().activeRecoveryState = activeState;
        }
    }

    void setRunningForTests(boolean running) {
        if (!running && client.getSessionManager().session != null) {
            client.getSessionManager().intentionallyClosingGenerations.add(client.getSessionManager().session.generation());
            client.getSessionManager().session.process().destroyForcibly();
        }
    }

    void submitRecoverySignalForTests(JdtlsRecoveryAction action, String reason) {
        client.getRecoveryManager().submitSignal(action, reason);
    }

    void handleRecoverySignalForTests(JdtlsRecoveryAction action, String reason) {
        client.getRecoveryManager().handleSignal(action, reason);
    }

    void setRecoveryTaskStartHookForTests(Runnable hook) {
        client.getRecoveryManager().recoveryTaskStartHookForTests = hook;
    }

    Thread asyncInitThreadForTests() {
        return client.getAsyncInitThread();
    }

    Future<String> startManualRecoveryInBackgroundForTests(boolean reindex) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reindex ? client.reindexWorkspace() : client.restartJdtls();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    void awaitRecoveryTasksForTests() throws Exception {
        client.getRecoveryManager().recoveryExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);
    }
}
