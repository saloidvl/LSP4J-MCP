# JDTLS Client

This package owns the connection to the JDTLS subprocess and all LSP request dispatch.

## Class Roles

| Class                     | Role                                                                                                  |
|---------------------------|-------------------------------------------------------------------------------------------------------|
| `JdtlsClient`             | Public API. Owns JDTLS lifecycle, LSP request methods, recovery orchestration.                        |
| `JdtlsLanguageClient`     | LSP4J `LanguageClient` implementation. Receives server notifications (progress, diagnostics, status). |
| `JdtlsLanguageServer`     | LSP4J-extended `LanguageServer` that adds JDTLS-specific `java/buildWorkspace`.                       |
| `JdtlsSessionManager`     | Manages the active LSP session (process + launcher + open document set). Handles graceful shutdown.   |
| `JdtlsRecoveryManager`    | State machine for automatic restart/reindex; enforces cooldown and max-attempt limits.                |
| `JdtlsRecoveryClassifier` | Maps exceptions and log messages to `JdtlsRecoveryAction` (NONE / RESTART / REINDEX).                 |
| `DiagnosticsCache`        | Thread-safe cache of last JDTLS `publishDiagnostics` notifications, keyed by URI.                     |
| `LombokSupport`           | Detects Lombok jar in the workspace's local Maven repo and returns its path for JDTLS `-javaagent`.   |
| `LspSummarizer`           | Debug-only helpers that truncate large LSP values for log output.                                     |
| `DebugLogWriter`          | Writes a raw LSP traffic log file when `LSP4J_DEBUG_LOG` env var is set.                              |

## JdtlsClient Initialization Modes

```
createAndInitialize(...)      — blocking; retries once with clean data dir on failure
createAndInitializeAsync(...) — non-blocking; initialize() runs on a daemon thread
                                (used by RepoWorkerMain — readiness reported via indexing_status)
```

Constructor always starts a session immediately. `initialize()` sends the LSP `initialize` +
`initialized` handshake.

## JDTLS State Machine

```
STARTING ──► INDEXING ──► READY
    │             │          │
    │    recovery signals    │
    ▼             ▼          ▼
RECOVERING_RESTART / RECOVERING_REINDEX
    │
    ├─► INDEXING (recovery succeeded)
    └─► DEGRADED / FAILED (max attempts exceeded or non-recoverable error)
```

State lives in `JdtlsRecoveryManager`. `JdtlsClient` delegates all transitions via
`recovery.transitionTo(...)`.

`DEGRADED` = automatic recovery suppressed (cooldown or attempt limit); manual `restart_jdtls` or
`reindex_workspace` needed.
`FAILED` = non-recoverable; process gone or initialization error.

## Recovery Policy

- **Cooldown**: `RuntimeConstants.JDTLS_RECOVERY_COOLDOWN` (30 s) between attempts
- **Window**: `RuntimeConstants.JDTLS_RECOVERY_WINDOW` (5 min)
- **Max attempts**: `RuntimeConstants.JDTLS_MAX_RECOVERY_ATTEMPTS` (3) within the window
- Recovery action priority: `REINDEX > RESTART > NONE`; a pending higher-priority action replaces a
  queued lower one
- Signals from `JdtlsLanguageClient` (log messages) and `JdtlsRecoveryClassifier` (exceptions, exit
  codes)

## `buildWorkspace` vs `buildIncremental`

Both call `java/buildWorkspace` (JDTLS extension):

- `buildWorkspace()` — passes `true` (CLEAN + FULL); used by `reindexWorkspace` MCP tool
- `buildIncremental()` — passes `false` (incremental); used by `refresh_diagnostics` MCP tool

## Document Open Tracking

Before any textDocument request, `ensureDocumentOpen()` sends `textDocument/didOpen` with the file
content. Tracked per-session in `JdtlsSessionManager.RuntimeSession.openedDocuments` (a
`Set<String>` of URIs). The set is discarded when the session restarts.

## Data Directory

`/tmp/jdtls-data/<first-16-hex-of-sha256(workspacePath)>/` — passed to JDTLS as its workspace
storage. Deleted and recreated on `reindexWorkspace` or a clean restart.
