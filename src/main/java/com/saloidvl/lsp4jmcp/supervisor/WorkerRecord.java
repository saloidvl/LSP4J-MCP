package com.saloidvl.lsp4jmcp.supervisor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

final class WorkerRecord {
    private final String repoId;
    private final Path workspacePath;
    private final String jdtlsCommand;
    private final Map<String, Instant> leases = new HashMap<>();

    private long workerPid;
    private String host;
    private int port;
    private WorkerState state;
    private Instant lastLeaseReleasedAt;

    WorkerRecord(String repoId, Path workspacePath, String jdtlsCommand, long workerPid, String host, int port, WorkerState state) {
        this.repoId = repoId;
        this.workspacePath = workspacePath;
        this.jdtlsCommand = jdtlsCommand;
        this.workerPid = workerPid;
        this.host = host;
        this.port = port;
        this.state = state;
    }

    String repoId() {
        return repoId;
    }

    Path workspacePath() {
        return workspacePath;
    }

    String jdtlsCommand() {
        return jdtlsCommand;
    }

    long workerPid() {
        return workerPid;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    WorkerState state() {
        return state;
    }

    void setState(WorkerState state) {
        this.state = state;
    }

    int leaseCount() {
        return leases.size();
    }

    Instant lastLeaseReleasedAt() {
        return lastLeaseReleasedAt;
    }

    void addLease(String leaseId, Instant now) {
        leases.put(leaseId, now);
        lastLeaseReleasedAt = null;
    }

    boolean hasLease(String leaseId) {
        return leases.containsKey(leaseId);
    }

    void touchLease(String leaseId, Instant now) {
        if (leases.containsKey(leaseId)) {
            leases.put(leaseId, now);
        }
    }

    boolean removeLease(String leaseId, Instant now) {
        boolean removed = leases.remove(leaseId) != null;
        if (removed && leases.isEmpty()) {
            lastLeaseReleasedAt = now;
        }
        return removed;
    }

    Map<String, Instant> leases() {
        return leases;
    }
}
