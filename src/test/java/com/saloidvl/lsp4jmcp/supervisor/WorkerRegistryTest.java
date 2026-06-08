package com.saloidvl.lsp4jmcp.supervisor;

import com.saloidvl.lsp4jmcp.control.SupervisorResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerRegistryTest {

    @Test
    void acquire_existingReadyWorkerIncrementsLeaseCount() {
        WorkerRegistry registry = new WorkerRegistry(Clock.fixed(Instant.parse("2026-04-22T10:00:00Z"), ZoneOffset.UTC));
        registry.registerReady("repo-1", Path.of("/tmp/repo"), "jdtls", 123L, "127.0.0.1", 51234);

        SupervisorResponse first = registry.acquire("repo-1");
        SupervisorResponse second = registry.acquire("repo-1");

        assertThat(first.ok()).isTrue();
        assertThat(second.ok()).isTrue();
        assertThat(second.port()).isEqualTo(51234);
        assertThat(registry.get("repo-1").leaseCount()).isEqualTo(2);
    }

    @Test
    void release_lastLeaseMarksWorkerIdle() {
        WorkerRegistry registry = new WorkerRegistry(Clock.fixed(Instant.parse("2026-04-22T10:00:00Z"), ZoneOffset.UTC));
        registry.registerReady("repo-1", Path.of("/tmp/repo"), "jdtls", 123L, "127.0.0.1", 51234);

        String leaseId = registry.acquire("repo-1").leaseId();
        registry.release(leaseId);

        WorkerRecord record = registry.get("repo-1");
        assertThat(record.leaseCount()).isZero();
        assertThat(record.lastLeaseReleasedAt()).isEqualTo(Instant.parse("2026-04-22T10:00:00Z"));
    }

    @Test
    void collectIdleWorkers_returnsWorkersPastIdleDelay() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-22T10:00:00Z"), ZoneOffset.UTC);
        WorkerRegistry registry = new WorkerRegistry(clock);
        registry.registerReady("repo-1", Path.of("/tmp/repo"), "jdtls", 123L, "127.0.0.1", 51234);

        String leaseId = registry.acquire("repo-1").leaseId();
        registry.release(leaseId);

        List<WorkerRecord> idleWorkers = registry.collectIdleWorkers(Instant.parse("2026-04-22T10:01:00Z"));

        assertThat(idleWorkers).hasSize(1);
        assertThat(idleWorkers.getFirst().repoId()).isEqualTo("repo-1");
    }
}
