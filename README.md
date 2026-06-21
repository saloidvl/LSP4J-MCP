# LSP4J-MCP

An MCP server that gives AI agents IDE-grade Java navigation: go-to-definition, find references, call hierarchies, type hierarchies, diagnostics, hover docs, and more — powered by the same language server that drives Eclipse and VS Code. Instead of grep-and-guess, the agent navigates code the same way a developer does in an IDE.

> Forked from [devoxx/lsp4j-mcp](https://github.com/devoxx/lsp4j-mcp) with a rewritten runtime model, automatic JDTLS recovery, Lombok support, and 14 additional tools. [See what changed.](#changes-from-upstream)

---

## Requirements

- Java 21+
- macOS or Linux (Windows untested)
- JDTLS installed and on `PATH`

## Install JDTLS

**macOS:**
```bash
brew install jdtls
```

**Linux / manual:** download from [eclipse-jdtls/eclipse.jdt.ls releases](https://github.com/eclipse-jdtls/eclipse.jdt.ls/releases) and put the `jdtls` wrapper script on your `PATH`.

## Build

```bash
git clone https://github.com/saloidvl/LSP4J-MCP.git
cd LSP4J-MCP
mvn clean package -DskipTests
```

Produces a shaded JAR at `target/lsp4j-mcp-<version>.jar`.

Unit tests (no JDTLS required):
```bash
mvn test
```

## Run

```bash
java -jar target/lsp4j-mcp-<version>.jar /path/to/java/workspace jdtls
```

Arguments:
1. Absolute path to the Java workspace root
2. JDTLS command — `jdtls` if on `PATH`, or a full path

## MCP Client Configuration

### Claude Code (`.mcp.json`)

```json
{
  "mcpServers": {
    "java-lsp": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/LSP4J-MCP/target/lsp4j-mcp-2.2.1.jar",
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

### Generic MCP client

```json
{
  "command": "java",
  "args": [
    "-jar",
    "/path/to/lsp4j-mcp-2.2.1.jar",
    "/path/to/java/workspace",
    "jdtls"
  ],
  "env": {
    "LOG_FILE": "/tmp/lsp4j-mcp.log"
  }
}
```

---

## Tools

| Tool | Description |
|---|---|
| `find_symbols` | Search for Java symbols (classes, methods, fields) by name |
| `find_references` | Find all references to a symbol at a given file location |
| `find_definition` | Go to the definition of a symbol at a given file location |
| `document_symbols` | List all symbols declared in a Java file |
| `find_implementations` | Find all implementations of an interface method or abstract method |
| `find_method_declarations` | Find interfaces or classes that declare a method with the given name |
| `find_incoming_calls` | Find all call sites where a method is called from |
| `find_outgoing_calls` | Find all methods called by a given method |
| `get_hover` | Get type signature and Javadoc for the symbol at a given position |
| `resolve_stack_trace` | Resolve a Java stack frame to its source file and line number |
| `get_diagnostics` | Return cached compiler diagnostics; supports per-file or workspace summary |
| `refresh_diagnostics` | Trigger a full workspace build to refresh the diagnostics cache |
| `decompile_class` | Decompile a dependency class file (`jdt://` or `jar:` URI from `find_definition`) to source |
| `get_type_hierarchy` | Get supertypes and subtypes for the type at a given position |
| `get_type_definition` | Resolve the declared type of the symbol at a given position |
| `get_projects` | List all Java projects in the workspace |
| `get_classpath` | Get source directories and JAR dependencies for the project containing a given file |
| `indexing_status` | Report current JDTLS health (starting / indexing / ready / degraded / failed) |
| `restart_jdtls` | Soft-restart JDTLS without deleting workspace state |
| `reindex_workspace` | Clean-restart JDTLS and force a full reimport and reindex |

---

## Changes from Upstream

This fork started from [`devoxx/lsp4j-mcp`](https://github.com/devoxx/lsp4j-mcp) (commit `b71d596`). That version was a single-process server with 6 tools and no recovery. Here is what changed.

### Multi-process shared backend

The original design started a new JDTLS process for every MCP client connection. This fork replaces it with a three-tier runtime:

```
MCP Client → LauncherMain → SupervisorMain → RepoWorkerMain → JDTLS → Java workspace
```

Multiple agents targeting the same repository share one JDTLS instance. The supervisor is a machine-level singleton (guarded by `FileLock`). Workers shut down automatically after 30 seconds of no active leases.

IPC:
- MCP client → launcher: `stdio`
- launcher → supervisor: Unix domain socket
- launcher → repo worker: `127.0.0.1` TCP on an ephemeral port

### Automatic JDTLS recovery

JDTLS can crash or enter a broken indexing state during long sessions. A recovery state machine monitors the process and automatically restarts or reindexes on failure, with a 30 s cooldown, 5-minute window, and a 3-attempt limit. After the limit is exceeded, the worker enters `DEGRADED` state and waits for a manual `restart_jdtls` or `reindex_workspace` call.

### 14 additional tools

Upstream shipped 6 tools. This fork adds:

| Tool | What it adds |
|---|---|
| `find_method_declarations` | Filterable by package prefix, container type, and parameter count |
| `find_implementations` | Implementations of interfaces and abstract methods |
| `get_hover` | Javadoc and type signatures at a position |
| `find_incoming_calls` | Incoming call hierarchy |
| `find_outgoing_calls` | Outgoing call hierarchy |
| `get_diagnostics` | Compiler errors and warnings, per-file or summary |
| `refresh_diagnostics` | Incremental build + diagnostics refresh |
| `resolve_stack_trace` | Map stack trace lines to source locations |
| `decompile_class` | Decompile `.class` files from dependencies |
| `get_type_hierarchy` | Supertypes and subtypes |
| `get_type_definition` | Jump to the definition of a type |
| `get_projects` | List projects in the workspace |
| `get_classpath` | Classpath entries for a project |
| `restart_jdtls` / `reindex_workspace` | Manual recovery controls |

---

## Logging

Logs are written to `LOG_FILE` (default: `/tmp/lsp4j-mcp.log`). A separate `.error` file captures ERROR-level entries only. Both files rotate at 50 MB / 10 MB respectively.

`stdout` is reserved for MCP protocol traffic — never redirect logs there.

| Variable | Default | Description |
|---|---|---|
| `LOG_FILE` | `/tmp/lsp4j-mcp.log` | Path to the main log file |
| `LOG_LEVEL` | `INFO` | Logback level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |

```json
{
  "env": {
    "LOG_FILE": "/tmp/lsp4j-mcp.log",
    "LOG_LEVEL": "DEBUG"
  }
}
```

---

## Lombok Support

If the analyzed project uses Lombok, the server automatically searches for `lombok.jar` in:

1. Maven local repository (`~/.m2/repository/org/projectlombok/lombok/`)
2. Gradle caches (`~/.gradle/caches/modules-2/files-2.1/org.projectlombok/lombok/`)

When found, it injects `-javaagent:/path/to/lombok.jar` into the JDTLS JVM at startup. Without it, JDTLS reports false compilation errors on every Lombok annotation.

If auto-detection fails, set `LOMBOK_JAR` explicitly:

```json
{
  "env": {
    "LOMBOK_JAR": "/Users/me/.m2/repository/org/projectlombok/lombok/1.18.34/lombok-1.18.34.jar"
  }
}
```

## Gradle: Preventing `bin/` in Project Root

When JDTLS imports a Gradle project via Buildship it creates a `bin/` folder in the project root. To redirect it into `build/`, create `~/.gradle/init.d/eclipse-output-redirect.gradle`:

```groovy
import org.gradle.util.GradleVersion

allprojects {
    afterEvaluate { project ->
        if (project.plugins.hasPlugin('java')) {
            project.apply plugin: 'eclipse'

            if (GradleVersion.current() >= GradleVersion.version('8.1')) {
                project.eclipse.classpath.baseSourceOutputDir.set(
                        project.file("${project.buildDir}/classes/jdtls-bin")
                )
            }
        }
    }
}
```

Requires Gradle 8.1+. Delete any existing `bin/` manually after applying.

---

## License

MIT