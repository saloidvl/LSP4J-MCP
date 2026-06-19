package com.saloidvl.lsp4jmcp.supervisor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

final class WorkerRecord {
    private final String repoId;
    private final Path workspacePath;
    private final String jdtlsCommand;
    private final Set<Object> leases = new HashSet<>();

    private Process process;
    private long workerPid;
    private String host;
    private int port;
    private WorkerState state;
    private Instant lastLeaseReleasedAt;
    private ScheduledFuture<?> pendingIdleShutdown;

    WorkerRecord(String repoId, Path workspacePath, String jdtlsCommand, Process process, long workerPid, String host, int port, WorkerState state) {
        this.repoId = repoId;
        this.workspacePath = workspacePath;
        this.jdtlsCommand = jdtlsCommand;
        this.process = process;
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

    Process process() {
        return process;
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

    ScheduledFuture<?> pendingIdleShutdown() {
        return pendingIdleShutdown;
    }

    void setPendingIdleShutdown(ScheduledFuture<?> future) {
        this.pendingIdleShutdown = future;
    }

    void addLease(Object handle) {
        leases.add(handle);
        lastLeaseReleasedAt = null;
        if (pendingIdleShutdown != null) {
            pendingIdleShutdown.cancel(false);
            pendingIdleShutdown = null;
        }
    }

    boolean removeLease(Object handle, Instant now) {
        boolean removed = leases.remove(handle);
        if (removed && leases.isEmpty()) {
            lastLeaseReleasedAt = now;
        }
        return removed;
    }

    Set<Object> leases() {
        return leases;
    }
}
