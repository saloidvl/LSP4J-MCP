package com.saloidvl.lsp4jmcp.supervisor;

import com.google.gson.Gson;
import com.saloidvl.lsp4jmcp.config.JdtlsSettingsInputs;
import com.saloidvl.lsp4jmcp.config.JdtlsSettingsSource;
import com.saloidvl.lsp4jmcp.control.SupervisorCommand;
import com.saloidvl.lsp4jmcp.control.SupervisorRequest;
import com.saloidvl.lsp4jmcp.control.SupervisorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorMainIntegrationTest {

    private static final Gson GSON = new Gson();

    @Test
    void supervisorRequest_roundTripsSettingsInputs() {
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "config/jdtls.json", "/jdk-21", "/gradle-jdk", "false", "true", "false");
        SupervisorRequest request = new SupervisorRequest(
                SupervisorCommand.OPEN_LEASE, "repo", "/repo", "jdtls", inputs);

        SupervisorRequest decoded = GSON.fromJson(GSON.toJson(request), SupervisorRequest.class);

        assertThat(decoded.settingsInputs()).isEqualTo(inputs);
    }

    @Test
    void openLease_missingSettingsInputsRejectsIncompatibleProtocolWithoutLaunchingWorker(
            @TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        AtomicInteger launches = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(1);
        SupervisorMain supervisor = new SupervisorMain(
                socketPath,
                new WorkerRegistry(),
                (workspacePath, jdtlsCommand, settingsInputs) -> {
                    launches.incrementAndGet();
                    return new WorkerProcessLauncher.StartedWorker(
                            null, 321L, "127.0.0.1", 45123, "test-fingerprint");
                },
                ready::countDown);
        Thread serverThread = start(supervisor);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        SupervisorResponse response = sendRequest(socketPath, new SupervisorRequest(
                SupervisorCommand.OPEN_LEASE,
                "repo",
                tempDir.resolve("repo").toString(),
                "jdtls",
                null));

        assertThat(response.ok()).isFalse();
        assertThat(response.message()).contains("incompatible control protocol");
        assertThat(launches).hasValue(0);

        supervisor.close();
        serverThread.join(2000);
    }

    @Test
    void openLease_sameRepoReusesSingleStartedWorker(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        AtomicInteger launches = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(1);

        SupervisorMain supervisor = new SupervisorMain(
            socketPath,
            new WorkerRegistry(),
            (workspacePath, jdtlsCommand, settingsInputs) -> {
                launches.incrementAndGet();
                return new WorkerProcessLauncher.StartedWorker(
                        new TrackingProcess(), 321L, "127.0.0.1", 45123,
                        settingsFingerprint(workspacePath, settingsInputs));
            },
            ready::countDown
        );

        Thread serverThread = new Thread(() -> {
            try {
                supervisor.run();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        // Open two connections, each gets same worker port
        Path workspace = Files.createDirectories(tempDir.resolve("repo-a"));
        SupervisorRequest request = request(workspace, JdtlsSettingsInputs.empty());
        try (SocketChannel ch1 = openLeaseChannel(socketPath, request)) {
            SupervisorResponse first = readResponse(ch1);
            assertThat(first.ok()).isTrue();
            assertThat(first.port()).isEqualTo(45123);

            try (SocketChannel ch2 = openLeaseChannel(socketPath, request)) {
                SupervisorResponse second = readResponse(ch2);
                assertThat(second.ok()).isTrue();
                assertThat(second.port()).isEqualTo(45123);
                assertThat(launches).hasValue(1);
            }
        }

        supervisor.close();
        serverThread.join(2000);
    }

    @Test
    void openLease_matchingExitedWorkerIsReplacedBeforeLeaseIsReturned(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        LaggingExitProcess firstProcess = new LaggingExitProcess();
        WorkerProcessLauncher launcher = (path, command, inputs) -> {
            int number = starts.incrementAndGet();
            Process process = number == 1 ? firstProcess : new TrackingProcess();
            return new WorkerProcessLauncher.StartedWorker(
                    process, 300L + number, "127.0.0.1", 45_000 + number,
                    settingsFingerprint(path, inputs));
        };
        RunningSupervisor running = startSupervisor(tempDir, launcher);
        SupervisorResponse first = sendAndCloseLease(
                running.socketPath(), request(workspace, JdtlsSettingsInputs.empty()));
        awaitLeaseCount(running.registry(), "repo", 0);
        firstProcess.markExitedWithoutNotification();

        SupervisorResponse replacement = sendAndCloseLease(
                running.socketPath(), request(workspace, JdtlsSettingsInputs.empty()));

        assertThat(replacement.ok()).isTrue();
        assertThat(replacement.workerPid()).isNotEqualTo(first.workerPid());
        assertThat(starts).hasValue(2);
        running.close();
    }

    @Test
    void openLease_connectionCloseTriggersLeaseRelease(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        CountDownLatch ready = new CountDownLatch(1);
        WorkerRegistry registry = new WorkerRegistry();

        SupervisorMain supervisor = new SupervisorMain(
            socketPath,
            registry,
            (workspacePath, jdtlsCommand, settingsInputs) ->
                new WorkerProcessLauncher.StartedWorker(
                        new TrackingProcess(), 321L, "127.0.0.1", 45123,
                        settingsFingerprint(workspacePath, settingsInputs)),
            ready::countDown
        );

        Thread serverThread = new Thread(() -> {
            try {
                supervisor.run();
            } catch (Exception ignored) {
            }
        });
        serverThread.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        Path workspace = Files.createDirectories(tempDir.resolve("repo-b"));
        try (SocketChannel ch = openLeaseChannel(
                socketPath, request(workspace, JdtlsSettingsInputs.empty()))) {
            SupervisorResponse resp = readResponse(ch);
            assertThat(resp.ok()).isTrue();
            assertThat(registry.get("repo-b").leaseCount()).isEqualTo(1);
        }
        // After close, give virtual thread a moment to process EOF
        Thread.sleep(100);
        assertThat(registry.get("repo-b").leaseCount()).isZero();

        supervisor.close();
        serverThread.join(2000);
    }

    @Test
    void ping_returnsOk(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        CountDownLatch ready = new CountDownLatch(1);

        SupervisorMain supervisor = new SupervisorMain(
            socketPath,
            new WorkerRegistry(),
            (workspacePath, jdtlsCommand, settingsInputs) ->
                new WorkerProcessLauncher.StartedWorker(
                        new TrackingProcess(), 1L, "127.0.0.1", 1,
                        settingsFingerprint(workspacePath, settingsInputs)),
            ready::countDown
        );

        Thread serverThread = new Thread(() -> {
            try { supervisor.run(); } catch (Exception ignored) {}
        });
        serverThread.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        SupervisorResponse response = sendRequest(socketPath,
            new SupervisorRequest(
                    SupervisorCommand.PING, null, null, null, JdtlsSettingsInputs.empty()));
        assertThat(response.ok()).isTrue();
        assertThat(response.message()).isEqualTo("pong");

        supervisor.close();
        serverThread.join(2000);
    }

    @Test
    void supervisorClose_interruptsActiveLeaseConnections(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        CountDownLatch ready = new CountDownLatch(1);
        WorkerRegistry registry = new WorkerRegistry();

        SupervisorMain supervisor = new SupervisorMain(
            socketPath,
            registry,
            (workspacePath, jdtlsCommand, settingsInputs) ->
                new WorkerProcessLauncher.StartedWorker(
                        new TrackingProcess(), 321L, "127.0.0.1", 45123,
                        settingsFingerprint(workspacePath, settingsInputs)),
            ready::countDown
        );

        Thread serverThread = new Thread(() -> {
            try { supervisor.run(); } catch (Exception ignored) {}
        });
        serverThread.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

        // Open a lease but don't close it
        Path workspace = Files.createDirectories(tempDir.resolve("repo-c"));
        SocketChannel ch = openLeaseChannel(
                socketPath, request(workspace, JdtlsSettingsInputs.empty()));
        SupervisorResponse resp = readResponse(ch);
        assertThat(resp.ok()).isTrue();
        assertThat(registry.get("repo-c").leaseCount()).isEqualTo(1);

        // Close supervisor — should interrupt active channel, release lease
        supervisor.close();
        serverThread.join(2000);

        Thread.sleep(100);
        // Lease should have been released via finally block
        assertThat(registry.get("repo-c")).satisfiesAnyOf(
            r -> assertThat(r).isNull(),
            r -> assertThat(r.leaseCount()).isZero()
        );
        ch.close();
    }

    @Test
    void openLease_changedSettingsWithNoLeasesReplacesWorkerImmediately(
            @TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        List<TrackingProcess> processes = new CopyOnWriteArrayList<>();
        WorkerProcessLauncher launcher = (path, command, inputs) -> {
            TrackingProcess process = new TrackingProcess();
            processes.add(process);
            int number = starts.incrementAndGet();
            return new WorkerProcessLauncher.StartedWorker(
                    process, 300L + number, "127.0.0.1", 45_000 + number,
                    settingsFingerprint(path, inputs));
        };
        RunningSupervisor running = startSupervisor(tempDir, launcher);

        SupervisorResponse first = sendAndCloseLease(running.socketPath(), request(
                workspace, new JdtlsSettingsInputs(null, null, null, "true", null, null)));
        awaitLeaseCount(running.registry(), workspace.getFileName().toString(), 0);
        SupervisorResponse second = sendAndCloseLease(running.socketPath(), request(
                workspace, new JdtlsSettingsInputs(null, null, null, "false", null, null)));

        assertThat(first.ok()).isTrue();
        assertThat(second.ok()).isTrue();
        assertThat(second.workerPid()).isNotEqualTo(first.workerPid());
        assertThat(starts).hasValue(2);
        assertThat(processes.getFirst().isAlive()).isFalse();
        running.close();
    }

    @Test
    void openLease_changedSettingsWithActiveLeaseReturnsActionableError(
            @TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        List<TrackingProcess> processes = new CopyOnWriteArrayList<>();
        WorkerProcessLauncher launcher = fakeLauncher(starts, processes);
        RunningSupervisor running = startSupervisor(tempDir, launcher);
        SocketChannel active = openLeaseChannel(running.socketPath(), request(
                workspace, new JdtlsSettingsInputs(null, null, null, "true", null, null)));
        SupervisorResponse first = readResponse(active);

        SupervisorResponse rejected = sendRequest(running.socketPath(), request(
                workspace, new JdtlsSettingsInputs(null, null, null, "false", null, null)));

        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.message())
                .contains("JDTLS settings changed for repository '" + workspace.toAbsolutePath() + "'")
                .contains("pid " + first.workerPid())
                .contains("1 active MCP session(s)")
                .contains("Close all MCP sessions connected to this repository, then reconnect")
                .doesNotContain("GRADLE_APT");
        assertThat(starts).hasValue(1);
        assertThat(running.registry().get("repo").leaseCount()).isEqualTo(1);
        assertThat(processes.getFirst().isAlive()).isTrue();
        active.close();
        running.close();
    }

    @Test
    void openLease_concurrentDifferentSettingsStartsOneWorkerAndRejectsOneLease(
            @TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        RunningSupervisor running = startSupervisor(
                tempDir, fakeLauncher(starts, new CopyOnWriteArrayList<>()));
        CountDownLatch go = new CountDownLatch(1);
        SupervisorRequest aptOn = request(workspace,
                new JdtlsSettingsInputs(null, null, null, "true", null, null));
        SupervisorRequest aptOff = request(workspace,
                new JdtlsSettingsInputs(null, null, null, "false", null, null));

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<LeaseAttempt> first = pool.submit(
                    () -> openAfter(go, running.socketPath(), aptOn));
            Future<LeaseAttempt> second = pool.submit(
                    () -> openAfter(go, running.socketPath(), aptOff));
            go.countDown();
            List<LeaseAttempt> attempts = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertThat(attempts).filteredOn(attempt -> attempt.response().ok()).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> !attempt.response().ok()).singleElement()
                    .satisfies(attempt -> assertThat(attempt.response().message())
                            .contains("JDTLS settings changed"));
            assertThat(starts).hasValue(1);
            for (LeaseAttempt attempt : attempts) {
                attempt.close();
            }
        }
        running.close();
    }

    @Test
    void openLease_changedSettingsFileBytesReplaceIdleWorker(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path settings = workspace.resolve("settings.json");
        Files.writeString(settings, "{\"java\":{\"x\":1}}");
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "settings.json", null, null, null, null, null);
        AtomicInteger starts = new AtomicInteger();
        List<TrackingProcess> processes = new CopyOnWriteArrayList<>();
        RunningSupervisor running = startSupervisor(tempDir, fakeLauncher(starts, processes));

        SupervisorResponse first = sendAndCloseLease(
                running.socketPath(), request(workspace, inputs));
        awaitLeaseCount(running.registry(), "repo", 0);
        Files.writeString(settings, "{\"java\":{\"x\":2}}");
        SupervisorResponse second = sendAndCloseLease(
                running.socketPath(), request(workspace, inputs));

        assertThat(first.ok()).isTrue();
        assertThat(second.ok()).isTrue();
        assertThat(second.workerPid()).isNotEqualTo(first.workerPid());
        assertThat(starts).hasValue(2);
        assertThat(processes.getFirst().isAlive()).isFalse();
        running.close();
    }

    @Test
    void openLease_concurrentIdenticalSettingsStartsOneWorkerAndReturnsSamePid(
            @TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        RunningSupervisor running = startSupervisor(
                tempDir, fakeLauncher(starts, new CopyOnWriteArrayList<>()));
        CountDownLatch go = new CountDownLatch(1);
        SupervisorRequest request = request(workspace, JdtlsSettingsInputs.empty());

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<LeaseAttempt> first = pool.submit(
                    () -> openAfter(go, running.socketPath(), request));
            Future<LeaseAttempt> second = pool.submit(
                    () -> openAfter(go, running.socketPath(), request));
            go.countDown();
            LeaseAttempt firstAttempt = first.get(5, TimeUnit.SECONDS);
            LeaseAttempt secondAttempt = second.get(5, TimeUnit.SECONDS);

            assertThat(firstAttempt.response().ok()).isTrue();
            assertThat(secondAttempt.response().ok()).isTrue();
            assertThat(secondAttempt.response().workerPid())
                    .isEqualTo(firstAttempt.response().workerPid());
            assertThat(starts).hasValue(1);
            firstAttempt.close();
            secondAttempt.close();
        }
        running.close();
    }

    @Test
    void openLease_barrierReleasedDifferentSettingsProduceOneActiveLease(
            @TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        RunningSupervisor running = startSupervisor(
                tempDir, fakeLauncher(starts, new CopyOnWriteArrayList<>()));
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<LeaseAttempt> first = pool.submit(() -> openAfter(
                    barrier,
                    running.socketPath(),
                    request(workspace,
                            new JdtlsSettingsInputs(null, null, null, "true", null, null))));
            Future<LeaseAttempt> second = pool.submit(() -> openAfter(
                    barrier,
                    running.socketPath(),
                    request(workspace,
                            new JdtlsSettingsInputs(null, null, null, "false", null, null))));
            List<LeaseAttempt> attempts = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertThat(attempts).filteredOn(attempt -> attempt.response().ok()).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> !attempt.response().ok()).hasSize(1);
            assertThat(starts).hasValue(1);
            assertThat(running.registry().get("repo").leaseCount()).isEqualTo(1);
            for (LeaseAttempt attempt : attempts) {
                attempt.close();
            }
        }
        running.close();
    }

    @Test
    void openLease_firstPostStartFingerprintMismatchStopsAndRetriesOnce(
            @TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        List<TrackingProcess> processes = new CopyOnWriteArrayList<>();
        WorkerProcessLauncher launcher = (path, command, inputs) -> {
            int number = starts.incrementAndGet();
            TrackingProcess process = new TrackingProcess();
            processes.add(process);
            String expected = settingsFingerprint(path, inputs);
            return new WorkerProcessLauncher.StartedWorker(
                    process, 300L + number, "127.0.0.1", 45_000 + number,
                    number == 1 ? expected + "-stale" : expected);
        };
        RunningSupervisor running = startSupervisor(tempDir, launcher);

        SupervisorResponse response = sendAndCloseLease(
                running.socketPath(), request(workspace, JdtlsSettingsInputs.empty()));

        assertThat(response.ok()).isTrue();
        assertThat(starts).hasValue(2);
        assertThat(processes.getFirst().isAlive()).isFalse();
        assertThat(running.registry().get("repo").settingsFingerprint())
                .isEqualTo(settingsFingerprint(workspace, JdtlsSettingsInputs.empty()));
        running.close();
    }

    @Test
    void openLease_twoPostStartFingerprintMismatchesStopBothAndRegisterNeither(
            @TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        List<TrackingProcess> processes = new CopyOnWriteArrayList<>();
        WorkerProcessLauncher launcher = (path, command, inputs) -> {
            int number = starts.incrementAndGet();
            TrackingProcess process = new TrackingProcess();
            processes.add(process);
            return new WorkerProcessLauncher.StartedWorker(
                    process, 300L + number, "127.0.0.1", 45_000 + number,
                    settingsFingerprint(path, inputs) + "-stale-" + number);
        };
        RunningSupervisor running = startSupervisor(tempDir, launcher);

        SupervisorResponse response = sendRequest(
                running.socketPath(), request(workspace, JdtlsSettingsInputs.empty()));

        assertThat(response.ok()).isFalse();
        assertThat(response.message()).isEqualTo(
                "JDTLS settings source changed repeatedly during worker startup; "
                        + "wait for edits to finish and reconnect");
        assertThat(starts).hasValue(2);
        assertThat(processes).allSatisfy(process -> assertThat(process.isAlive()).isFalse());
        assertThat(running.registry().get("repo")).isNull();
        running.close();
    }

    @Test
    void openLease_unstoppablePostStartMismatchIsQuarantinedUntilExit(
            @TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        StubbornProcess stubborn = new StubbornProcess();
        WorkerProcessLauncher launcher = (path, command, inputs) -> {
            int number = starts.incrementAndGet();
            Process process = number == 1 ? stubborn : new TrackingProcess();
            String fingerprint = settingsFingerprint(path, inputs);
            return new WorkerProcessLauncher.StartedWorker(
                    process, 300L + number, "127.0.0.1", 45_000 + number,
                    number == 1 ? fingerprint + "-stale" : fingerprint);
        };
        RunningSupervisor running = startSupervisor(tempDir, launcher);

        SupervisorResponse blocked = sendRequest(
                running.socketPath(), request(workspace, JdtlsSettingsInputs.empty()));

        assertThat(blocked.ok()).isFalse();
        assertThat(blocked.message()).contains("could not be terminated after settings changed");
        assertThat(starts).hasValue(1);
        assertThat(running.registry().get("repo").state()).isEqualTo(WorkerState.STOPPING);

        stubborn.completeExit();
        awaitRecordRemoved(running.registry(), "repo");
        SupervisorResponse retried = sendAndCloseLease(
                running.socketPath(), request(workspace, JdtlsSettingsInputs.empty()));
        assertThat(retried.ok()).isTrue();
        assertThat(starts).hasValue(2);
        running.close();
    }

    @Test
    void openLease_pidOnlyExitedStartedWorkerIsRetriedBeforeLeaseIsReturned(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        long exitedPid = Long.MAX_VALUE;
        assertThat(ProcessHandle.of(exitedPid)).isEmpty();
        AtomicInteger starts = new AtomicInteger();
        TrackingProcess replacement = new TrackingProcess();
        WorkerProcessLauncher launcher = (path, command, inputs) -> {
            int attempt = starts.incrementAndGet();
            return attempt == 1
                    ? new WorkerProcessLauncher.StartedWorker(
                            null, exitedPid, "127.0.0.1", 45_001,
                            settingsFingerprint(path, inputs))
                    : new WorkerProcessLauncher.StartedWorker(
                            replacement, 302L, "127.0.0.1", 45_002,
                            settingsFingerprint(path, inputs));
        };
        RunningSupervisor running = startSupervisor(tempDir, launcher);

        SupervisorResponse response = sendAndCloseLease(
                running.socketPath(), request(workspace, JdtlsSettingsInputs.empty()));

        assertThat(response.ok()).isTrue();
        assertThat(response.workerPid()).isEqualTo(302L);
        assertThat(starts).hasValue(2);
        assertThat(running.registry().get("repo").process()).isSameAs(replacement);
        running.close();
    }

    @Test
    void openLease_unstoppableMismatchedWorkerIsQuarantinedUntilExit(
            @TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        StubbornProcess stubborn = new StubbornProcess();
        WorkerProcessLauncher launcher = (path, command, inputs) -> {
            int number = starts.incrementAndGet();
            Process process = number == 1 ? stubborn : new TrackingProcess();
            return new WorkerProcessLauncher.StartedWorker(
                    process, 300L + number, "127.0.0.1", 45_000 + number,
                    settingsFingerprint(path, inputs));
        };
        RunningSupervisor running = startSupervisor(tempDir, launcher);
        sendAndCloseLease(running.socketPath(), request(
                workspace, new JdtlsSettingsInputs(null, null, null, "true", null, null)));
        awaitLeaseCount(running.registry(), "repo", 0);

        SupervisorResponse blocked = sendRequest(running.socketPath(), request(
                workspace, new JdtlsSettingsInputs(null, null, null, "false", null, null)));

        assertThat(blocked.ok()).isFalse();
        assertThat(blocked.message()).contains("could not be terminated");
        assertThat(starts).hasValue(1);
        assertThat(running.registry().get("repo").state()).isEqualTo(WorkerState.STOPPING);

        stubborn.completeExit();
        awaitRecordRemoved(running.registry(), "repo");
        SupervisorResponse retried = sendAndCloseLease(running.socketPath(), request(
                workspace, new JdtlsSettingsInputs(null, null, null, "false", null, null)));
        assertThat(retried.ok()).isTrue();
        assertThat(starts).hasValue(2);
        running.close();
    }

    @Test
    void idleShutdown_waitDoesNotBlockLeaseForDifferentRepository(@TempDir Path tempDir)
            throws Exception {
        Path firstWorkspace = Files.createDirectories(tempDir.resolve("repo-a"));
        Path secondWorkspace = Files.createDirectories(tempDir.resolve("repo-b"));
        BlockingStopProcess blocking = new BlockingStopProcess();
        WorkerProcessLauncher launcher = (path, command, inputs) -> {
            Process process = path.equals(firstWorkspace.toAbsolutePath())
                    ? blocking
                    : new TrackingProcess();
            long pid = path.equals(firstWorkspace.toAbsolutePath()) ? 301L : 302L;
            return new WorkerProcessLauncher.StartedWorker(
                    process, pid, "127.0.0.1", (int) (45_000 + pid),
                    settingsFingerprint(path, inputs));
        };
        RunningSupervisor running = startSupervisor(tempDir, launcher);
        sendAndCloseLease(
                running.socketPath(), request(firstWorkspace, JdtlsSettingsInputs.empty()));
        awaitLeaseCount(running.registry(), "repo-a", 0);

        WorkerRecord firstRecord = running.registry().get("repo-a");
        Thread stopping = new Thread(
                () -> running.supervisor().stopIdleWorker("repo-a", firstRecord));
        stopping.start();
        assertThat(blocking.stopStarted.await(2, TimeUnit.SECONDS)).isTrue();

        SupervisorResponse other = sendAndCloseLease(
                running.socketPath(), request(secondWorkspace, JdtlsSettingsInputs.empty()));
        assertThat(other.ok()).isTrue();

        blocking.allowExit();
        stopping.join(2_000);
        assertThat(stopping.isAlive()).isFalse();
        running.close();
    }

    @Test
    void staleIdleShutdownCallbackDoesNotStopReplacement(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        AtomicInteger starts = new AtomicInteger();
        List<TrackingProcess> processes = new CopyOnWriteArrayList<>();
        RunningSupervisor running = startSupervisor(
                tempDir, fakeLauncher(starts, processes));
        JdtlsSettingsInputs firstSettings =
                new JdtlsSettingsInputs(null, null, null, "true", null, null);
        JdtlsSettingsInputs secondSettings =
                new JdtlsSettingsInputs(null, null, null, "false", null, null);
        sendAndCloseLease(running.socketPath(), request(workspace, firstSettings));
        awaitLeaseCount(running.registry(), "repo", 0);
        WorkerRecord replaced = running.registry().get("repo");
        sendAndCloseLease(running.socketPath(), request(workspace, secondSettings));
        awaitLeaseCount(running.registry(), "repo", 0);
        WorkerRecord replacement = running.registry().get("repo");

        running.supervisor().stopIdleWorker("repo", replaced);

        assertThat(running.registry().get("repo")).isSameAs(replacement);
        assertThat(processes.get(1).isAlive()).isTrue();
        assertThat(starts).hasValue(2);
        running.close();
    }

    private static SocketChannel openLeaseChannel(
            Path socketPath,
            SupervisorRequest request) throws Exception {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(socketPath));
        Writer writer = new OutputStreamWriter(Channels.newOutputStream(channel));
        writer.write(GSON.toJson(request));
        writer.write("\n");
        writer.flush();
        return channel;
    }

    private static RunningSupervisor startSupervisor(
            Path tempDir,
            WorkerProcessLauncher launcher) throws Exception {
        Path socketPath = tempDir.resolve("supervisor.sock");
        CountDownLatch ready = new CountDownLatch(1);
        WorkerRegistry registry = new WorkerRegistry();
        SupervisorMain supervisor = new SupervisorMain(
                socketPath, registry, launcher, ready::countDown);
        Thread thread = start(supervisor);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        return new RunningSupervisor(socketPath, registry, supervisor, thread);
    }

    private static SupervisorRequest request(Path workspace, JdtlsSettingsInputs inputs) {
        return new SupervisorRequest(
                SupervisorCommand.OPEN_LEASE,
                workspace.getFileName().toString(),
                workspace.toAbsolutePath().toString(),
                "jdtls",
                inputs);
    }

    private static SupervisorResponse sendAndCloseLease(
            Path socketPath,
            SupervisorRequest request) throws Exception {
        try (SocketChannel channel = openLeaseChannel(socketPath, request)) {
            return readResponse(channel);
        }
    }

    private static void awaitLeaseCount(
            WorkerRegistry registry,
            String repoId,
            int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            WorkerRecord record = registry.get(repoId);
            if (record != null && record.leaseCount() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(registry.get(repoId).leaseCount()).isEqualTo(expected);
    }

    private static void awaitRecordRemoved(WorkerRegistry registry, String repoId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && registry.get(repoId) != null) {
            Thread.sleep(10);
        }
        assertThat(registry.get(repoId)).isNull();
    }

    private static WorkerProcessLauncher fakeLauncher(
            AtomicInteger starts,
            List<TrackingProcess> processes) {
        return (workspace, command, inputs) -> {
            int number = starts.incrementAndGet();
            TrackingProcess process = new TrackingProcess();
            processes.add(process);
            return new WorkerProcessLauncher.StartedWorker(
                    process,
                    300L + number,
                    "127.0.0.1",
                    45_000 + number,
                    settingsFingerprint(workspace, inputs));
        };
    }

    private static String settingsFingerprint(Path workspace, JdtlsSettingsInputs inputs)
            throws Exception {
        return JdtlsSettingsSource.capture(
                        workspace.toAbsolutePath(),
                        inputs,
                        Path.of(System.getProperty("java.home")))
                .fingerprint();
    }

    private static LeaseAttempt openAfter(
            CountDownLatch go,
            Path socketPath,
            SupervisorRequest request) throws Exception {
        go.await();
        SocketChannel channel = openLeaseChannel(socketPath, request);
        return new LeaseAttempt(channel, readResponse(channel));
    }

    private static LeaseAttempt openAfter(
            CyclicBarrier barrier,
            Path socketPath,
            SupervisorRequest request) throws Exception {
        barrier.await();
        SocketChannel channel = openLeaseChannel(socketPath, request);
        return new LeaseAttempt(channel, readResponse(channel));
    }

    private static Thread start(SupervisorMain supervisor) {
        Thread thread = new Thread(() -> {
            try {
                supervisor.run();
            } catch (Exception ex) {
                throw new IllegalStateException("Supervisor test server failed", ex);
            }
        });
        thread.start();
        return thread;
    }

    private static SupervisorResponse readResponse(SocketChannel channel) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
        return GSON.fromJson(reader.readLine(), SupervisorResponse.class);
    }

    private static SupervisorResponse sendRequest(Path socketPath, SupervisorRequest request) throws Exception {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            try (Writer writer = new OutputStreamWriter(Channels.newOutputStream(channel));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)))) {
                writer.write(GSON.toJson(request));
                writer.write("\n");
                writer.flush();
                return GSON.fromJson(reader.readLine(), SupervisorResponse.class);
            }
        }
    }

    private record RunningSupervisor(
            Path socketPath,
            WorkerRegistry registry,
            SupervisorMain supervisor,
            Thread serverThread) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            supervisor.close();
            serverThread.join(2_000);
        }
    }

    private record LeaseAttempt(SocketChannel channel, SupervisorResponse response)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            channel.close();
        }
    }

    private static class TrackingProcess extends Process {
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private volatile boolean alive = true;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            exit.join();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return 0;
        }

        @Override
        public void destroy() {
            completeExit();
        }

        @Override
        public Process destroyForcibly() {
            completeExit();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }

        void completeExit() {
            markNotAlive();
            exit.complete(this);
        }

        void markNotAlive() {
            alive = false;
        }
    }

    private static final class StubbornProcess extends TrackingProcess {
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }
    }

    private static final class LaggingExitProcess extends TrackingProcess {
        private final CompletableFuture<Process> delayedExit = new CompletableFuture<>();

        void markExitedWithoutNotification() {
            markNotAlive();
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return delayedExit;
        }
    }

    private static final class BlockingStopProcess extends TrackingProcess {
        private final CountDownLatch stopStarted = new CountDownLatch(1);
        private final CountDownLatch mayExit = new CountDownLatch(1);

        @Override
        public void destroy() {
            stopStarted.countDown();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (mayExit.await(timeout, unit)) {
                completeExit();
                return true;
            }
            return false;
        }

        void allowExit() {
            mayExit.countDown();
        }
    }
}
