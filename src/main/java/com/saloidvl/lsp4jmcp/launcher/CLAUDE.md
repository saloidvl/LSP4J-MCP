# Launcher

`LauncherMain` is the shaded-JAR entry point that MCP clients (e.g. Claude) invoke.

## Responsibilities

1. Parse CLI args: `<workspace-path> <jdtls-command>`
2. Call `SupervisorClient.connectOrStart()` — starts `SupervisorMain` if not running
3. Open a lease (supervisor returns worker host:port)
4. Open a TCP socket to the worker
5. Pipe `System.in → worker socket` and `worker socket → System.out` using virtual threads
6. Close lease on exit (causes the supervisor to schedule idle shutdown for that repo)

## stdio Contract

`System.in` and `System.out` carry raw MCP JSON-RPC. The launcher is a transparent byte-level
proxy — it does not parse or modify the MCP stream. `System.err` and the log file are safe for
diagnostics.

## Lease Lifetime

The `SupervisorClient.Lease` (a `SocketLease` wrapping a Unix domain `SocketChannel`) is kept open
for the entire session via `try-with-resources`. Closing it triggers lease release in the
supervisor.

## CLI Args

```
LauncherMain <workspace-path> <jdtls-command>
```

- `workspace-path` — absolute path to the Java project root
- `jdtls-command` — command to launch JDTLS, e.g. `jdtls` or `/path/to/jdtls`; passed through to
  `WorkerProcessLauncher`
