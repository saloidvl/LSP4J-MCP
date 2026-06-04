package com.saloidvl.lsp4jmcp.supervisor;

import com.saloidvl.lsp4jmcp.control.SupervisorResponse;
import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkerRegistry {
    private final Clock clock;
    private final Map<String, WorkerRecord> workersByRepoId = new HashMap<>();
    private final Map<String, String> repoIdByLeaseId = new HashMap<>();

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
            long workerPid,
            String host,
            int port) {
        WorkerRecord record = new WorkerRecord(
            repoId,
            workspacePath,
            jdtlsCommand,
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

    public synchronized SupervisorResponse acquire(String repoId) {
        WorkerRecord record = workersByRepoId.get(repoId);
        if (record == null || record.state() != WorkerState.READY) {
            return new SupervisorResponse(false, "Worker not ready", null, null, null, null);
        }

        String leaseId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        record.addLease(leaseId, now);
        repoIdByLeaseId.put(leaseId, repoId);

        return new SupervisorResponse(true, "ok", leaseId, record.host(), record.port(), record.workerPid());
    }

    public synchronized boolean release(String leaseId) {
        String repoId = repoIdByLeaseId.remove(leaseId);
        if (repoId == null) {
            return false;
        }

        WorkerRecord record = workersByRepoId.get(repoId);
        if (record == null) {
            return false;
        }

        return record.removeLease(leaseId, clock.instant());
    }

    public synchronized boolean heartbeat(String leaseId) {
        String repoId = repoIdByLeaseId.get(leaseId);
        if (repoId == null) {
            return false;
        }

        WorkerRecord record = workersByRepoId.get(repoId);
        if (record == null) {
            return false;
        }

        record.touchLease(leaseId, clock.instant());
        return true;
    }

    public synchronized List<WorkerRecord> expireLeases(Instant now) {
        List<String> expiredLeaseIds = new ArrayList<>();
        List<WorkerRecord> touchedRecords = new ArrayList<>();

        for (Map.Entry<String, String> entry : repoIdByLeaseId.entrySet()) {
            String leaseId = entry.getKey();
            WorkerRecord record = workersByRepoId.get(entry.getValue());
            if (record == null) {
                expiredLeaseIds.add(leaseId);
                continue;
            }

            Instant lastHeartbeat = record.leases().get(leaseId);
            if (lastHeartbeat != null
                    && lastHeartbeat.plus(RuntimeConstants.LEASE_EXPIRY_TIMEOUT).isBefore(now)) {
                expiredLeaseIds.add(leaseId);
                touchedRecords.add(record);
            }
        }

        for (String leaseId : expiredLeaseIds) {
            release(leaseId);
        }

        return touchedRecords;
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

        List<String> leaseIds = new ArrayList<>(record.leases().keySet());
        for (String leaseId : leaseIds) {
            repoIdByLeaseId.remove(leaseId);
        }
    }
}
