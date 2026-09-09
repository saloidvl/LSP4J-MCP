# Supervisor

This package manages the machine-local worker registry and the lease protocol.

## Class Roles

| Class                   | Role                                                                                                                                                       |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SupervisorMain`        | Unix domain socket server; singleton per machine via `FileLock`. Handles OPEN_LEASE requests, per-repo worker startup, idle shutdown scheduling.           |
| `SupervisorClient`      | Interface + `SocketSupervisorClient` impl used by `LauncherMain`. `connectOrStart()` starts `SupervisorMain` as a child JVM if the socket is unreachable.  |
| `WorkerRegistry`        | In-memory map of `repoId → WorkerRecord`. Tracks leases; provides `collectIdleWorkers`.                                                                    |
| `WorkerRecord`          | Mutable state for one worker: host, port, PID, settings fingerprint, lease set, last-release timestamp, and pending-shutdown future.                       |
| `WorkerState`           | Lifecycle state; `READY` workers may receive leases and `STOPPING` workers are quarantined until their exit is confirmed.                                  |
| `WorkerProcessLauncher` | Interface + `JvmWorkerProcessLauncher` impl. Starts `RepoWorkerMain` in a child JVM reusing the current classpath, then reads `READY <port> <fingerprint>`.  |

## Supervisor Singleton Protocol

`SupervisorMain.run()` acquires a `FileLock` on `<socket-dir>/supervisor.lock`. If the lock is
taken, this instance exits immediately (another supervisor is already running). The socket file is
deleted and recreated on each start.

## OPEN_LEASE Flow

```
LauncherMain                    SupervisorMain
    │──── OPEN_LEASE (JSON) ────►│
    │                            │ capture requested settings fingerprint
    │                            │ per-repo lock → compare/start/register/acquire atomically
    │◄─── {ok, host, port} ──────│
    │──── keep connection open ──►│  (connection open = lease held)
    │──── close connection ───────►│ → scheduleIdleShutdownIfNeeded
```

The control connection stays open for the duration of the MCP session. Closing it releases the
lease. The supervisor detects lease release by reading EOF on the control channel.

## Per-Repo Locking

Worker compatibility, startup, registration, and first lease acquisition use a two-level lock:

1. `repoLocks.computeIfAbsent(repoId, k -> new Object())` serializes the complete operation for one
   repository.
2. Short `synchronized(this)` sections inspect or update the registry and acquire a matching lease.
3. Process startup and bounded shutdown waits remain inside the per-repository lock but outside the
   global monitor.

This prevents duplicate JDTLS launches and ensures a lease is never handed to a worker running a
different settings fingerprint. A mismatched idle worker is replaced; an active mismatch is rejected
with instructions to close the repository's sessions and reconnect.

## Idle Shutdown

After all leases for a repo are released, a `ScheduledExecutorService` task fires after
`RuntimeConstants.WORKER_IDLE_SHUTDOWN_DELAY` (30 s). If the lease count is still 0 at fire time,
the worker process is destroyed and removed from the registry.

The pending shutdown future is cancelled if a new lease arrives before it fires.

## Socket Path

`SocketPaths.supervisorSocketPath()` returns
`<socket-dir>/supervisor-<version>-p2.sock`. Control-protocol revision 2 is part of the filename, so
processes using the settings-bearing protocol do not connect to an incompatible older supervisor.
The default socket directory is `~/.cache/lsp4j-mcp`; `LSP4J_MCP_SOCKET_DIR` overrides it.

## Worker Startup Timeout

`WorkerProcessLauncher` waits for `READY <port> <fingerprint>` with a timeout of
`RuntimeConstants.WORKER_STARTUP_TIMEOUT` (30 s). Before readiness, the worker may instead emit
`ERROR <message>` and exit when settings cannot be loaded. The port is dynamic (bound to 0 on
loopback), and the fingerprint is compared with the supervisor's captured settings source before
registration.
