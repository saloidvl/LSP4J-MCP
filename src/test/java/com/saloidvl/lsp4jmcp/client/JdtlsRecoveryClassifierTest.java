package com.saloidvl.lsp4jmcp.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class JdtlsRecoveryClassifierTest {

    @Test
    void classifyLogMessage_returnsReindexForCode368FileNotFound() {
        assertThat(JdtlsRecoveryClassifier.classifyLogMessage(
            "Core Exception [code 368] File not found: /repo/Test.java"))
            .isEqualTo(JdtlsRecoveryAction.REINDEX);
    }

    @Test
    void classifyLogMessage_returnsNoneForUnknownMessage() {
        assertThat(JdtlsRecoveryClassifier.classifyLogMessage("random warning"))
            .isEqualTo(JdtlsRecoveryAction.NONE);
    }

    @Test
    void classifyLogMessage_doesNotReindexForGenericFileNotFoundWarning() {
        assertThat(JdtlsRecoveryClassifier.classifyLogMessage("File not found: /tmp/foo.txt"))
            .isEqualTo(JdtlsRecoveryAction.NONE);
    }

    @Test
    void classifyProcessExited_returnsRestartForNonZeroExit() {
        assertThat(JdtlsRecoveryClassifier.classifyProcessExited(1))
            .isEqualTo(JdtlsRecoveryAction.RESTART);
    }

    @Test
    void classifyThrowable_mapsKnownRuntimeFailuresToRestart() {
        assertThat(JdtlsRecoveryClassifier.classifyThrowable(new IOException("process died")))
            .isEqualTo(JdtlsRecoveryAction.RESTART);
        assertThat(JdtlsRecoveryClassifier.classifyThrowable(new TimeoutException("timeout")))
            .isEqualTo(JdtlsRecoveryAction.NONE);
        assertThat(JdtlsRecoveryClassifier.classifyThrowable(new ExecutionException(new RuntimeException("boom"))))
            .isEqualTo(JdtlsRecoveryAction.NONE);
    }

    @Test
    void classifyThrowable_unwrapsExecutionExceptionForStaleWorkspaceSignals() {
        assertThat(JdtlsRecoveryClassifier.classifyThrowable(
            new ExecutionException(new IOException("Core Exception [code 368] File not found: /repo/Test.java"))))
            .isEqualTo(JdtlsRecoveryAction.REINDEX);
    }

    @Test
    void classifyThrowable_returnsNoneForGenericExecutionExceptionAndInterrupt() {
        assertThat(JdtlsRecoveryClassifier.classifyThrowable(
            new ExecutionException(new RuntimeException("request failed"))))
            .isEqualTo(JdtlsRecoveryAction.NONE);
        assertThat(JdtlsRecoveryClassifier.classifyThrowable(new InterruptedException("caller interrupted")))
            .isEqualTo(JdtlsRecoveryAction.NONE);
        assertThat(JdtlsRecoveryClassifier.classifyThrowable(
            new IOException("Failed to open document: file:///missing/File.java")))
            .isEqualTo(JdtlsRecoveryAction.NONE);
    }
}
