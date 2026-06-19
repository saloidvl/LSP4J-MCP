package com.saloidvl.lsp4jmcp.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void executeRestart_isNotPublicApi() {
        assertThatThrownBy(() -> JdtlsClient.class.getMethod(
            "executeRestart",
            boolean.class,
            String.class
        )).isInstanceOf(NoSuchMethodException.class);
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
        String workspaceHash = JdtlsClient.computeWorkspaceHash(workspace.toString());
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
        String workspaceHash = JdtlsClient.computeWorkspaceHash(workspace.toString());
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
        String workspaceHash = JdtlsClient.computeWorkspaceHash(workspace.toString());
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
        String hash1 = JdtlsClient.computeWorkspaceHash(workspace1.toString());
        String hash2 = JdtlsClient.computeWorkspaceHash(workspace2.toString());
        
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
        String workspaceHash = JdtlsClient.computeWorkspaceHash(workspace.toString());
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
        Path dataDir = access(client).dataDirForTests();

        String result = client.restartJdtls();

        assertThat(result).contains("status=");
        assertThat(factory.startCount.get()).isEqualTo(2);
        assertThat(dataDir).exists();
        client.close();
    }

    @Test
    void reindexWorkspace_callsCleanFullBuildWithoutRestartingProcess() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);

        String result = client.reindexWorkspace();

        assertThat(factory.currentLanguageServer.lastBuildWorkspaceArg).isEqualTo(Either.forLeft(true));
        assertThat(factory.startCount.get()).isEqualTo(1); // no process restart
        assertThat(result).contains("status=");
        client.close();
    }

    @Test
    void reindexWorkspace_doesNotDeleteDataDir() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        Path dataDir = access(client).dataDirForTests();
        Path sentinel = dataDir.resolve("marker.txt");
        Files.writeString(sentinel, "stale");

        client.reindexWorkspace();

        assertThat(sentinel).exists();
        client.close();
    }

    @Test
    void reindexWorkspace_throwsIOExceptionWhenBuildReturnsFailed() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.currentLanguageServer.setBuildWorkspaceResult(BuildWorkspaceStatus.FAILED);

        assertThatThrownBy(() -> client.reindexWorkspace())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("FAILED");
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
        access(client).forceStateForTests(JdtlsClientState.INDEXING, "Importing workspace", "stale path detected");
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
        access(client).setInitializedForTests(false);
        int startCountBefore = factory.startCount.get();

        assertThatThrownBy(() -> client.findWorkspaceSymbols("Foo"))
            .isInstanceOf(IllegalStateException.class);

        access(client).awaitRecoveryTasksForTests();
        assertThat(factory.startCount.get()).isGreaterThan(startCountBefore);
        client.close();
    }

    @Test
    void repeatedQueuedSignals_doNotCauseBackToBackRecoveries() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.blockInitialize();

        access(client).submitRecoverySignalForTests(
            JdtlsRecoveryAction.REINDEX,
            "Core Exception [code 368] File not found: /repo/Test.java");
        factory.awaitInitializeAttemptCount(2, 1, TimeUnit.SECONDS);

        access(client).submitRecoverySignalForTests(
            JdtlsRecoveryAction.REINDEX,
            "Core Exception [code 368] File not found: /repo/Other.java");
        access(client).submitRecoverySignalForTests(
            JdtlsRecoveryAction.REINDEX,
            "Core Exception [code 368] File not found: /repo/Third.java");

        factory.unblockInitialize();
        access(client).awaitRecoveryTasksForTests();

        assertThat(factory.startCount.get()).isEqualTo(2);
        assertThat(access(client).recoveryActionExecutionCountForTests()).isEqualTo(1);
        client.close();
    }

    @Test
    void strongerQueuedSignal_escalatesRestartIntoFollowUpReindex() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.blockInitialize();

        access(client).submitRecoverySignalForTests(
            JdtlsRecoveryAction.RESTART,
            "JDTLS process exited with code 1");
        factory.awaitInitializeAttemptCount(2, 1, TimeUnit.SECONDS);

        access(client).submitRecoverySignalForTests(
            JdtlsRecoveryAction.REINDEX,
            "Core Exception [code 368] Failed to publish diagnostics for file:///repo/Test.java File not found");

        factory.unblockInitialize();
        factory.awaitStartCount(3, 1, TimeUnit.SECONDS);
        access(client).awaitRecoveryTasksForTests();

        assertThat(factory.startCount.get()).isEqualTo(3);
        assertThat(access(client).recoveryActionExecutionCountForTests()).isEqualTo(2);
        client.close();
    }

    @Test
    void recoveryStatus_staysRecoveringWhileReindexIsInProgress() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.currentLanguageServer.blockBuildWorkspace();

        Future<String> recovery = access(client).startManualRecoveryInBackgroundForTests(true);
        // give the background thread time to enter reindexWorkspace and call buildWorkspace
        Thread.sleep(100);

        assertThat(client.getIndexingStatus()).contains("status=recovering_reindex");

        factory.currentLanguageServer.unblockBuildWorkspace();
        assertThatCode(() -> recovery.get(2, TimeUnit.SECONDS)).doesNotThrowAnyException();
        client.close();
    }

    @Test
    void manualRecovery_returnsAlreadyInProgressInsteadOfBlockingDuringAutoRecovery() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.blockInitialize();

        access(client).submitRecoverySignalForTests(
            JdtlsRecoveryAction.RESTART,
            "JDTLS process exited with code 1");
        factory.awaitInitializeAttemptCount(2, 1, TimeUnit.SECONDS);

        long startedAt = System.nanoTime();
        String result = client.restartJdtls();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(result).contains("recovery already in progress");
        assertThat(elapsedMs).isLessThan(500);

        factory.unblockInitialize();
        access(client).awaitRecoveryTasksForTests();
        client.close();
    }

    @Test
    void cooldownSuppression_marksClientDegradedWhenProcessIsAlreadyDead() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);

        access(client).handleRecoverySignalForTests(JdtlsRecoveryAction.RESTART, "JDTLS process exited with code 1");
        access(client).setRunningForTests(false);
        access(client).handleRecoverySignalForTests(JdtlsRecoveryAction.RESTART, "JDTLS process exited with code 2");

        assertThat(client.getIndexingStatus()).contains("status=degraded");
        client.close();
    }

    @Test
    void closeDuringReindex_recoveryCompletesAndProcessIsStopped() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.currentLanguageServer.blockBuildWorkspace();

        Future<String> recovery = access(client).startManualRecoveryInBackgroundForTests(true);
        Thread.sleep(100);

        client.close();
        factory.currentLanguageServer.unblockBuildWorkspace();
        assertThatCode(() -> recovery.get(2, TimeUnit.SECONDS)).doesNotThrowAnyException();
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    void completedRecovery_transitionsOutOfRecoveringStateWithoutStatusCallback() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);

        String result = client.reindexWorkspace();

        assertThat(result).contains("status=ready");
        assertThat(client.getIndexingStatus()).contains("status=ready");
        client.close();
    }

    @Test
    void findIncomingCalls_prepareReturnsEmpty_returnsNull() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.currentLanguageServer.setPrepareCallHierarchyResult(List.of());
        Path javaFile = tempDir.resolve("Foo.java");
        Files.writeString(javaFile, "class Foo {}\n");

        List<CallHierarchyIncomingCall> result = client.findIncomingCalls(javaFile.toUri().toString(), 0, 0);

        assertThat(result).isNull();
        client.close();
    }

    @Test
    void findOutgoingCalls_prepareReturnsEmpty_returnsNull() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.currentLanguageServer.setPrepareCallHierarchyResult(List.of());
        Path javaFile = tempDir.resolve("Foo.java");
        Files.writeString(javaFile, "class Foo {}\n");

        List<CallHierarchyOutgoingCall> result = client.findOutgoingCalls(javaFile.toUri().toString(), 0, 0);

        assertThat(result).isNull();
        client.close();
    }

    @Test
    void findImplementations_resultIsNull_returnsEmptyList() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.currentLanguageServer.setImplementationResult(null);
        Path javaFile = tempDir.resolve("Foo.java");
        Files.writeString(javaFile, "class Foo {}\n");

        List<? extends Location> result = client.findImplementations(javaFile.toUri().toString(), 0, 0);

        assertThat(result).isEmpty();
        client.close();
    }

    @Test
    void onLanguageClientStatusChanged_withNoRecoveryInFlight_transitionsToIndexingNotNull() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        access(client).forceRecoveryStateForTests(false, null);

        client.getLanguageClient().resetForNewSession();

        assertThat(client.getIndexingStatus())
            .contains("status=indexing")
            .doesNotContain("status=null");
        client.close();
    }

    @Test
    void concurrentSignalsDuringRecoveryStart_doNotExceedOneActiveRecoveryPlusOnePending() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        CountDownLatch firstTaskEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        AtomicInteger taskStarts = new AtomicInteger();
        access(client).setRecoveryTaskStartHookForTests(() -> {
            if (taskStarts.getAndIncrement() == 0) {
                firstTaskEntered.countDown();
                try {
                    releaseFirstTask.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        access(client).submitRecoverySignalForTests(
            JdtlsRecoveryAction.RESTART,
            "JDTLS process exited with code 0");
        assertThat(firstTaskEntered.await(2, TimeUnit.SECONDS)).isTrue();

        for (int i = 1; i < 5; i++) {
            access(client).submitRecoverySignalForTests(
                JdtlsRecoveryAction.RESTART,
                "JDTLS process exited with code " + i);
        }
        releaseFirstTask.countDown();
        access(client).awaitRecoveryTasksForTests();
        access(client).awaitRecoveryTasksForTests();

        assertThat(taskStarts.get()).isEqualTo(2);
        assertThat(access(client).recoveryActionExecutionCountForTests()).isEqualTo(2);
        client.close();
    }

    @Test
    void ensureAvailableForRequests_startingState_throwsWithStartingMessage() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        access(client).forceStateForTests(JdtlsClientState.STARTING, "Starting", "");
        access(client).setInitializedForTests(false);

        assertThatThrownBy(() -> client.findWorkspaceSymbols("Foo"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("still initializing");
        client.close();
    }

    @Test
    void startingStateError_doesNotTriggerRecovery() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        int startCountBefore = factory.startCount.get();
        access(client).forceStateForTests(JdtlsClientState.STARTING, "Starting", "");
        access(client).setInitializedForTests(false);

        assertThatThrownBy(() -> client.findWorkspaceSymbols("Foo"))
            .isInstanceOf(IllegalStateException.class);
        access(client).awaitRecoveryTasksForTests();

        assertThat(factory.startCount.get()).isEqualTo(startCountBefore);
        client.close();
    }

    @Test
    void startingState_tenConcurrentToolCalls_allGetStartingError_noThunderingRecovery() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        int startCountBefore = factory.startCount.get();
        access(client).forceStateForTests(JdtlsClientState.STARTING, "Starting", "");
        access(client).setInitializedForTests(false);
        List<Thread> threads = new ArrayList<>();
        List<Exception> caught = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 10; i++) {
            Thread thread = new Thread(() -> {
                try {
                    client.findWorkspaceSymbols("Foo");
                } catch (Exception e) {
                    caught.add(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(2000);
            assertThat(thread.isAlive()).isFalse();
        }
        access(client).awaitRecoveryTasksForTests();

        assertThat(caught).hasSize(10);
        assertThat(caught).allMatch(e ->
            e instanceof IllegalStateException && e.getMessage().contains("still initializing"));
        assertThat(factory.startCount.get()).isEqualTo(startCountBefore);
        client.close();
    }

    @Test
    void createAndInitializeAsync_returnsImmediatelyInStartingState() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        factory.blockInitialize();

        JdtlsClient client = JdtlsClient.createAndInitializeAsync(tempDir, "/fake/jdtls", factory);
        try {
            Thread asyncThread = access(client).asyncInitThreadForTests();
            assertThat(asyncThread.getName()).isEqualTo("jdtls-async-init");
            assertThat(asyncThread.isDaemon()).isTrue();
            assertThat(client.getIndexingStatus()).contains("status=starting");
            assertThatThrownBy(() -> client.findWorkspaceSymbols("Foo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still initializing");
        } finally {
            factory.unblockInitialize();
            client.close();
        }
    }

    @Test
    void createAndInitializeAsync_initializationFailure_transitionsToFailed() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        factory.failAllInitializeAttempts = true;

        JdtlsClient client = JdtlsClient.createAndInitializeAsync(tempDir, "/fake/jdtls", factory);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!client.getIndexingStatus().contains("status=failed")
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(client.getIndexingStatus())
                .contains("status=failed")
                .contains("message=Async initialization failed");
        } finally {
            client.close();
        }
    }

    @Test
    void createAndInitializeAsync_retrySucceeds_recoversFromFirstFailure() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        factory.failFirstInitializeAttempt = true;

        JdtlsClient client = JdtlsClient.createAndInitializeAsync(tempDir, "/fake/jdtls", factory);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (client.getIndexingStatus().contains("status=starting")
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(factory.startCount.get()).isGreaterThanOrEqualTo(2);
            assertThat(client.getIndexingStatus()).doesNotContain("status=failed");
        } finally {
            client.close();
        }
    }

    @Test
    void closeDuringAsyncInitialization_returnsPromptlyAndPreservesClosedState() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        factory.blockInitialize();
        JdtlsClient client = JdtlsClient.createAndInitializeAsync(tempDir, "/fake/jdtls", factory);
        factory.awaitInitializeAttemptCount(1, 2, TimeUnit.SECONDS);

        CountDownLatch closeDone = new CountDownLatch(1);
        new Thread(() -> {
            client.close();
            closeDone.countDown();
        }, "closer").start();

        assertThat(closeDone.await(2, TimeUnit.SECONDS)).isTrue();
        factory.unblockInitialize();
        access(client).asyncInitThreadForTests().join(2000);

        assertThat(client.getIndexingStatus())
            .contains("status=failed")
            .contains("message=Closed");
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    void languageStatusCallbackAfterClose_doesNotOverwriteClosedState() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        client.close();

        client.getLanguageClient().resetForNewSession();

        assertThat(client.getIndexingStatus())
            .contains("status=failed")
            .contains("message=Closed");
    }

    @Test
    void buildWorkspace_callsJsonRequestWithFullBuildTrue() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);

        client.buildWorkspace();

        assertThat(factory.currentLanguageServer.lastBuildWorkspaceArg).isEqualTo(Either.forLeft(true));
        client.close();
    }

    @Test
    void buildWorkspace_throwsIOExceptionOnFailed() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);
        factory.currentLanguageServer.setBuildWorkspaceResult(BuildWorkspaceStatus.FAILED);

        assertThatThrownBy(() -> client.buildWorkspace())
            .isInstanceOf(IOException.class)
            .hasMessageContaining("FAILED");
        client.close();
    }

    @Test
    void initialize_declaresTypeHierarchyAndTypeDefinitionCapabilities() throws Exception {
        FakeRuntimeSessionFactory factory = new FakeRuntimeSessionFactory();
        JdtlsClient client = JdtlsClient.createAndInitialize(tempDir, "/fake/jdtls", factory);

        assertThat(factory.currentLanguageServer.lastInitializeParams).isNotNull();
        assertThat(factory.currentLanguageServer.lastInitializeParams.getCapabilities()
            .getTextDocument().getTypeHierarchy()).isNotNull();
        assertThat(factory.currentLanguageServer.lastInitializeParams.getCapabilities()
            .getTextDocument().getTypeHierarchy().getDynamicRegistration()).isTrue();
        assertThat(factory.currentLanguageServer.lastInitializeParams.getCapabilities()
            .getTextDocument().getTypeDefinition()).isNotNull();
        assertThat(factory.currentLanguageServer.lastInitializeParams.getCapabilities()
            .getTextDocument().getTypeDefinition().getDynamicRegistration()).isTrue();

        client.close();
    }

    private JdtlsClientTestAccess access(JdtlsClient client) {
        return new JdtlsClientTestAccess(client);
    }

    private static final class FakeRuntimeSessionFactory implements JdtlsSessionManager.RuntimeSessionFactory {
        private final AtomicInteger startCount = new AtomicInteger();
        private boolean failFirstInitializeAttempt;
        private boolean failAllInitializeAttempts;
        private volatile CountDownLatch initializeBlocker = new CountDownLatch(0);
        private volatile CountDownLatch initializeAttemptLatch = new CountDownLatch(0);
        private volatile FakeProcess currentProcess;
        private volatile FakeLanguageServer currentLanguageServer;
        ServerCapabilities serverCapabilities = new ServerCapabilities();

        @Override
        public JdtlsSessionManager.RuntimeSession start(Path workspaceRoot, Path dataDir, String jdtlsCommand,
                                                        JdtlsLanguageClient languageClient,
                                                        long generation) throws Exception {
            startCount.incrementAndGet();
            Files.createDirectories(dataDir);
            FakeProcess process = new FakeProcess();
            currentProcess = process;
            FakeLanguageServer server = new FakeLanguageServer(
                failAllInitializeAttempts || (failFirstInitializeAttempt && startCount.get() == 1),
                process,
                initializeBlocker,
                initializeAttemptLatch,
                serverCapabilities);
            currentLanguageServer = server;
            return new JdtlsSessionManager.RuntimeSession(
                generation,
                process,
                server,
                mock(RemoteEndpoint.class),
                new Thread(() -> {}, "fake-stderr"),
                java.util.concurrent.ConcurrentHashMap.newKeySet()
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

    private static final class FakeLanguageServer implements JdtlsLanguageServer {
        private final boolean failInitialize;
        private final FakeProcess process;
        private final CountDownLatch initializeBlocker;
        private final CountDownLatch initializeAttemptLatch;
        private final ServerCapabilities serverCapabilities;
        private final TextDocumentService textDocumentService = mock(TextDocumentService.class);
        private volatile BuildWorkspaceStatus buildWorkspaceResult = BuildWorkspaceStatus.SUCCEED;
        private volatile org.eclipse.lsp4j.InitializeParams lastInitializeParams;
        volatile Either<Boolean, boolean[]> lastBuildWorkspaceArg;
        private volatile CountDownLatch buildWorkspaceBlocker = new CountDownLatch(0);

        private FakeLanguageServer(boolean failInitialize, FakeProcess process, CountDownLatch initializeBlocker,
                                   CountDownLatch initializeAttemptLatch, ServerCapabilities serverCapabilities) {
            this.failInitialize = failInitialize;
            this.process = process;
            this.initializeBlocker = initializeBlocker;
            this.initializeAttemptLatch = initializeAttemptLatch;
            this.serverCapabilities = serverCapabilities;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(org.eclipse.lsp4j.InitializeParams params) {
            lastInitializeParams = params;
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
            InitializeResult result = new InitializeResult(serverCapabilities);
            return CompletableFuture.completedFuture(result);
        }

        void setPrepareCallHierarchyResult(List<CallHierarchyItem> items) {
            when(textDocumentService.prepareCallHierarchy(any()))
                .thenReturn(CompletableFuture.completedFuture(items));
        }

        void setImplementationResult(Either<List<? extends Location>, List<? extends LocationLink>> result) {
            when(textDocumentService.implementation(any()))
                .thenReturn(CompletableFuture.completedFuture(result));
        }

        void setBuildWorkspaceResult(BuildWorkspaceStatus result) {
            this.buildWorkspaceResult = result;
        }

        void blockBuildWorkspace() {
            buildWorkspaceBlocker = new CountDownLatch(1);
        }

        void unblockBuildWorkspace() {
            buildWorkspaceBlocker.countDown();
        }

        @Override
        public CompletableFuture<BuildWorkspaceStatus> buildWorkspace(Either<Boolean, boolean[]> forceReBuild) {
            lastBuildWorkspaceArg = forceReBuild;
            return CompletableFuture.supplyAsync(() -> {
                try {
                    buildWorkspaceBlocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return buildWorkspaceResult;
            });
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
            return textDocumentService;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return mock(WorkspaceService.class);
        }
    }
}
