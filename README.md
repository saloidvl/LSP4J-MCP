# LSP4J-MCP Server

LSP4J-MCP is a Java MCP server for Java codebases. It wraps Eclipse JDT Language Server through LSP4J and exposes Java navigation and symbol-search tools to MCP clients.

The packaged entrypoint is now a local `launcher`, not the heavy analysis backend itself. The launcher keeps the MCP client contract on `stdio`, talks to a shared local `supervisor`, and reuses one `repo worker` plus one `JDTLS` process per repository.

## What It Is

Use this server when you want an MCP-compatible client to inspect a Java repository through JDTLS-backed tools such as symbol lookup, reference search, go-to-definition, and document symbol listing.

## Tools

| Tool | Description |
|------|-------------|
| `find_symbols` | Search for Java symbols by name |
| `find_references` | Find references at a file location |
| `find_definition` | Go to definition at a file location |
| `document_symbols` | List symbols declared in one Java file |
| `indexing_status` | Report whether JDTLS indexing is still in progress |
| `find_interfaces_with_method` | Find interfaces containing a method name |

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
- Maven 3.8+
- JDTLS available on the machine where the worker runs
- macOS or Linux recommended for the shared-runtime path

## Installing JDTLS

Homebrew on macOS:

```bash
brew install jdtls
```

If you do not use Homebrew, install Eclipse JDT Language Server from the official project and make sure the executable is available in `PATH`, or pass the full command as the last argument when starting the launcher.

## Build

Normal development build:

```bash
mvn clean package
```

This produces a shaded JAR in `target/`. Normal Maven builds do not publish artifacts into `dist/`.

## Published Artifact

The repository-managed binary lives in `dist/`, for example:

```text
dist/lsp4j-mcp-1.0.5-SNAPSHOT.jar
```

To publish a tracked binary into `dist/`, run:

```bash
./scripts/bump-and-publish.sh <new-version>
```

This flow:

- updates `pom.xml` to the requested version
- builds the shaded JAR
- copies it from `target/` into `dist/`
- fails if `dist/lsp4j-mcp-<new-version>.jar` already exists

## Running The Launcher

With the tracked repository artifact:

```bash
./run.sh /path/to/java/workspace
```

Direct invocation:

```bash
java -jar /path/to/lsp4j-mcp/dist/lsp4j-mcp-<version>.jar /path/to/java/workspace jdtls
```

Arguments:

1. Java workspace root to analyze
2. JDTLS command to start, for example `jdtls`

The launched process is `LauncherMain`. On the first connection for a repository it starts a shared local supervisor and a per-repository worker if needed. Later clients for the same repository reuse that worker and its single `JDTLS` process.

## Connecting From Any MCP Client

The MCP client contract is still a local process over `stdio`:

```text
command: java
args:
  - -jar
  - /path/to/lsp4j-mcp/dist/lsp4j-mcp-<version>.jar
  - /path/to/java/workspace
  - jdtls
transport: stdio
```

Example JSON-style configuration:

```json
{
  "command": "java",
  "args": [
    "-jar",
    "/path/to/lsp4j-mcp/dist/lsp4j-mcp-1.0.5-SNAPSHOT.jar",
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
        "/path/to/lsp4j-mcp/dist/lsp4j-mcp-1.0.5-SNAPSHOT.jar",
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

## Development Notes

- Production code lives under `src/main/java/com/saloidvl/lsp4jmcp/`
- `target/` is build output only
- `dist/` is reserved for tracked published JARs
- Run tests with `mvn test`
- `JdtlsClientIntegrationTest` requires `JDTLS_PATH` to be set

## Logging

Logs are written to `LOG_FILE` if provided, otherwise to `/tmp/lsp4j-mcp.log`.

`stdout` is reserved for MCP protocol traffic between the MCP client and launcher, so diagnostic logs should go to the log file, not to standard output.

## License

MIT
