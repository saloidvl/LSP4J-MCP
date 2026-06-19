# LSP4J-MCP Server

An MCP server for Java codebases. It wraps [Eclipse JDT Language Server](https://github.com/eclipse-jdtls/eclipse.jdt.ls) (JDTLS) through [LSP4J](https://github.com/eclipse-lsp4j/lsp4j) and exposes Java navigation and symbol-search tools to any MCP-compatible client.

## Runtime Model

```text
MCP Client -> LauncherMain -> SupervisorMain -> RepoWorkerMain -> JDTLS -> Java workspace
```

Rules:

- one `supervisor` process per machine
- one `repo worker` per active repository
- one `JDTLS` process per active repository
- many MCP clients can share the same worker

This prevents every agent or sub-agent from spinning up its own heavy backend for the same repo.

IPC:

- MCP client → launcher: `stdio`
- launcher → supervisor: Unix domain socket
- launcher → repo worker: `127.0.0.1` TCP on an ephemeral port

## Tools

| Tool                       | Description                                                                              |
|----------------------------|------------------------------------------------------------------------------------------|
| `find_symbols`             | Search for Java symbols (classes, methods, fields) by name                               |
| `find_references`          | Find all references to a symbol at a given file location                                 |
| `find_definition`          | Go to the definition of a symbol at a given file location                                |
| `document_symbols`         | List all symbols declared in a Java file                                                 |
| `find_implementations`     | Find all implementations of an interface method or abstract method                       |
| `find_method_declarations` | Find interfaces or classes that declare a method with the given name                     |
| `find_incoming_calls`      | Find all call sites where a method is called from                                        |
| `find_outgoing_calls`      | Find all methods called by a given method                                                |
| `get_hover`                | Get type signature and Javadoc for the symbol at a given position                        |
| `resolve_stack_trace`      | Resolve a Java stack frame to its source file and line number                            |
| `get_diagnostics`          | Return cached compiler diagnostics; supports per-file or workspace summary               |
| `refresh_diagnostics`      | Trigger a full workspace build to refresh the diagnostics cache                          |
| `indexing_status`          | Report current JDTLS health (starting / indexing / ready / degraded / failed)           |
| `restart_jdtls`            | Soft-restart JDTLS without deleting workspace state                                      |
| `reindex_workspace`        | Clean-restart JDTLS and force a full reimport and reindex                                |
| `decompile_class`          | Decompile a dependency class file (jdt:// or jar: URI from `find_definition`) to source  |
| `get_type_hierarchy`       | Get supertypes and subtypes for the type at a given position                             |
| `get_type_definition`      | Resolve the declared type of the symbol at a given position                              |
| `get_projects`             | List all Java projects in the workspace                                                  |
| `get_classpath`            | Get source directories and JAR dependencies for the project containing a given file      |

## Requirements

- Java 21+
- macOS or Linux (Windows is untested)
- JDTLS installed and available in `PATH`

## Install JDTLS

**macOS (Homebrew):**

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

This produces a shaded JAR at `target/lsp4j-mcp-<version>.jar`.

To run tests (unit tests only, no JDTLS required):

```bash
mvn test
```

## Run

```bash
java -jar target/lsp4j-mcp-<version>.jar /path/to/java/workspace jdtls
```

Arguments:

1. Absolute path to the Java workspace root
2. JDTLS command — `jdtls` if it is on your `PATH`, or a full path otherwise

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

### Generic MCP Client

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

## Logging

Logs are written to `LOG_FILE` (default: `/tmp/lsp4j-mcp.log`). A separate `.error` file captures ERROR-level entries only. Both files rotate at 50 MB / 10 MB respectively.

`stdout` is reserved for MCP protocol traffic — never redirect logs there.

**Environment variables:**

| Variable    | Default                 | Description                                              |
|-------------|-------------------------|----------------------------------------------------------|
| `LOG_FILE`  | `/tmp/lsp4j-mcp.log`    | Path to the main log file                                |
| `LOG_LEVEL` | `INFO`                  | Logback level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |

Example — enable debug logging:

```json
{
  "env": {
    "LOG_FILE": "/tmp/lsp4j-mcp.log",
    "LOG_LEVEL": "DEBUG"
  }
}
```

## Lombok Support

If the analyzed project uses Lombok, the server automatically searches for `lombok.jar` in:

1. Maven local repository (`~/.m2/repository/org/projectlombok/lombok/`)
2. Gradle caches (`~/.gradle/caches/modules-2/files-2.1/org.projectlombok/lombok/`)

When found, it injects `-javaagent:/path/to/lombok.jar` into the JDTLS JVM at startup.

If auto-detection fails, set `LOMBOK_JAR` explicitly:

```json
{
  "env": {
    "LOMBOK_JAR": "/Users/me/.m2/repository/org/projectlombok/lombok/1.18.34/lombok-1.18.34.jar"
  }
}
```

## Gradle: Preventing `bin/` in Project Root

When JDTLS imports a Gradle project via Buildship it creates a `bin/` folder in the project root. To redirect it into `build/` (which IDEs exclude automatically), create `~/.gradle/init.d/eclipse-output-redirect.gradle`:

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

Requires Gradle 8.1+. Delete any existing `bin/` manually after applying — it will not be recreated.

## License

MIT
