package com.saloidvl.lsp4jmcp.server;

import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.saloidvl.lsp4jmcp.client.LombokSupport;
import com.saloidvl.lsp4jmcp.runtime.BuildInfo;
import io.modelcontextprotocol.server.McpSyncServer;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP Server that provides Java IDE features via JDTLS.
 *
 * Usage: java -jar lsp4j-mcp.jar <workspace-path> <jdtls-command>
 */
public class McpServerMain {
    private static final Logger LOG = LoggerFactory.getLogger(McpServerMain.class);

    private static final String SERVER_NAME = "java-lsp";
    private static final String SERVER_VERSION = BuildInfo.version();

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java -jar lsp4j-mcp.jar <workspace-path> <jdtls-command>");
            System.err.println("Example: java -jar lsp4j-mcp.jar /path/to/project jdtls");
            System.exit(1);
        }

        Path workspacePath = Path.of(args[0]).toAbsolutePath();
        String jdtlsCommand = args[1];

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            if (ex instanceof OutOfMemoryError) {
                LOG.error("OUT OF MEMORY in thread '{}': {}. Increase -Xmx in .mcp.json", thread.getName(), ex.getMessage());
            } else {
                LOG.error("Uncaught exception in thread '{}'", thread.getName(), ex);
            }
        });

        Runtime rt = Runtime.getRuntime();
        LOG.info("Starting Java LSP MCP Server");
        LOG.info("Workspace: {}", workspacePath);
        LOG.info("JDTLS command: {}", jdtlsCommand);
        LOG.info("JVM heap: max={}MB, total={}MB, free={}MB",
            rt.maxMemory() / 1024 / 1024,
            rt.totalMemory() / 1024 / 1024,
            rt.freeMemory() / 1024 / 1024);

        try {
            run(workspacePath, jdtlsCommand);
        } catch (Exception e) {
            LOG.error("Fatal error", e);
            System.exit(1);
        }
    }

    static String serverName() {
        return SERVER_NAME;
    }

    static String serverVersion() {
        return SERVER_VERSION;
    }

    private static void run(Path workspacePath, String jdtlsCommand) throws Exception {
        Optional<Path> lombokJar = LombokSupport.detectAndFind(workspacePath);
        JdtlsClient jdtlsClient = JdtlsClient.createAndInitialize(workspacePath, jdtlsCommand, lombokJar);

        McpSyncServer server = JavaMcpServer.create(System.in, System.out, jdtlsClient, workspacePath);

        LOG.info("MCP Server started with 6 tools");

        // Setup shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down...");
            jdtlsClient.close();
            try {
                server.close();
            } catch (Exception e) {
                LOG.warn("Error closing server", e);
            }
        }));

        // Block until interrupted
        Thread.currentThread().join();
    }
}
