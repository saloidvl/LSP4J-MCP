package com.saloidvl.lsp4jmcp.server;

import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.saloidvl.lsp4jmcp.tools.JavaTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Set;

public final class JavaMcpServer {
    private static final Set<String> REGISTERED_TOOL_NAMES = Set.of(
        "find_symbols",
        "find_references",
        "find_definition",
        "document_symbols",
        "indexing_status",
        "find_interfaces_with_method",
        "restart_jdtls",
        "reindex_workspace",
        "find_implementations",
        "get_hover",
        "find_incoming_calls",
        "find_outgoing_calls",
        "get_diagnostics",
        "refresh_diagnostics",
        "resolve_stack_trace"
    );

    private JavaMcpServer() {
    }

    public static McpSyncServer create(
            InputStream input,
            OutputStream output,
            JdtlsClient jdtlsClient,
            Path workspacePath) throws IOException {
        JavaTools javaTools = new JavaTools(jdtlsClient, workspacePath);
        McpJsonMapper objectMapper = McpJsonDefaults.getMapper();
        var transportProvider = new StdioServerTransportProvider(objectMapper, input, output);

        return McpServer.sync(transportProvider)
            .serverInfo(McpServerMain.serverName(), McpServerMain.serverVersion())
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())

            .toolCall(
                Tool.builder()
                    .name("find_symbols")
                    .description("Search for Java symbols (classes, methods, fields) by name")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "query": { "type": "string", "description": "The symbol name or pattern to search for" }
                      },
                      "required": ["query"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(javaTools.findSymbols((String) request.arguments().get("query")))
                        .build())

            .toolCall(
                Tool.builder()
                    .name("find_references")
                    .description("Find all references to a symbol at a given file location")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "file": { "type": "string", "description": "Path to the Java file" },
                        "line": { "type": "integer", "description": "Line number (1-based)" },
                        "character": { "type": "integer", "description": "Character/column position (1-based)" }
                      },
                      "required": ["file", "line", "character"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) -> {
                    String file = (String) request.arguments().get("file");
                    int line = ((Number) request.arguments().get("line")).intValue() - 1;
                    int character = ((Number) request.arguments().get("character")).intValue() - 1;
                    return CallToolResult.builder()
                        .addTextContent(javaTools.findReferences(file, line, character))
                        .build();
                })

            .toolCall(
                Tool.builder()
                    .name("find_definition")
                    .description("Go to the definition of a symbol at a given file location")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "file": { "type": "string", "description": "Path to the Java file" },
                        "line": { "type": "integer", "description": "Line number (1-based)" },
                        "character": { "type": "integer", "description": "Character/column position (1-based)" }
                      },
                      "required": ["file", "line", "character"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) -> {
                    String file = (String) request.arguments().get("file");
                    int line = ((Number) request.arguments().get("line")).intValue() - 1;
                    int character = ((Number) request.arguments().get("character")).intValue() - 1;
                    return CallToolResult.builder()
                        .addTextContent(javaTools.findDefinition(file, line, character))
                        .build();
                })

            .toolCall(
                Tool.builder()
                    .name("document_symbols")
                    .description("Get all symbols (classes, methods, fields) defined in a Java file")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "file": { "type": "string", "description": "Path to the Java file" }
                      },
                      "required": ["file"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(javaTools.getDocumentSymbols((String) request.arguments().get("file")))
                        .build())

            .toolCall(
                Tool.builder()
                    .name("indexing_status")
                    .description("Get the current JDTLS health status. Values: starting (wait), indexing (results may be partial), ready (normal operation), recovering_restart (retry later), recovering_reindex (expect a longer delay), degraded (manual recovery recommended), failed (call restart_jdtls or reindex_workspace). Use this tool to decide whether to wait, retry, or trigger recovery.")
                    .inputSchema(objectMapper.readValue("""
                    {"type": "object", "properties": {}}
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(jdtlsClient.getIndexingStatus())
                        .build())

            .toolCall(
                Tool.builder()
                    .name("find_interfaces_with_method")
                    .description("Find all interfaces that contain a method with the given name")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "method_name": { "type": "string", "description": "The method name to search for" }
                      },
                      "required": ["method_name"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(javaTools.findInterfacesWithMethod((String) request.arguments().get("method_name")))
                        .build())

            .toolCall(
                Tool.builder()
                    .name("restart_jdtls")
                    .description("Soft-restart JDTLS without deleting workspace state.")
                    .inputSchema(objectMapper.readValue("""
                    {"type": "object", "properties": {}}
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) -> CallToolResult.builder()
                    .addTextContent(runRecovery(() -> jdtlsClient.restartJdtls()))
                    .build())

            .toolCall(
                Tool.builder()
                    .name("reindex_workspace")
                    .description("Clean-restart JDTLS after deleting workspace state to force a full reimport and reindex.")
                    .inputSchema(objectMapper.readValue("""
                    {"type": "object", "properties": {}}
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) -> CallToolResult.builder()
                    .addTextContent(runRecovery(() -> jdtlsClient.reindexWorkspace()))
                    .build())

            .toolCall(
                Tool.builder()
                    .name("find_implementations")
                    .description("Find all implementations of an interface method or abstract method at the given position.")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "file": { "type": "string", "description": "Absolute path to the Java file" },
                        "line": { "type": "integer", "description": "1-based line number" },
                        "character": { "type": "integer", "description": "1-based character offset" }
                      },
                      "required": ["file", "line", "character"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(javaTools.findImplementations(
                            (String) request.arguments().get("file"),
                            ((Number) request.arguments().get("line")).intValue(),
                            ((Number) request.arguments().get("character")).intValue()))
                        .build())

            .toolCall(
                Tool.builder()
                    .name("get_hover")
                    .description("Get hover information, such as type signature and Javadoc, for the symbol at the given position.")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "file": { "type": "string", "description": "Absolute path to the Java file" },
                        "line": { "type": "integer", "description": "1-based line number" },
                        "character": { "type": "integer", "description": "1-based character offset" }
                      },
                      "required": ["file", "line", "character"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(javaTools.getHover(
                            (String) request.arguments().get("file"),
                            ((Number) request.arguments().get("line")).intValue(),
                            ((Number) request.arguments().get("character")).intValue()))
                        .build())

            .toolCall(
                Tool.builder()
                    .name("find_incoming_calls")
                    .description("Find all call sites where the method at the given position is called from.")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "file": { "type": "string", "description": "Absolute path to the Java file" },
                        "line": { "type": "integer", "description": "1-based line number" },
                        "character": { "type": "integer", "description": "1-based character offset" }
                      },
                      "required": ["file", "line", "character"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(javaTools.findIncomingCalls(
                            (String) request.arguments().get("file"),
                            ((Number) request.arguments().get("line")).intValue(),
                            ((Number) request.arguments().get("character")).intValue()))
                        .build())

            .toolCall(
                Tool.builder()
                    .name("find_outgoing_calls")
                    .description("Find all methods called by the method at the given position.")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "file": { "type": "string", "description": "Absolute path to the Java file" },
                        "line": { "type": "integer", "description": "1-based line number" },
                        "character": { "type": "integer", "description": "1-based character offset" }
                      },
                      "required": ["file", "line", "character"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(javaTools.findOutgoingCalls(
                            (String) request.arguments().get("file"),
                            ((Number) request.arguments().get("line")).intValue(),
                            ((Number) request.arguments().get("character")).intValue()))
                        .build())

            .toolCall(
                Tool.builder()
                    .name("get_diagnostics")
                    .description("Return cached diagnostics from the last JDTLS build. Use file for one file, or summary_only for per-file counts.")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "summary_only": { "type": "boolean", "description": "Return only error/warning counts per file" },
                        "file": { "type": "string", "description": "Absolute path to filter to a single file" }
                      }
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) -> {
                    Boolean summaryOnly = (Boolean) request.arguments().get("summary_only");
                    String file = (String) request.arguments().get("file");
                    return CallToolResult.builder()
                        .addTextContent(javaTools.getDiagnostics(summaryOnly, file))
                        .build();
                })

            .toolCall(
                Tool.builder()
                    .name("refresh_diagnostics")
                    .description("Trigger a full workspace build and wait for completion so cached diagnostics can refresh.")
                    .inputSchema(objectMapper.readValue("""
                    {"type": "object", "properties": {}}
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(javaTools.refreshDiagnostics())
                        .build())

            .toolCall(
                Tool.builder()
                    .name("resolve_stack_trace")
                    .description("Resolve a Java stack frame to its source file and line number.")
                    .inputSchema(objectMapper.readValue("""
                    {
                      "type": "object",
                      "properties": {
                        "stack_frame": {
                          "type": "string",
                          "description": "Full Java stack frame line, for example 'at com.example.Foo.bar(Foo.java:42)'"
                        }
                      },
                      "required": ["stack_frame"]
                    }
                    """, McpSchema.JsonSchema.class))
                    .build(),
                (exchange, request) ->
                    CallToolResult.builder()
                        .addTextContent(javaTools.resolveStackTrace(
                            (String) request.arguments().get("stack_frame")))
                        .build())

            .build();
    }

    private static String runRecovery(RecoveryAction action) {
        try {
            return action.run();
        } catch (Exception ex) {
            return "status=failed; message=" + ex.getMessage();
        }
    }

    @FunctionalInterface
    private interface RecoveryAction {
        String run() throws Exception;
    }

    static Set<String> registeredToolNames() {
        return REGISTERED_TOOL_NAMES;
    }
}
