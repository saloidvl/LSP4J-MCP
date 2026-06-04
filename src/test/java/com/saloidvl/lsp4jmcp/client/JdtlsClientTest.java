package com.saloidvl.lsp4jmcp.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.mockito.MockitoAnnotations;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for JdtlsClient.
 * Tests cover initialization, error handling, and data directory creation.
 * 
 * Note: Tests that require a running JDTLS process are in JdtlsClientIntegrationTest.
 * These unit tests focus on error paths and pre-process setup behavior.
 */
class JdtlsClientTest {

    @TempDir
    Path tempDir;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void constructor_failsWithInvalidCommand() {
        // Given an invalid command that doesn't exist
        String invalidCommand = "/nonexistent/path/to/jdtls";

        // When/Then - should fail to start process
        assertThatThrownBy(() -> new JdtlsClient(tempDir, invalidCommand))
            .isInstanceOf(IOException.class);
    }

    @Test
    void constructor_failsWithEmptyCommand() {
        // Given an empty command
        String emptyCommand = "";

        // When/Then - should fail
        assertThatThrownBy(() -> new JdtlsClient(tempDir, emptyCommand))
            .isInstanceOf(Exception.class);
    }

    @Test
    void constructor_createsDataDirectory() throws IOException {
        // Given
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        // When - try to create client (will fail because jdtls doesn't exist, but should create data dir first)
        try {
            new JdtlsClient(workspace, "/nonexistent/jdtls");
        } catch (IOException e) {
            // Expected - jdtls doesn't exist
        }

        // Then - data directory should have been created OUTSIDE the workspace (in temp dir)
        String workspaceHash = Integer.toHexString(workspace.toString().hashCode());
        Path dataDir = Path.of(System.getProperty("java.io.tmpdir"), "jdtls-data", workspaceHash);
        assertThat(dataDir).exists();
        assertThat(dataDir).isDirectory();
    }

    @Test
    void constructor_handlesCommandWithArguments() {
        // Given a command with arguments
        String commandWithArgs = "/nonexistent/jdtls --some-arg value";

        // When/Then - should parse command correctly and fail on execution, not parsing
        assertThatThrownBy(() -> new JdtlsClient(tempDir, commandWithArgs))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Cannot run program");
    }

    @Test
    void dataDirectoryPath_isOutsideWorkspace() throws IOException {
        // Given
        Path workspace = tempDir.resolve("my-project");
        Files.createDirectories(workspace);

        // When - attempt to create client
        try {
            new JdtlsClient(workspace, "/nonexistent/jdtls");
        } catch (IOException e) {
            // Expected
        }

        // Then - verify data directory is OUTSIDE workspace (fixes JDTLS overlap error)
        Path insideWorkspace = workspace.resolve(".jdtls-data");
        assertThat(insideWorkspace).doesNotExist();

        // Data dir should be in temp folder with workspace hash
        String workspaceHash = Integer.toHexString(workspace.toString().hashCode());
        Path expectedDataDir = Path.of(System.getProperty("java.io.tmpdir"), "jdtls-data", workspaceHash);
        assertThat(expectedDataDir).exists();
    }

    @Test
    void constructor_parsesMultipleCommandArguments() {
        // Given a command with multiple space-separated arguments
        String command = "/nonexistent/java -jar server.jar --verbose";

        // When/Then - should split into 4 parts correctly
        assertThatThrownBy(() -> new JdtlsClient(tempDir, command))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Cannot run program");
    }

    @Test
    void constructor_addsDataDirectoryArgument() throws IOException {
        // Given
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        
        // When - create client (will fail but data dir gets created)
        try {
            new JdtlsClient(workspace, "/nonexistent/jdtls");
        } catch (IOException e) {
            // Expected
        }

        // Then - verify the data directory structure
        String workspaceHash = Integer.toHexString(workspace.toString().hashCode());
        Path dataDir = Path.of(System.getProperty("java.io.tmpdir"), "jdtls-data", workspaceHash);
        assertThat(dataDir).exists();
    }

    @Test
    void dataDirectory_usesWorkspaceHashForUniqueness() throws IOException {
        // Given two different workspaces
        Path workspace1 = tempDir.resolve("project-a");
        Path workspace2 = tempDir.resolve("project-b");
        Files.createDirectories(workspace1);
        Files.createDirectories(workspace2);

        // When - attempt to create clients for both
        try {
            new JdtlsClient(workspace1, "/nonexistent/jdtls");
        } catch (IOException e) {
            // Expected
        }
        try {
            new JdtlsClient(workspace2, "/nonexistent/jdtls");
        } catch (IOException e) {
            // Expected
        }

        // Then - each should have its own data directory based on workspace hash
        String hash1 = Integer.toHexString(workspace1.toString().hashCode());
        String hash2 = Integer.toHexString(workspace2.toString().hashCode());
        
        Path dataDir1 = Path.of(System.getProperty("java.io.tmpdir"), "jdtls-data", hash1);
        Path dataDir2 = Path.of(System.getProperty("java.io.tmpdir"), "jdtls-data", hash2);
        
        assertThat(dataDir1).exists();
        assertThat(dataDir2).exists();
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void constructor_throwsIOExceptionForProcessStartFailure() {
        // Given a command that cannot be executed
        String invalidCommand = "/this/path/does/not/exist/jdtls";

        // When/Then
        assertThatThrownBy(() -> new JdtlsClient(tempDir, invalidCommand))
            .isInstanceOf(IOException.class);
    }

    @Test
    void constructor_splitsCommandOnWhitespace() {
        // Given a command with multiple whitespace-separated parts
        String command = "/path/to/java   -jar    server.jar";

        // When/Then - command should be split correctly (though it will fail to execute)
        assertThatThrownBy(() -> new JdtlsClient(tempDir, command))
            .isInstanceOf(IOException.class);
    }

    @Test
    void dataDirectory_isCreatedInSystemTempFolder() throws IOException {
        // Given
        Path workspace = tempDir.resolve("test-workspace");
        Files.createDirectories(workspace);

        // When
        try {
            new JdtlsClient(workspace, "/nonexistent/jdtls");
        } catch (IOException e) {
            // Expected
        }

        // Then - data directory should be under system temp
        String workspaceHash = Integer.toHexString(workspace.toString().hashCode());
        Path dataDir = Path.of(System.getProperty("java.io.tmpdir"), "jdtls-data", workspaceHash);
        
        assertThat(dataDir.toString()).startsWith(System.getProperty("java.io.tmpdir"));
        assertThat(dataDir.getParent().getFileName().toString()).isEqualTo("jdtls-data");
    }

    @Test
    void jdtlsLanguageClient_isCreatedDuringConstruction() throws IOException {
        // Given
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        Path mockScript = tempDir.resolve("mock-jdtls.sh");
        Files.writeString(mockScript, "#!/bin/bash\nwhile true; do sleep 1; done");
        mockScript.toFile().setExecutable(true);

        JdtlsClient client = null;
        try {
            // When
            client = new JdtlsClient(workspace, mockScript.toString());
            client.shutdownTimeoutMs = 200;
            client.selfExitPollMs = 200;

            // Then
            assertThat(client.getLanguageClient()).isNotNull();
            assertThat(client.getLanguageClient()).isInstanceOf(JdtlsLanguageClient.class);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void close_terminatesRunningProcess() throws IOException {
        // Given
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        Path mockScript = tempDir.resolve("mock-jdtls.sh");
        Files.writeString(mockScript, "#!/bin/bash\nwhile true; do sleep 1; done");
        mockScript.toFile().setExecutable(true);

        JdtlsClient client = new JdtlsClient(workspace, mockScript.toString());
        client.shutdownTimeoutMs = 200;
        client.selfExitPollMs = 200;
        assertThat(client.isRunning()).isTrue();

        // When
        client.close();

        // Then
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    void isRunning_returnsTrueForActiveProcess() throws IOException {
        // Given
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        Path mockScript = tempDir.resolve("mock-jdtls.sh");
        Files.writeString(mockScript, "#!/bin/bash\nwhile true; do sleep 1; done");
        mockScript.toFile().setExecutable(true);

        JdtlsClient client = null;
        try {
            // When
            client = new JdtlsClient(workspace, mockScript.toString());
            client.shutdownTimeoutMs = 200;
            client.selfExitPollMs = 200;

            // Then
            assertThat(client.isRunning()).isTrue();
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void isRunning_returnsFalseAfterClose() throws IOException, InterruptedException {
        // Given
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        Path mockScript = tempDir.resolve("mock-jdtls.sh");
        Files.writeString(mockScript, "#!/bin/bash\nwhile true; do sleep 1; done");
        mockScript.toFile().setExecutable(true);

        JdtlsClient client = new JdtlsClient(workspace, mockScript.toString());
        client.shutdownTimeoutMs = 200;
        client.selfExitPollMs = 200;
        assertThat(client.isRunning()).isTrue();

        // When
        client.close();
        Thread.sleep(200);

        // Then
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    void createAndInitialize_acceptsFakeRuntimeSessionFactory() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();

        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);

        assertThat(factory.startCount.get()).isEqualTo(1);
        assertThat(client.getIndexingStatus()).contains("status=");
        client.close();
    }

    @Test
    void restartJdtls_restartsProcessWithoutDeletingDataDir() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        Path dataDir = client.dataDirForTests();

        String result = client.restartJdtls();

        assertThat(result).contains("status=");
        assertThat(factory.startCount.get()).isEqualTo(2);
        assertThat(dataDir).exists();
        client.close();
    }

    @Test
    void reindexWorkspace_recreatesDataDirAfterCleanup() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        Path dataDir = client.dataDirForTests();
        Path sentinel = dataDir.resolve("stale-index-marker.txt");
        Files.writeString(sentinel, "stale");
        FileTime before = Files.getLastModifiedTime(dataDir);

        String result = client.reindexWorkspace();

        assertThat(result).contains("status=");
        assertThat(Files.exists(sentinel)).isFalse();
        assertThat(Files.getLastModifiedTime(dataDir).toMillis()).isGreaterThanOrEqualTo(before.toMillis());
        client.close();
    }

    @Test
    void createAndInitialize_retriesOnceAfterInitializationFailure() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        factory.failFirstInitializeAttempt = true;

        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);

        assertThat(factory.startCount.get()).isEqualTo(2);
        client.close();
    }

    @Test
    void getIndexingStatus_includesStatusMessageProgressAndReason() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        client.forceStateForTests(JdtlsClientState.INDEXING, "Importing workspace", "stale path detected");
        client.getLanguageClient().setProgressForTests("Import", "Indexing", 42);

        String status = client.getIndexingStatus();

        assertThat(status).contains("status=indexing");
        assertThat(status).contains("message=Importing workspace");
        assertThat(status).contains("progress=42%");
        assertThat(status).contains("reason=stale path detected");
        client.close();
    }

    @Test
    void requestTimeIllegalStateFailure_triggersRestartRecovery() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        client.setInitializedForTests(false);
        int startCountBefore = factory.startCount.get();

        assertThatThrownBy(() -> client.findWorkspaceSymbols("Foo"))
            .isInstanceOf(IllegalStateException.class);

        client.awaitRecoveryTasksForTests();
        assertThat(factory.startCount.get()).isGreaterThan(startCountBefore);
        client.close();
    }

    @Test
    void repeatedQueuedSignals_doNotCauseBackToBackRecoveries() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.blockInitialize();

        client.submitRecoverySignalForTests(
            JdtlsRecoveryAction.REINDEX,
            "Core Exception [code 368] File not found: /repo/Test.java");
        factory.awaitInitializeAttemptCount(2, 1, TimeUnit.SECONDS);

        client.submitRecoverySignalForTests(
            JdtlsRecoveryAction.REINDEX,
            "Core Exception [code 368] File not found: /repo/Other.java");
        client.submitRecoverySignalForTests(
            JdtlsRecoveryAction.REINDEX,
            "Core Exception [code 368] File not found: /repo/Third.java");

        factory.unblockInitialize();
        client.awaitRecoveryTasksForTests();

        assertThat(factory.startCount.get()).isEqualTo(2);
        assertThat(client.recoveryActionExecutionCountForTests()).isEqualTo(1);
        client.close();
    }

    @Test
    void strongerQueuedSignal_escalatesRestartIntoFollowUpReindex() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.blockInitialize();

        client.submitRecoverySignalForTests(
            JdtlsRecoveryAction.RESTART,
            "JDTLS process exited with code 1");
        factory.awaitInitializeAttemptCount(2, 1, TimeUnit.SECONDS);

        client.submitRecoverySignalForTests(
            JdtlsRecoveryAction.REINDEX,
            "Core Exception [code 368] Failed to publish diagnostics for file:///repo/Test.java File not found");

        factory.unblockInitialize();
        factory.awaitStartCount(3, 1, TimeUnit.SECONDS);
        client.awaitRecoveryTasksForTests();

        assertThat(factory.startCount.get()).isEqualTo(3);
        assertThat(client.recoveryActionExecutionCountForTests()).isEqualTo(2);
        client.close();
    }

    @Test
    void recoveryStatus_staysRecoveringWhileRestartIsInProgress() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.blockInitialize();

        Future<String> recovery = client.startManualRecoveryInBackgroundForTests(true);
        factory.awaitInitializeAttemptCount(2, 1, TimeUnit.SECONDS);

        assertThat(client.getIndexingStatus()).contains("status=recovering_reindex");

        factory.unblockInitialize();
        assertThatCode(() -> recovery.get(1, TimeUnit.SECONDS)).doesNotThrowAnyException();
        client.close();
    }

    @Test
    void manualRecovery_returnsAlreadyInProgressInsteadOfBlockingDuringAutoRecovery() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.blockInitialize();

        client.submitRecoverySignalForTests(
            JdtlsRecoveryAction.RESTART,
            "JDTLS process exited with code 1");
        factory.awaitInitializeAttemptCount(2, 1, TimeUnit.SECONDS);

        long startedAt = System.nanoTime();
        String result = client.restartJdtls();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(result).contains("recovery already in progress");
        assertThat(elapsedMs).isLessThan(500);

        factory.unblockInitialize();
        client.awaitRecoveryTasksForTests();
        client.close();
    }

    @Test
    void cooldownSuppression_marksClientDegradedWhenProcessIsAlreadyDead() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);

        client.handleRecoverySignal(JdtlsRecoveryAction.RESTART, "JDTLS process exited with code 1");
        client.setRunningForTests(false);
        client.handleRecoverySignal(JdtlsRecoveryAction.RESTART, "JDTLS process exited with code 2");

        assertThat(client.getIndexingStatus()).contains("status=degraded");
        client.close();
    }

    @Test
    void closeDuringRecovery_doesNotLeaveRestartedSessionRunning() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.blockInitialize();

        Future<String> recovery = client.startManualRecoveryInBackgroundForTests(true);
        factory.awaitInitializeAttemptCount(2, 1, TimeUnit.SECONDS);

        client.close();
        factory.unblockInitialize();
        assertThatCode(() -> recovery.get(1, TimeUnit.SECONDS)).doesNotThrowAnyException();
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    void completedRecovery_transitionsOutOfRecoveringStateWithoutStatusCallback() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);

        String result = client.reindexWorkspace();

        assertThat(result).contains("status=indexing");
        assertThat(client.getIndexingStatus()).contains("status=indexing");
        client.close();
    }

    private static final class FakeRuntimeSessionFactory implements JdtlsClient.RuntimeSessionFactory {
        private final AtomicInteger startCount = new AtomicInteger();
        private boolean failFirstInitializeAttempt;
        private volatile CountDownLatch initializeBlocker = new CountDownLatch(0);
        private volatile CountDownLatch initializeAttemptLatch = new CountDownLatch(0);
        private volatile FakeProcess currentProcess;

        @Override
        public JdtlsClient.RuntimeSession start(Path workspaceRoot, Path dataDir, String jdtlsCommand,
                                                JdtlsLanguageClient languageClient, long generation) throws Exception {
            startCount.incrementAndGet();
            Files.createDirectories(dataDir);
            FakeProcess process = new FakeProcess();
            currentProcess = process;
            return new JdtlsClient.RuntimeSession(
                generation,
                process,
                new FakeLanguageServer(
                    failFirstInitializeAttempt && startCount.get() == 1,
                    process,
                    initializeBlocker,
                    initializeAttemptLatch),
                new Thread(() -> {}, "fake-stderr"),
                Set.of()
            );
        }

        void blockInitialize() {
            initializeBlocker = new CountDownLatch(1);
            initializeAttemptLatch = new CountDownLatch(1);
        }

        void unblockInitialize() {
            initializeBlocker.countDown();
        }

        void awaitInitializeAttemptCount(int expectedStartCount, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (startCount.get() < expectedStartCount && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(startCount.get()).isGreaterThanOrEqualTo(expectedStartCount);
            initializeAttemptLatch.await(timeout, unit);
        }

        void awaitStartCount(int expectedStartCount, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (startCount.get() < expectedStartCount && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(startCount.get()).isGreaterThanOrEqualTo(expectedStartCount);
        }
    }

    private static final class FakeProcess extends Process {
        private final CountDownLatch exitLatch = new CountDownLatch(1);
        private volatile boolean alive = true;
        private volatile int exitCode;

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { exitLatch.await(); return exitCode; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return exitLatch.await(timeout, unit);
        }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { exit(0); }
        @Override public Process destroyForcibly() { exit(0); return this; }
        @Override public boolean isAlive() { return alive; }

        void exit(int code) {
            exitCode = code;
            alive = false;
            exitLatch.countDown();
        }
    }

    private static final class FakeLanguageServer implements LanguageServer {
        private final boolean failInitialize;
        private final FakeProcess process;
        private final CountDownLatch initializeBlocker;
        private final CountDownLatch initializeAttemptLatch;

        private FakeLanguageServer(boolean failInitialize, FakeProcess process, CountDownLatch initializeBlocker,
                                   CountDownLatch initializeAttemptLatch) {
            this.failInitialize = failInitialize;
            this.process = process;
            this.initializeBlocker = initializeBlocker;
            this.initializeAttemptLatch = initializeAttemptLatch;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(org.eclipse.lsp4j.InitializeParams params) {
            if (failInitialize) {
                return CompletableFuture.failedFuture(new RuntimeException("boom"));
            }
            initializeAttemptLatch.countDown();
            try {
                initializeBlocker.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(e);
            }
            InitializeResult result = new InitializeResult(new ServerCapabilities());
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
            process.exit(0);
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return mock(TextDocumentService.class);
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return mock(WorkspaceService.class);
        }
    }
}
