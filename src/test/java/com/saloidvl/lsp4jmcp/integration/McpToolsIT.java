package com.saloidvl.lsp4jmcp.integration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.skyscreamer.jsonassert.Customization;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.comparator.CustomComparator;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "JDTLS_PATH", matches = ".+")
class McpToolsIT {

    private static final Duration INDEXING_TIMEOUT = Duration.ofSeconds(60);
    private static final CustomComparator IGNORE_CACHE_TS = new CustomComparator(
        JSONCompareMode.NON_EXTENSIBLE,
        new Customization("cache_updated_at_ms", (e, a) -> e != null && a != null),
        new Customization("build_duration_ms", (e, a) -> a instanceof Number),
        new Customization("timestamp", (e, a) -> !e.toString().isBlank() && !a.toString().isBlank())
    );

    private final StringBuilder serverErrors = new StringBuilder();
    private McpSyncClient client;
    private Path fixturePath;
    private Path isolatedSocketDir;
    private Path buildDir;

    @BeforeAll
    void setUp() throws Exception {
        fixturePath = fixturePath();
        Path logPath = Path.of(System.getProperty("user.dir")).resolve("logs/test-integration.log").toAbsolutePath();
        isolatedSocketDir = Files.createTempDirectory(Path.of("/tmp"), "mcp-it-");
        cleanFixtureArtifacts(fixturePath);
        Path mainProjectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        ensureLombokJar(mainProjectDir);

        String jdtlsPath = System.getenv("JDTLS_PATH");
        buildDir = Files.createTempDirectory("lsp4j-mcp-build-");
        final Path builtJar = buildJar(buildDir);

        ServerParameters params = ServerParameters.builder("java")
            .env(Map.of(
                "LOG_FILE", logPath.toString(),
                "LSP4J_MCP_SOCKET_DIR", isolatedSocketDir.toString(),
                "LOG_LEVEL", "DEBUG"
            ))
            .args("-jar", builtJar.toString(), fixturePath.toString(), jdtlsPath)
            .build();
        StdioClientTransport transport =
            new StdioClientTransport(params, McpJsonDefaults.getMapper());
        transport.setStdErrorHandler(error -> {
            synchronized (serverErrors) {
                serverErrors.append(error).append(System.lineSeparator());
            }
        });

        client = McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(30))
            .initializationTimeout(Duration.ofSeconds(90))
            .build();
        client.initialize();

        ListToolsResult tools = client.listTools();
        assertThat(tools.tools()).hasSize(20);

        pollUntilReady(INDEXING_TIMEOUT);
        callTool("refresh_diagnostics", Map.of());
    }

    @AfterAll
    void tearDown() throws IOException {
        if (client != null)
            client.closeGracefully();
        if (isolatedSocketDir != null)
            deleteRecursively(isolatedSocketDir);
        if (buildDir != null)
            deleteRecursively(buildDir);
    }

    @Test
    void indexingStatus_returnsReady() {
        assertThat(callTool("indexing_status", Map.of())).contains("status=ready");
    }

    @Test
    void findSymbols_findsGreeter() throws Exception {
        String expected = getFromFile("integration/find_symbols_greeter.json");
        String actual = callTool("find_symbols", Map.of("query", "Greeter"));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void documentSymbols_listsGreeterImplSymbols() throws Exception {
        String expected = getFromFile("integration/document_symbols_greeter_impl.json");
        String actual = callTool("document_symbols", Map.of("file", fixtureFile("GreeterImpl.java")));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findReferences_findsGreetCallSites() throws Exception {
        String expected = getFromFile("integration/find_references_greet.json");
        String actual = callTool(
            "find_references", Map.of(
                "file", fixtureFile("GreeterImpl.java"), "line", 6, "character", 20));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findDefinition_resolvesGreet() throws Exception {
        String expected = getFromFile("integration/find_definition_greet.json");
        String actual = callTool(
            "find_definition", Map.of(
                "file", fixtureFile("App.java"), "line", 7, "character", 34));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findDefinition_resolvesGreetBySymbol() throws Exception {
        // App.java line 7: String message = greeter.greet("World");
        String expected = getFromFile("integration/find_definition_greet_by_symbol.json");
        String actual = callTool(
            "find_definition", Map.of(
                "file", fixtureFile("App.java"),
                "line", 7,
                "symbol", "greet"
            ));
        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findDefinition_badPosition_returnsPositionNotResolved() throws Exception {
        // character: 1 on line 7 is leading whitespace — not an identifier
        String expected = getFromFile("integration/find_definition_bad_position.json");
        String actual = callTool(
            "find_definition", Map.of(
                "file", fixtureFile("App.java"),
                "line", 7,
                "character", 1
            ));
        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void getTypeDefinition_resolvesGreeterType() throws Exception {
        String expected = getFromFile("integration/get_type_definition_greeter.json");
        String actual = callTool(
            "get_type_definition", Map.of(
                "file", fixtureFile("App.java"), "line", 6, "character", 17));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findMethodDeclarations_findsGreeterInterface() throws Exception {
        String expected = getFromFile("integration/find_method_declarations_greet.json");
        String actual = callTool("find_method_declarations", Map.of("method_name", "greet"));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findMethodDeclarations_fallback_findsDataPortLoad() throws Exception {
        String expected = getFromFile("integration/find_method_declarations_load.json");
        String actual = callTool("find_method_declarations", Map.of("method_name", "load"));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findImplementations_findsGreeterImpl() throws Exception {
        String expected = getFromFile("integration/find_implementations_greet.json");
        String actual = callTool(
            "find_implementations", Map.of(
                "file", fixtureFile("Greeter.java"), "line", 5, "character", 13));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void getTypeHierarchy_returnsGreeterHierarchy() throws Exception {
        String expected = getFromFile("integration/get_type_hierarchy_greeter.json");
        String actual = callTool(
            "get_type_hierarchy", Map.of(
                "file", fixtureFile("Greeter.java"), "line", 4, "character", 18));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findIncomingCalls_findsCallersOfGreeterImplGreet() throws Exception {
        String expected = getFromFile("integration/find_incoming_calls_greet.json");
        String actual = callTool(
            "find_incoming_calls", Map.of(
                "file", fixtureFile("GreeterImpl.java"), "line", 6, "character", 20));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findOutgoingCalls_findsMessageHelperCall() throws Exception {
        String expected = getFromFile("integration/find_outgoing_calls_greet.json");
        String actual = callTool(
            "find_outgoing_calls", Map.of(
                "file", fixtureFile("GreeterImpl.java"), "line", 6, "character", 20));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findIncomingCalls_bySymbol() throws Exception {
        // GreeterImpl.java line 6: public String greet(String name) {
        // Same position as existing findIncomingCalls test — reuses existing golden file
        String expected = getFromFile("integration/find_incoming_calls_greet.json");
        String actual = callTool(
            "find_incoming_calls", Map.of(
                "file", fixtureFile("GreeterImpl.java"),
                "line", 6,
                "symbol", "greet"
            ));
        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void findOutgoingCalls_bySymbol() throws Exception {
        // GreeterImpl.java line 6: public String greet(String name) {
        // Same position as existing findOutgoingCalls test — reuses existing golden file
        String expected = getFromFile("integration/find_outgoing_calls_greet.json");
        String actual = callTool(
            "find_outgoing_calls", Map.of(
                "file", fixtureFile("GreeterImpl.java"),
                "line", 6,
                "symbol", "greet"
            ));
        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void getHover_returnsInfoForBuildMessageCall() throws Exception {
        String expected = getFromFile("integration/get_hover_greet_param.json");
        String actual = callTool(
            "get_hover", Map.of(
                "file", fixtureFile("GreeterImpl.java"), "line", 7, "character", 30));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void getHover_bySymbol_returnsContent() throws Exception {
        String expected = getFromFile("integration/get_hover_greet_symbol.json");
        String actual = callTool(
            "get_hover", Map.of(
                "file", fixtureFile("GreeterImpl.java"),
                "line", 6,
                "symbol", "greet"
            ));
        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void getProjects_listsWorkspaceProject() throws Exception {
        String expected = getFromFile("integration/get_projects.json");
        String actual = callTool("get_projects", Map.of());

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void getClasspath_returnsProjectClasspath() throws Exception {
        String expected = getFromFile("integration/get_classpath.json");
        String actual = callTool("get_classpath", Map.of("file", fixtureFile("App.java")));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void resolveStackTrace_resolvesGreeterImplGreet() throws Exception {
        String expected = getFromFile("integration/resolve_stack_trace_greeter.json");
        String actual = callTool(
            "resolve_stack_trace", Map.of(
                "stack_frame", "at com.example.sample.GreeterImpl.greet(GreeterImpl.java:7)"));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    void getDiagnostics_summaryHasNoErrors() throws Exception {
        String expected = getFromFile("integration/get_diagnostics_summary.json");
        String actual = callTool("get_diagnostics", Map.of("summary_only", true));
        JSONAssert.assertEquals(expected, actual, IGNORE_CACHE_TS);
    }

    @Test
    void getDiagnostics_withFile_returnsDeprecationWarningForGreeterImpl() throws Exception {
        String expected = getFromFile("integration/get_diagnostics_greeter_impl.json");
        String actual = callTool("get_diagnostics", Map.of("file", fixtureFile("GreeterImpl.java")));
        JSONAssert.assertEquals(expected, actual, IGNORE_CACHE_TS);
    }

    @Test
    void getDiagnostics_lombokConsumer_hasNoErrors() throws Exception {
        String expected = getFromFile("integration/get_diagnostics_lombok_consumer.json");
        String actual = callTool("get_diagnostics", Map.of("file", fixtureFile("LombokConsumer.java")));
        JSONAssert.assertEquals(expected, actual, IGNORE_CACHE_TS);
    }

    @Test
    @Order(Integer.MAX_VALUE - 2)
    void refreshDiagnostics_returnsOk() throws Exception {
        String expected = getFromFile("integration/refresh_diagnostics.json");
        String actual = callTool("refresh_diagnostics", Map.of());

        JSONAssert.assertEquals(expected, actual, IGNORE_CACHE_TS);
    }

    @Test
    @Order(Integer.MAX_VALUE - 1)
    void restartJdtls_becomesReady() throws Exception {
        // restart is async: process restarts, returns status=indexing, then becomes ready
        String result = callTool("restart_jdtls", Map.of());
        assertThat(result).containsAnyOf("status=ready", "status=indexing");
        pollUntilReady(INDEXING_TIMEOUT);
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void reindexWorkspace_completesAndBecomesReady() throws Exception {
        // reindex_workspace now blocks until CLEAN+FULL build finishes (no process restart).
        // Returns status=ready if ServiceReady notification was already processed,
        // or status=indexing if it arrives slightly after the build response.
        String result = callTool("reindex_workspace", Map.of());
        assertThat(result).containsAnyOf("status=ready", "status=indexing");
        pollUntilReady(INDEXING_TIMEOUT);
    }

    private String callTool(String name, Map<String, Object> arguments) {
        CallToolResult result = client.callTool(
            CallToolRequest.builder().name(name).arguments(arguments).build());
        assertThat(result.isError()).as("MCP tool result for %s", name).isNotEqualTo(true);
        return result.content().stream()
            .filter(TextContent.class::isInstance)
            .map(c -> ((TextContent) c).text())
            .findFirst().orElse("");
    }

    private String getFromFile(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null)
                throw new IOException("Resource not found: " + path);
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return content.replace("$FIXTURE", fixturePath.toString());
        }
    }

    private void pollUntilReady(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String lastStatus = "";
        while (System.nanoTime() < deadline) {
            lastStatus = callTool("indexing_status", Map.of());
            if (lastStatus.contains("status=ready"))
                return;
            Thread.sleep(300);
        }
        throw new AssertionError(
            "JDTLS did not reach ready within " + timeout
            + "; last status: " + lastStatus
            + "; server stderr:" + System.lineSeparator() + serverErrors());
    }

    private String fixtureFile(String fileName) {
        return fixturePath.resolve("src/main/java/com/example/sample").resolve(fileName).toString();
    }

    private Path fixturePath() {
        return Path.of(System.getProperty("user.dir"))
            .resolve("src/it/fixtures/java-sample").toAbsolutePath();
    }

    private String serverErrors() {
        synchronized (serverErrors) {
            return serverErrors.toString();
        }
    }

    private static void ensureLombokJar(Path projectDir) throws IOException, InterruptedException {
        // Download lombok.jar to ~/.m2 so LombokSupport.findJar() can locate it.
        // Uses dependency:get from the main project dir to avoid creating Eclipse project files
        // in the fixture workspace (which would change how JDTLS analyzes it).
        Process process = new ProcessBuilder(
            "mvn", "dependency:get", "-Dartifact=org.projectlombok:lombok:1.18.46:jar", "-q"
        ).directory(projectDir.toFile())
         .redirectErrorStream(true)
         .start();
        process.waitFor();
    }

    private static Path buildJar(Path buildDir) throws IOException, InterruptedException {
        Path projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Files.createDirectories(projectDir.resolve("logs"));
        Process process = new ProcessBuilder("mvn", "package", "-DskipTests", "-q")
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(projectDir.resolve("logs/test-integration-build.log").toFile())
            .start();
        int exit = process.waitFor();
        if (exit != 0)
            throw new IOException("mvn package failed with exit code " + exit
                                  + "; see logs/test-integration-build.log");
        Path built;
        try (Stream<Path> stream = Files.list(projectDir.resolve("target"))) {
            built = stream
                .filter(path -> path.getFileName().toString().startsWith("lsp4j-mcp-"))
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .filter(path -> !path.getFileName().toString().startsWith("original-"))
                .findFirst()
                .orElseThrow(() -> new IOException("Built jar not found in target/"));
        }
        Path copy = buildDir.resolve(built.getFileName().toString());
        Files.copy(built, copy);
        return copy;
    }

    private static void cleanFixtureArtifacts(Path fixturePath) throws IOException {
        deleteRecursively(fixturePath.resolve("target"));
        deleteRecursively(fixturePath.resolve(".settings"));
        Files.deleteIfExists(fixturePath.resolve(".classpath"));
        Files.deleteIfExists(fixturePath.resolve(".project"));
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path))
            return;
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
        }
    }
}
