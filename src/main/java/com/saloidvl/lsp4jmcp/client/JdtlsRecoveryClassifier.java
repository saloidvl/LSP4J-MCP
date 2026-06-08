package com.saloidvl.lsp4jmcp.client;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class JdtlsRecoveryClassifier {

    private JdtlsRecoveryClassifier() {
    }

    public static JdtlsRecoveryAction classifyLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return JdtlsRecoveryAction.NONE;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        boolean staleDiagnosticsFailure = normalized.contains("failed to publish diagnostics")
            && (normalized.contains("file not found") || normalized.contains("nosuchfileexception"));
        if (normalized.contains("code 368") || staleDiagnosticsFailure) {
            return JdtlsRecoveryAction.REINDEX;
        }
        return JdtlsRecoveryAction.NONE;
    }

    public static JdtlsRecoveryAction classifyProcessExited(int exitCode) {
        return exitCode == 0 ? JdtlsRecoveryAction.NONE : JdtlsRecoveryAction.RESTART;
    }

    public static JdtlsRecoveryAction classifyThrowable(Throwable throwable) {
        if (throwable == null) {
            return JdtlsRecoveryAction.NONE;
        }
        JdtlsRecoveryAction fromMessage = classifyLogMessage(throwable.getMessage());
        if (fromMessage != JdtlsRecoveryAction.NONE) {
            return fromMessage;
        }
        if (throwable.getMessage() != null && throwable.getMessage().contains("Failed to open document")) {
            return JdtlsRecoveryAction.NONE;
        }
        if (throwable instanceof ExecutionException && throwable.getCause() != null) {
            JdtlsRecoveryAction causeAction = classifyThrowable(throwable.getCause());
            if (causeAction != JdtlsRecoveryAction.NONE) {
                return causeAction;
            }
        }
        if (throwable instanceof IOException) {
            return JdtlsRecoveryAction.RESTART;
        }
        return JdtlsRecoveryAction.NONE;
    }
}
