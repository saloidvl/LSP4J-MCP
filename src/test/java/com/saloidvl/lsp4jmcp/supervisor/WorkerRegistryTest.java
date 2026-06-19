package com.saloidvl.lsp4jmcp.supervisor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerRegistryTest {

    @Test
    void acquireLease_existingReadyWorkerIncrementsLeaseCount() {
        WorkerRegistry registry = new WorkerRegistry(Clock.fixed(Instant.parse("2026-04-22T10:00:00Z"), ZoneOffset.UTC));
        registry.registerReady("repo-1", Path.of("/tmp/repo"), "jdtls", null, 123L, "127.0.0.1", 51234);

        Object first = registry.acquireLease("repo-1");
        Object second = registry.acquireLease("repo-1");

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isNotSameAs(second);
        assertThat(registry.get("repo-1").leaseCount()).isEqualTo(2);
    }

    @Test
    void releaseLease_lastLeaseMarksWorkerIdle() {
        WorkerRegistry registry = new WorkerRegistry(Clock.fixed(Instant.parse("2026-04-22T10:00:00Z"), ZoneOffset.UTC));
        registry.registerReady("repo-1", Path.of("/tmp/repo"), "jdtls", null, 123L, "127.0.0.1", 51234);

        Object handle = registry.acquireLease("repo-1");
        registry.releaseLease(handle);

        WorkerRecord record = registry.get("repo-1");
        assertThat(record.leaseCount()).isZero();
        assertThat(record.lastLeaseReleasedAt()).isEqualTo(Instant.parse("2026-04-22T10:00:00Z"));
    }

    @Test
    void releaseLease_doubleReleaseIsIdempotent() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.registerReady("repo-1", Path.of("/tmp/repo"), "jdtls", null, 123L, "127.0.0.1", 51234);

        Object handle = registry.acquireLease("repo-1");
        assertThat(registry.releaseLease(handle)).isTrue();
        assertThat(registry.releaseLease(handle)).isFalse();
    }

    @Test
    void collectIdleWorkers_returnsWorkersPastIdleDelay() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-22T10:00:00Z"), ZoneOffset.UTC);
        WorkerRegistry registry = new WorkerRegistry(clock);
        registry.registerReady("repo-1", Path.of("/tmp/repo"), "jdtls", null, 123L, "127.0.0.1", 51234);

        Object handle = registry.acquireLease("repo-1");
        registry.releaseLease(handle);

        List<WorkerRecord> idleWorkers = registry.collectIdleWorkers(Instant.parse("2026-04-22T10:01:00Z"));

        assertThat(idleWorkers).hasSize(1);
        assertThat(idleWorkers.getFirst().repoId()).isEqualTo("repo-1");
    }

    @Test
    void acquireLease_cancelsScheduledIdleShutdown() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.registerReady("repo-1", Path.of("/tmp/repo"), "jdtls", null, 123L, "127.0.0.1", 51234);

        Object handle = registry.acquireLease("repo-1");
        registry.releaseLease(handle);

        WorkerRecord record = registry.get("repo-1");
        assertThat(record.leaseCount()).isZero();

        // Acquire again — addLease() should clear lastLeaseReleasedAt
        Object handle2 = registry.acquireLease("repo-1");
        assertThat(record.lastLeaseReleasedAt()).isNull();
        assertThat(record.leaseCount()).isEqualTo(1);

        registry.releaseLease(handle2);
    }
}
