package com.saloidvl.lsp4jmcp.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.saloidvl.lsp4jmcp.config.JdtlsSettingsSnapshot;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for JdtlsClient against a temporary copy of the Java fixture.
 * Run with {@code JDTLS_PATH=jdtls mvn test -Dtest=JdtlsClientIntegrationTest}.
 */
@EnabledIfEnvironmentVariable(named = "JDTLS_PATH", matches = ".+")
class JdtlsClientIntegrationTest {

    private static JdtlsClient client;
    private static Path workspaceRoot;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        String jdtlsCommand = System.getenv("JDTLS_PATH");
        Path projectRoot = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        workspaceRoot = tempDir.resolve("java-sample");
        copyFixture(
                projectRoot.resolve("src/it/fixtures/java-sample"),
                workspaceRoot);

        System.out.println("Starting JDTLS integration test");
        System.out.println("Workspace: " + workspaceRoot);
        System.out.println("JDTLS command: " + jdtlsCommand);

        JsonObject settings = JsonParser.parseString("""
                {"java":{"completion":{"maxResults":1}}}
                """).getAsJsonObject();
        JdtlsSettingsSnapshot snapshot = JdtlsSettingsSnapshot.of(
                settings, "real-jdtls-completion-limit");
        client = JdtlsClient.createAndInitializeAsync(
                workspaceRoot,
                jdtlsCommand,
                Optional.empty(),
                snapshot);

        System.out.println("Waiting for JDTLS to report ready status...");
        boolean ready = client.getLanguageClient().waitForReady(60, TimeUnit.SECONDS);
        System.out.println("JDTLS ready: " + ready);
        System.out.println("Current status: " + client.getLanguageClient().getCurrentStatus());
        
        assertThat(ready)
                .as("JDTLS must be ready before settings acceptance checks")
                .isTrue();
        Thread.sleep(2_000);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void workspace_isCreatedUnderJUnitTempDirectory() {
        assertThat(workspaceRoot).startsWith(tempDir);
    }

    @Test
    void findSymbols_shouldFindFixtureClass() throws Exception {
        List<? extends SymbolInformation> symbols = client.findWorkspaceSymbols("FormatterBase");

        assertThat(symbols)
            .isNotEmpty()
            .as("Should find FormatterBase class");

        boolean foundFixtureClass = symbols.stream()
            .anyMatch(s -> s.getName().equals("FormatterBase") && s.getKind() == SymbolKind.Class);

        assertThat(foundFixtureClass)
            .isTrue()
            .as("Should find a class named 'FormatterBase'");
    }

    @Test
    void findSymbols_shouldFindFixtureClassInCorrectLocation() throws Exception {
        List<? extends SymbolInformation> symbols = client.findWorkspaceSymbols("FormatterBase");

        SymbolInformation fixtureClass = symbols.stream()
            .filter(s -> s.getName().equals("FormatterBase"))
            .filter(s -> s.getKind() == SymbolKind.Class)
            .findFirst()
            .orElse(null);

        assertThat(fixtureClass)
            .isNotNull()
            .as("Should find 'FormatterBase' class");

        assertThat(fixtureClass.getLocation().getUri())
            .contains("FormatterBase.java")
            .as("The FormatterBase class should be located in FormatterBase.java");
    }

    @Test
    void findSymbols_shouldReturnCorrectSymbolKind() throws Exception {
        List<? extends SymbolInformation> symbols = client.findWorkspaceSymbols("*");

        List<? extends SymbolInformation> classes = symbols.stream()
            .filter(s -> s.getKind() == SymbolKind.Class)
            .toList();

        assertThat(classes)
            .isNotEmpty()
            .as("Should find at least one class symbol");

        assertThat(classes)
            .allMatch(s -> s.getKind() == SymbolKind.Class);
    }

    @Test
    void settings_shouldReachAndAffectRealJdtls() throws Exception {
        Path sourceDirectory = workspaceRoot.resolve(
                "src/main/java/com/example/sample");
        Path probe = Files.createTempFile(sourceDirectory, "JdtlsSettingsProbe", ".java");
        String fileName = probe.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());
        String source = String.join("\n",
                "package com.example.sample;",
                "",
                "class " + className + " {",
                "    void probe() {",
                "        System.",
                "    }",
                "}");

        String uri = probe.toUri().toString();
        TextDocumentIdentifier document = new TextDocumentIdentifier(uri);
        TextDocumentService textDocuments = client.getSessionManager()
                .requireSession()
                .languageServer()
                .getTextDocumentService();

        boolean opened = false;
        try {
            Files.writeString(probe, source, StandardCharsets.UTF_8);
            textDocuments.didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, "java", 1, source)));
            opened = true;
            CompletionParams params = new CompletionParams(
                    document,
                    new Position(4, "        System.".length()));

            Either<List<CompletionItem>, CompletionList> response =
                    textDocuments.completion(params).get(30, TimeUnit.SECONDS);
            List<CompletionItem> items = response.isLeft()
                    ? response.getLeft()
                    : response.getRight().getItems();
            List<CompletionItem> regularItems = items.stream()
                    .filter(item -> item.getKind() != CompletionItemKind.Snippet)
                    .toList();

            assertThat(regularItems).hasSize(1);
        } finally {
            try {
                if (opened) {
                    textDocuments.didClose(new DidCloseTextDocumentParams(document));
                }
            } finally {
                Files.deleteIfExists(probe);
            }
        }
    }

    private static void copyFixture(Path source, Path target) throws IOException {
        Path generatedOutput = source.resolve("target");
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.filter(p -> !p.startsWith(generatedOutput)).toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
