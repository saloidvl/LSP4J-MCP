package com.saloidvl.lsp4jmcp.runtime;

import java.time.Duration;

public final class RuntimeConstants {
    public static final Duration SUPERVISOR_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration WORKER_STARTUP_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration WORKER_READINESS_POLL_INTERVAL = Duration.ofMillis(100);
    public static final Duration WORKER_TCP_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration LEASE_HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    public static final Duration LEASE_EXPIRY_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration WORKER_IDLE_SHUTDOWN_DELAY = Duration.ofSeconds(30);
    public static final Duration WORKER_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration WORKER_FORCE_KILL_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration JDTLS_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration JDTLS_SELF_EXIT_POLL_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration JDTLS_RECOVERY_COOLDOWN = Duration.ofSeconds(30);
    public static final Duration JDTLS_RECOVERY_WINDOW = Duration.ofMinutes(5);
    public static final long BUILD_TIMEOUT_SECONDS = 300;
    public static final int JDTLS_MAX_RECOVERY_ATTEMPTS = 3;

    private RuntimeConstants() {
    }
}
