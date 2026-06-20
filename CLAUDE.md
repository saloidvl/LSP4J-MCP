# CLAUDE.md

This file provides guidance to coding agents working in this repository.

## Project Overview

LSP4J-MCP is a Java MCP server for Java repositories. The packaged entrypoint is `LauncherMain`, which keeps the client-facing `stdio` contract but reuses a shared backend per repository.

## Build, Test, And Run

```bash
# Normal development build output in target/
mvn clean package

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=JavaToolsTest

# Release: bump version in pom.xml, commit, tag vX.Y.Z, push tag — CI builds and publishes JAR to GitHub Releases
mvn versions:set -DnewVersion=<new-version> -DgenerateBackupPoms=false
git add pom.xml && git commit -m "Release v<new-version>"
git tag -a v<new-version> -m "Release v<new-version>"
git push origin HEAD && git push origin v<new-version>
```

## Artifact Layout

- `target/` contains normal Maven build output

## Architecture

```text
MCP Client -> LauncherMain -> SupervisorMain -> RepoWorkerMain -> JDTLS -> Java workspace
```

Runtime rules:

1. `LauncherMain` is the shaded-jar entry point used by MCP clients.
2. `SupervisorMain` is a machine-local coordinator reached over a Unix domain socket.
3. `RepoWorkerMain` is one backend process per active repository.
4. Each repo worker owns one `JdtlsClient` and one `JDTLS` subprocess.
5. Multiple agents targeting the same repository should reuse one worker and one `JDTLS`.

## Key Design Decisions

- Keep the MCP client contract on `stdio`
- Use Unix domain sockets for launcher-to-supervisor control traffic
- Use localhost TCP for launcher-to-worker data traffic
- Keep all lifecycle timings in `RuntimeConstants`
- Store JDTLS workspace data under `/tmp/jdtls-data/<hash>/` to avoid overlapping the analyzed workspace
- Keep `stdout` reserved for MCP traffic and send logs to `LOG_FILE` or `/tmp/lsp4j-mcp.log`
- Load server version from filtered Maven build metadata in `src/main/resources/build-info.properties`

## Main Components

- `JavaMcpServer` - shared MCP server bootstrap on arbitrary input/output streams
- `JdtlsClient` and `JdtlsLanguageClient` - JDTLS startup, indexing, readiness, and progress tracking
- `WorkerRegistry` - per-repository worker metadata, leases, and idle bookkeeping
- `WorkerProcessLauncher` - starts repo workers inside the current JVM/classpath environment
- `SupervisorClient` - launcher-side client for acquire, heartbeat, and release

## MCP Tools

- `find_symbols` - Search for Java symbols by name
- `find_references` - Find all references to a symbol at file, line, and character
- `find_definition` - Go to the definition of a symbol at file, line, and character
- `document_symbols` - List symbols defined in a Java file
- `indexing_status` - Report whether JDTLS indexing is still in progress
- `find_interfaces_with_method` - Find interfaces containing a method with the given name

## Testing Notes

- Unit tests use JUnit 5, AssertJ, and Mockito
- Test resources pin Mockito to the subclass mock maker so the suite stays stable on the current JDK in this environment
- `JdtlsClientIntegrationTest` is guarded by `JDTLS_PATH` and is skipped unless JDTLS is explicitly configured
- `RepoWorkerMainTest`, `WorkerRegistryTest`, `SupervisorMainIntegrationTest`, and `LauncherMainIntegrationTest` cover the shared-runtime pieces without requiring a real JDTLS install
