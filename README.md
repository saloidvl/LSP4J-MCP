# LSP4J-MCP Server

[![CI](https://github.com/saloidvl/lsp4j-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/saloidvl/lsp4j-mcp/actions/workflows/ci.yml)

LSP4J-MCP is a Java MCP server for Java codebases. It wraps Eclipse JDT Language Server through LSP4J and exposes Java navigation and symbol-search tools to MCP clients.

The packaged entrypoint is now a local `launcher`, not the heavy analysis backend itself. The launcher keeps the MCP client contract on `stdio`, talks to a shared local `supervisor`, and reuses one `repo worker` plus one `JDTLS` process per repository.

## Based On

This is a fork of [devoxx/lsp4j-mcp](https://github.com/devoxx/lsp4j-mcp) with a major architectural overhaul.

Key changes from the original:

- New multi-process runtime: `LauncherMain` → `SupervisorMain` → `RepoWorkerMain`
- Shared JDTLS instance per repository — multiple agents reuse one backend, no duplicate indexing
- Unix domain socket + TCP IPC replacing the original direct subprocess model
- Added `find_interfaces_with_method` tool
- Automatic JDTLS crash recovery with back-to-back recovery protection
- Full test suite added

## What It Is

Use this server when you want an MCP-compatible client to inspect a Java repository through JDTLS-backed tools such as symbol lookup, reference search, go-to-definition, and document symbol listing.

## Tools

| Tool | Input | Output |
|------|-------|--------|
| `find_symbols` | Symbol name (string, supports wildcards) | List of matching symbols with file locations |
| `find_references` | File path, line, character | All usages of the symbol at that position |
| `find_definition` | File path, line, character | Definition location(s) of the symbol at that position |
| `document_symbols` | File path | All symbols declared in that Java file |
| `indexing_status` | — | Whether JDTLS indexing is still in progress |
| `find_interfaces_with_method` | Method name | Interfaces that declare a method with that name |

## Runtime Model

```text
MCP Client -> LauncherMain -> SupervisorMain -> RepoWorkerMain -> JDTLS -> Java workspace
```

Rules:

- one local `supervisor` process per machine
- one `repo worker` per active repository
- one `JDTLS` process per active repository
- many MCP clients can share the same repository worker

This reduces duplicate indexing and prevents every agent or sub-agent from starting its own heavy backend for the same repo.

The current IPC design is optimized for macOS and Linux:

- MCP client to launcher: `stdio`
- launcher to supervisor: Unix domain socket
- launcher to repo worker: `127.0.0.1` TCP on an ephemeral port

## Requirements

- Java 21+
- Maven 3.8+ (only if building from source)
- JDTLS available on the machine where the worker runs
- macOS or Linux recommended for the shared-runtime path

## Installing JDTLS

Homebrew on macOS:

```bash
brew install jdtls
```

If you do not use Homebrew, install Eclipse JDT Language Server from the official project and make sure the executable is available in `PATH`, or pass the full command as the last argument when starting the launcher.

## Install

Download the latest JAR from the [Releases page](https://github.com/saloidvl/lsp4j-mcp/releases):

```bash
# Example — replace version as needed
curl -L -o lsp4j-mcp.jar \
  https://github.com/saloidvl/lsp4j-mcp/releases/latest/download/lsp4j-mcp-1.0.6.jar
```

## Build from Source

```bash
mvn clean package
```

This produces a shaded JAR in `target/`. Run it directly from there:

```bash
java -jar target/lsp4j-mcp-<version>.jar /path/to/java/workspace jdtls
```

## Running

Arguments:

1. Java workspace root to analyze
2. JDTLS command to start, for example `jdtls`

```bash
java -jar lsp4j-mcp.jar /path/to/java/workspace jdtls
```

The launched process is `LauncherMain`. On the first connection for a repository it starts a shared local supervisor and a per-repository worker if needed. Later clients for the same repository reuse that worker and its single `JDTLS` process.

## Connecting From Any MCP Client

The MCP client contract is a local process over `stdio`:

```json
{
  "command": "java",
  "args": [
    "-jar",
    "/path/to/lsp4j-mcp.jar",
    "/path/to/java/workspace",
    "jdtls"
  ],
  "env": {
    "LOG_FILE": "/tmp/lsp4j-mcp.log"
  }
}
```

## Claude Code Example

Add to `.mcp.json`:

```json
{
  "mcpServers": {
    "java-lsp": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/lsp4j-mcp.jar",
        "/path/to/java/workspace",
        "jdtls"
      ],
      "env": {
        "LOG_FILE": "/tmp/lsp4j-mcp.log"
      }
    }
  }
}
```

## Releasing

```bash
./scripts/release.sh 1.1.0
```

This validates the working tree, bumps `pom.xml`, builds, commits, tags, and pushes. GitHub Actions then creates the GitHub Release with the JAR attached.

## Development Notes

- Production code lives under `src/main/java/com/saloidvl/lsp4jmcp/`
- `target/` is build output only
- Run tests with `mvn test`
- `JdtlsClientIntegrationTest` requires `JDTLS_PATH` to be set

## Logging

Logs are written to `LOG_FILE` if provided, otherwise to `/tmp/lsp4j-mcp.log`.

`stdout` is reserved for MCP protocol traffic between the MCP client and launcher, so diagnostic logs should go to the log file, not to standard output.

## License

MIT — see [LICENSE](LICENSE).
