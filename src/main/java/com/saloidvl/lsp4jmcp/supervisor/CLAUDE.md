# Supervisor

This package manages the machine-local worker registry and the lease protocol.

## Class Roles

| Class                   | Role                                                                                                                                                       |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SupervisorMain`        | Unix domain socket server; singleton per machine via `FileLock`. Handles OPEN_LEASE requests, per-repo worker startup, idle shutdown scheduling.           |
| `SupervisorClient`      | Interface + `SocketSupervisorClient` impl used by `LauncherMain`. `connectOrStart()` starts `SupervisorMain` as a child JVM if the socket is unreachable.  |
| `WorkerRegistry`        | In-memory map of `repoId → WorkerRecord`. Tracks leases; provides `collectIdleWorkers`.                                                                    |
| `WorkerRecord`          | Mutable state for one worker: host, port, PID, lease set, last-release timestamp, pending-shutdown future.                                                 |
| `WorkerState`           | Enum: `READY` (only state currently used; worker is either ready or absent).                                                                               |
| `WorkerProcessLauncher` | Interface + `JvmWorkerProcessLauncher` impl. Starts `RepoWorkerMain` in a child JVM reusing the current classpath, then polls stdout for `"READY <port>"`. |

## Supervisor Singleton Protocol

`SupervisorMain.run()` acquires a `FileLock` on `<socket-dir>/supervisor.lock`. If the lock is
taken, this instance exits immediately (another supervisor is already running). The socket file is
deleted and recreated on each start.

## OPEN_LEASE Flow

```
LauncherMain                    SupervisorMain
    │──── OPEN_LEASE (JSON) ────►│
    │                            │ fast path: worker already READY → return host:port
    │                            │ slow path: per-repo lock → start worker → wait READY
    │◄─── {ok, host, port} ──────│
    │──── keep connection open ──►│  (connection open = lease held)
    │──── close connection ───────►│ → scheduleIdleShutdownIfNeeded
```

The control connection stays open for the duration of the MCP session. Closing it releases the
lease. The supervisor detects lease release by reading EOF on the control channel.

## Per-Repo Locking

Worker startup uses a two-level lock:

1. `synchronized(this)` — fast path check
2. `repoLocks.computeIfAbsent(repoId, k -> new Object())` — per-repo lock for slow path
3. Inner `synchronized(this)` — double-check + register

This prevents duplicate JDTLS launches when two launchers target the same repo simultaneously.

## Idle Shutdown

After all leases for a repo are released, a `ScheduledExecutorService` task fires after
`RuntimeConstants.WORKER_IDLE_SHUTDOWN_DELAY` (30 s). If the lease count is still 0 at fire time,
the worker process is destroyed and removed from the registry.

The pending shutdown future is cancelled if a new lease arrives before it fires.

## Socket Path

`SocketPaths.supervisorSocketPath()` returns `<LSP4J_MCP_SOCKET_DIR>/supervisor.sock` where
`LSP4J_MCP_SOCKET_DIR` defaults to `/tmp/lsp4j-mcp`. Set `LSP4J_MCP_SOCKET_DIR` in tests to isolate
socket paths across parallel test runs (see `McpToolsIT`).

## Worker Startup Timeout

`WorkerProcessLauncher` polls the child process stdout for `"READY <port>"` with a timeout of
`RuntimeConstants.WORKER_STARTUP_TIMEOUT` (30 s). The port is dynamic (bound to 0 on loopback).
