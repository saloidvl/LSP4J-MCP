package com.saloidvl.lsp4jmcp.supervisor;

import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class WorkerRegistry {
    private final Clock clock;
    private final Map<String, WorkerRecord> workersByRepoId = new HashMap<>();
    private final Map<Object, String> leaseHandleToRepoId = new HashMap<>();

    public WorkerRegistry() {
        this(Clock.systemUTC());
    }

    public WorkerRegistry(Clock clock) {
        this.clock = clock;
    }

    public synchronized WorkerRecord registerReady(
            String repoId,
            Path workspacePath,
            String jdtlsCommand,
            Process process,
            long workerPid,
            String host,
            int port) {
        WorkerRecord record = new WorkerRecord(
            repoId,
            workspacePath,
            jdtlsCommand,
            process,
            workerPid,
            host,
            port,
            WorkerState.READY
        );
        workersByRepoId.put(repoId, record);
        return record;
    }

    public synchronized WorkerRecord get(String repoId) {
        return workersByRepoId.get(repoId);
    }

    public synchronized Object acquireLease(String repoId) {
        WorkerRecord record = workersByRepoId.get(repoId);
        if (record == null || record.state() != WorkerState.READY) {
            return null;
        }
        Object handle = new Object();
        record.addLease(handle);
        leaseHandleToRepoId.put(handle, repoId);
        return handle;
    }

    public synchronized boolean releaseLease(Object handle) {
        String repoId = leaseHandleToRepoId.remove(handle);
        if (repoId == null) {
            return false;
        }
        WorkerRecord record = workersByRepoId.get(repoId);
        if (record == null) {
            return false;
        }
        return record.removeLease(handle, clock.instant());
    }

    public synchronized String repoIdForLease(Object handle) {
        return leaseHandleToRepoId.get(handle);
    }

    public synchronized List<WorkerRecord> collectIdleWorkers(Instant now) {
        List<WorkerRecord> idleWorkers = new ArrayList<>();
        for (WorkerRecord record : workersByRepoId.values()) {
            if (record.leaseCount() == 0
                    && record.lastLeaseReleasedAt() != null
                    && !record.lastLeaseReleasedAt().plus(RuntimeConstants.WORKER_IDLE_SHUTDOWN_DELAY).isAfter(now)) {
                idleWorkers.add(record);
            }
        }
        return idleWorkers;
    }

    public synchronized void remove(String repoId) {
        WorkerRecord record = workersByRepoId.remove(repoId);
        if (record == null) {
            return;
        }
        if (record.pendingIdleShutdown() != null) {
            record.pendingIdleShutdown().cancel(false);
            record.setPendingIdleShutdown(null);
        }
        for (Object handle : new HashSet<>(record.leases())) {
            leaseHandleToRepoId.remove(handle);
        }
    }
}
