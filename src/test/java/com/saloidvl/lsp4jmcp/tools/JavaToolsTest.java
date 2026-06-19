package com.saloidvl.lsp4jmcp.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.saloidvl.lsp4jmcp.client.DiagnosticsCache;
import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.saloidvl.lsp4jmcp.client.TypeHierarchyData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for JavaTools.
 * Tests cover symbol search, reference finding, and result formatting.
 */
@ExtendWith(MockitoExtension.class)
class JavaToolsTest {

    @Mock
    private JdtlsClient jdtlsClient;

    private JavaTools javaTools;
    private Gson gson;

    @BeforeEach
    void setUp() {
        javaTools = new JavaTools(jdtlsClient, Path.of("/test/workspace"));
        gson = new Gson();
    }

    // ============================================
    // findSymbols tests
    // ============================================

    @Test
    void findSymbols_returnsMatchingWorkspaceSymbols() throws Exception {
        // Given - workspace symbol returns a class
        SymbolInformation classSymbol = new SymbolInformation(
            "KeywordRepository",
            SymbolKind.Class,
            new Location("file:///test/Repo.java", new Range(new Position(0, 0), new Position(100, 0))),
            "com.test"
        );
        doReturn(List.of(classSymbol)).when(jdtlsClient).findWorkspaceSymbols("KeywordRepository");
        doReturn(List.of()).when(jdtlsClient).getDocumentSymbols(anyString());

        // When
        String result = javaTools.findSymbols("KeywordRepository");

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("query").getAsString()).isEqualTo("KeywordRepository");
        assertThat(json.get("count").getAsInt()).isGreaterThanOrEqualTo(1);
        
        JsonArray symbols = json.getAsJsonArray("symbols");
        assertThat(symbols.size()).isGreaterThanOrEqualTo(1);
        JsonObject firstSymbol = symbols.get(0).getAsJsonObject();
        assertThat(firstSymbol.get("name").getAsString()).isEqualTo("KeywordRepository");
        assertThat(firstSymbol.get("kind").getAsString()).isEqualTo("Class");
        assertThat(firstSymbol.get("container").getAsString()).isEqualTo("com.test");
    }

    @Test
    void findSymbols_returnsMatchingDocumentSymbols() throws Exception {
        // Given - workspace symbol returns a class
        SymbolInformation classSymbol = new SymbolInformation(
            "KeywordRepository",
            SymbolKind.Class,
            new Location("file:///test/Repo.java", new Range(new Position(0, 0), new Position(100, 0))),
            "com.test"
        );
        doReturn(List.of(classSymbol)).when(jdtlsClient).findWorkspaceSymbols("findByName");
        
        // Document symbols include a method matching the query
        DocumentSymbol methodSymbol = new DocumentSymbol();
        methodSymbol.setName("findByName");
        methodSymbol.setKind(SymbolKind.Method);
        methodSymbol.setRange(new Range(new Position(10, 4), new Position(10, 20)));
        methodSymbol.setSelectionRange(new Range(new Position(10, 4), new Position(10, 20)));
        methodSymbol.setDetail("String");
        
        doReturn(List.of(methodSymbol)).when(jdtlsClient).getDocumentSymbols("file:///test/Repo.java");

        // When
        String result = javaTools.findSymbols("findByName");

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("query").getAsString()).isEqualTo("findByName");
        assertThat(json.get("count").getAsInt()).isEqualTo(2);

        JsonArray symbols = json.getAsJsonArray("symbols");
        JsonObject methodResult = symbols.get(1).getAsJsonObject();
        assertThat(methodResult.get("name").getAsString()).isEqualTo("findByName");
        assertThat(methodResult.get("kind").getAsString()).isEqualTo("Method");
        assertThat(methodResult.get("line").getAsInt()).isEqualTo(11); // 1-based
    }

    @Test
    void findSymbols_searchesNestedDocumentSymbols() throws Exception {
        // Given - class with nested method
        SymbolInformation classSymbol = new SymbolInformation(
            "OuterClass",
            SymbolKind.Class,
            new Location("file:///test/Outer.java", new Range(new Position(0, 0), new Position(100, 0))),
            "com.test"
        );
        doReturn(List.of(classSymbol)).when(jdtlsClient).findWorkspaceSymbols("nestedMethod");
        
        // Create nested structure: Class -> InnerClass -> nestedMethod
        DocumentSymbol nestedMethod = new DocumentSymbol();
        nestedMethod.setName("nestedMethod");
        nestedMethod.setKind(SymbolKind.Method);
        nestedMethod.setRange(new Range(new Position(20, 8), new Position(25, 8)));
        nestedMethod.setSelectionRange(new Range(new Position(20, 8), new Position(20, 20)));
        
        DocumentSymbol innerClass = new DocumentSymbol();
        innerClass.setName("InnerClass");
        innerClass.setKind(SymbolKind.Class);
        innerClass.setRange(new Range(new Position(15, 4), new Position(30, 4)));
        innerClass.setSelectionRange(new Range(new Position(15, 4), new Position(15, 15)));
        innerClass.setChildren(List.of(nestedMethod));
        
        DocumentSymbol outerClass = new DocumentSymbol();
        outerClass.setName("OuterClass");
        outerClass.setKind(SymbolKind.Class);
        outerClass.setRange(new Range(new Position(0, 0), new Position(100, 0)));
        outerClass.setSelectionRange(new Range(new Position(0, 0), new Position(0, 10)));
        outerClass.setChildren(List.of(innerClass));
        
        doReturn(List.of(outerClass)).when(jdtlsClient).getDocumentSymbols("file:///test/Outer.java");

        // When
        String result = javaTools.findSymbols("nestedMethod");

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(2);

        JsonObject foundMethod = json.getAsJsonArray("symbols").get(1).getAsJsonObject();
        assertThat(foundMethod.get("name").getAsString()).isEqualTo("nestedMethod");
    }

    @Test
    void findSymbols_performsCaseInsensitiveSearch() throws Exception {
        // Given
        SymbolInformation classSymbol = new SymbolInformation(
            "MyClass",
            SymbolKind.Class,
            new Location("file:///test/MyClass.java", new Range(new Position(0, 0), new Position(50, 0))),
            "com.test"
        );
        doReturn(List.of(classSymbol)).when(jdtlsClient).findWorkspaceSymbols("MYMETHOD");
        
        DocumentSymbol method = new DocumentSymbol();
        method.setName("myMethod");
        method.setKind(SymbolKind.Method);
        method.setRange(new Range(new Position(10, 4), new Position(15, 4)));
        method.setSelectionRange(new Range(new Position(10, 4), new Position(10, 12)));
        
        doReturn(List.of(method)).when(jdtlsClient).getDocumentSymbols("file:///test/MyClass.java");

        // When - search with uppercase
        String result = javaTools.findSymbols("MYMETHOD");

        // Then - should find the lowercase method
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(2);
    }

    @Test
    void findSymbols_returnsEmptyListWhenNoMatches() throws Exception {
        // Given - no workspace symbols and no document symbols match
        doReturn(List.of()).when(jdtlsClient).findWorkspaceSymbols("nonexistent");

        // When
        String result = javaTools.findSymbols("nonexistent");

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(0);
        assertThat(json.getAsJsonArray("symbols")).isEmpty();
    }

    @Test
    void findSymbols_handlesExceptionGracefully() throws Exception {
        // Given
        doThrow(new RuntimeException("Connection failed"))
            .when(jdtlsClient).findWorkspaceSymbols(anyString());

        // When
        String result = javaTools.findSymbols("test");

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.has("error")).isTrue();
        assertThat(json.get("error").getAsString()).contains("Connection failed");
    }

    @Test
    void findSymbols_handlesDocumentSymbolsException() throws Exception {
        // Given - workspace symbols work but document symbols fail for one file
        SymbolInformation classSymbol = new SymbolInformation(
            "MyClass",
            SymbolKind.Class,
            new Location("file:///test/MyClass.java", new Range(new Position(0, 0), new Position(50, 0))),
            "com.test"
        );
        doReturn(List.of(classSymbol)).when(jdtlsClient).findWorkspaceSymbols("test");
        doThrow(new RuntimeException("Document error"))
            .when(jdtlsClient).getDocumentSymbols(anyString());

        // When
        String result = javaTools.findSymbols("test");

        // Then - should still return workspace symbols, not fail completely
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.has("error")).isFalse();
        assertThat(json.get("count").getAsInt()).isEqualTo(1);
    }

    @Test
    void findSymbols_formatsLineNumbersAs1Based() throws Exception {
        // Given
        SymbolInformation classSymbol = new SymbolInformation(
            "TestClass",
            SymbolKind.Class,
            new Location("file:///test/Test.java", new Range(new Position(0, 0), new Position(50, 0))),
            "com.test"
        );
        doReturn(List.of(classSymbol)).when(jdtlsClient).findWorkspaceSymbols("TestClass");
        doReturn(List.of()).when(jdtlsClient).getDocumentSymbols(anyString());

        // When
        String result = javaTools.findSymbols("TestClass");

        // Then - line should be 1 (0+1), column should be 1 (0+1)
        JsonObject json = gson.fromJson(result, JsonObject.class);
        JsonObject symbol = json.getAsJsonArray("symbols").get(0).getAsJsonObject();
        assertThat(symbol.get("line").getAsInt()).isEqualTo(1);
        assertThat(symbol.get("column").getAsInt()).isEqualTo(1);
    }

    // ============================================
    // findReferences tests
    // ============================================

    @Test
    void findReferences_returnsLocations() throws Exception {
        // Given
        Location ref1 = new Location("file:///test/A.java", new Range(new Position(5, 10), new Position(5, 20)));
        Location ref2 = new Location("file:///test/B.java", new Range(new Position(15, 5), new Position(15, 15)));
        doReturn(List.of(ref1, ref2))
            .when(jdtlsClient).findReferences("file:///test/workspace/Test.java", 9, 4);

        // When
        String result = javaTools.findReferences("Test.java", 10, 5);

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(2);
        assertThat(json.getAsJsonArray("references")).hasSize(2);
    }

    @Test
    void findReferences_formatsLocationResultsCorrectly() throws Exception {
        // Given
        Location ref = new Location(
            "file:///test/Service.java",
            new Range(new Position(10, 5), new Position(10, 25))
        );
        doReturn(List.of(ref))
            .when(jdtlsClient).findReferences("file:///test/workspace/Test.java", 4, 9);

        // When
        String result = javaTools.findReferences("Test.java", 5, 10);

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        JsonObject reference = json.getAsJsonArray("references").get(0).getAsJsonObject();
        
        // Verify file path has file:// prefix stripped
        assertThat(reference.get("file").getAsString()).isEqualTo("/test/Service.java");
        // Verify 1-based line numbers
        assertThat(reference.get("startLine").getAsInt()).isEqualTo(11);
        assertThat(reference.get("startColumn").getAsInt()).isEqualTo(6);
        assertThat(reference.get("endLine").getAsInt()).isEqualTo(11);
        assertThat(reference.get("endColumn").getAsInt()).isEqualTo(26);
    }

    @Test
    void findReferences_includesRequestLocationInResponse() throws Exception {
        // Given
        doReturn(List.of())
            .when(jdtlsClient).findReferences(anyString(), anyInt(), anyInt());

        // When
        String result = javaTools.findReferences("MyFile.java", 42, 15);

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("file").getAsString()).isEqualTo("MyFile.java");
        assertThat(json.get("line").getAsInt()).isEqualTo(42);
        assertThat(json.get("character").getAsInt()).isEqualTo(15);
    }

    @Test
    void findReferences_returnsEmptyListWhenNoReferences() throws Exception {
        // Given
        doReturn(List.of())
            .when(jdtlsClient).findReferences(anyString(), anyInt(), anyInt());

        // When
        String result = javaTools.findReferences("Test.java", 10, 5);

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(0);
        assertThat(json.getAsJsonArray("references")).isEmpty();
    }

    @Test
    void findReferences_handlesExceptionGracefully() throws Exception {
        // Given
        doThrow(new ExecutionException("Timeout", new TimeoutException()))
            .when(jdtlsClient).findReferences(anyString(), anyInt(), anyInt());

        // When
        String result = javaTools.findReferences("Test.java", 10, 5);

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.has("error")).isTrue();
    }

    @Test
    void findReferences_resolvesRelativePathToAbsolute() throws Exception {
        // Given - relative path "Test.java" should be resolved against workspace root
        doReturn(List.of())
            .when(jdtlsClient).findReferences(eq("file:///test/workspace/Test.java"), anyInt(), anyInt());

        // When
        String result = javaTools.findReferences("Test.java", 10, 5);

        // Then - verify the URI was correctly formed
        verify(jdtlsClient).findReferences(eq("file:///test/workspace/Test.java"), eq(9), eq(4));
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("file").getAsString()).isEqualTo("Test.java");
        assertThat(json.get("line").getAsInt()).isEqualTo(10);
    }

    @Test
    void findReferences_handlesAbsolutePath() throws Exception {
        // Given - absolute path should be used as-is
        doReturn(List.of())
            .when(jdtlsClient).findReferences(eq("file:///absolute/path/Test.java"), anyInt(), anyInt());

        // When
        String result = javaTools.findReferences("/absolute/path/Test.java", 10, 5);

        // Then
        verify(jdtlsClient).findReferences(eq("file:///absolute/path/Test.java"), eq(9), eq(4));
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("file").getAsString()).isEqualTo("/absolute/path/Test.java");
        assertThat(json.get("line").getAsInt()).isEqualTo(10);
    }

    // ============================================
    // findDefinition tests
    // ============================================

    @Test
    void findDefinition_returnsDefinitionLocation() throws Exception {
        // When - note: unit tests can't read real files, so position_resolved will be false
        String result = javaTools.findDefinition("Impl.java", 50, 15, null);

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("position_resolved").getAsBoolean()).isFalse();
        // definitions empty because position couldn't be validated
        assertThat(json.getAsJsonArray("definitions").size()).isEqualTo(0);
    }

    @Test
    void findDefinition_includesRequestLocationInResponse() throws Exception {
        String result = javaTools.findDefinition("MyFile.java", 10, 5, null);
        JsonObject json = gson.fromJson(result, JsonObject.class);

        // Unit tests can't read real files, so position_resolved will be false
        assertThat(json.get("position_resolved").getAsBoolean()).isFalse();
        assertThat(json.get("line").getAsInt()).isEqualTo(10);
        assertThat(json.get("character").getAsInt()).isEqualTo(5);
    }

    @Test
    void findDefinition_handlesMultipleDefinitions() throws Exception {
        // When
        String result = javaTools.findDefinition("Test.java", 5, 10, null);

        // Then - unit tests can't read real files, so position_resolved will be false and definitions empty
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("position_resolved").getAsBoolean()).isFalse();
        JsonArray defs = json.getAsJsonArray("definitions");
        assertThat(defs).hasSize(0);
    }

    // ============================================
    // getDocumentSymbols tests
    // ============================================

    @Test
    void getDocumentSymbols_returnsSymbolsInDocument() throws Exception {
        // Given
        DocumentSymbol classSymbol = new DocumentSymbol();
        classSymbol.setName("MyClass");
        classSymbol.setKind(SymbolKind.Class);
        classSymbol.setRange(new Range(new Position(0, 0), new Position(100, 0)));
        classSymbol.setSelectionRange(new Range(new Position(0, 0), new Position(0, 10)));

        DocumentSymbol methodSymbol = new DocumentSymbol();
        methodSymbol.setName("myMethod");
        methodSymbol.setKind(SymbolKind.Method);
        methodSymbol.setRange(new Range(new Position(10, 0), new Position(20, 0)));
        methodSymbol.setSelectionRange(new Range(new Position(10, 0), new Position(10, 15)));

        doReturn(List.of(classSymbol, methodSymbol))
            .when(jdtlsClient).getDocumentSymbols("file:///test/workspace/MyClass.java");

        // When
        String result = javaTools.getDocumentSymbols("MyClass.java");

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(2);
        JsonArray symbols = json.getAsJsonArray("symbols");
        assertThat(symbols.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("MyClass");
        assertThat(symbols.get(0).getAsJsonObject().get("kind").getAsString()).isEqualTo("Class");
        assertThat(symbols.get(1).getAsJsonObject().get("name").getAsString()).isEqualTo("myMethod");
        assertThat(symbols.get(1).getAsJsonObject().get("kind").getAsString()).isEqualTo("Method");
    }

    @Test
    void getDocumentSymbols_formatsDocumentSymbolResult() throws Exception {
        // Given
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName("calculateTotal");
        symbol.setKind(SymbolKind.Method);
        symbol.setDetail("double");
        symbol.setRange(new Range(new Position(15, 4), new Position(25, 4)));
        symbol.setSelectionRange(new Range(new Position(15, 4), new Position(15, 18)));

        doReturn(List.of(symbol))
            .when(jdtlsClient).getDocumentSymbols(anyString());

        // When
        String result = javaTools.getDocumentSymbols("Test.java");

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        JsonObject symbolResult = json.getAsJsonArray("symbols").get(0).getAsJsonObject();
        assertThat(symbolResult.get("name").getAsString()).isEqualTo("calculateTotal");
        assertThat(symbolResult.get("kind").getAsString()).isEqualTo("Method");
        assertThat(symbolResult.get("detail").getAsString()).isEqualTo("double");
        assertThat(symbolResult.get("startLine").getAsInt()).isEqualTo(16); // 1-based
        assertThat(symbolResult.get("endLine").getAsInt()).isEqualTo(26); // 1-based
    }



    // ============================================
    // findImplementations tests
    // ============================================

    @Test
    void findImplementations_foundWithResults_returnsTrueAndLocations() throws Exception {
        Location loc = new Location("file:///src/FooImpl.java",
            new Range(new Position(9, 4), new Position(9, 20)));
        doReturn(List.of(loc))
            .when(jdtlsClient).findImplementations(anyString(), eq(4), eq(7));

        String json = javaTools.findImplementations("/workspace/src/Foo.java", 5, 8);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("found").getAsBoolean()).isTrue();
        assertThat(obj.get("count").getAsInt()).isEqualTo(1);
        assertThat(obj.getAsJsonArray("implementations").size()).isEqualTo(1);
        JsonObject impl = obj.getAsJsonArray("implementations").get(0).getAsJsonObject();
        assertThat(impl.get("file").getAsString()).endsWith("FooImpl.java");
        assertThat(impl.get("startLine").getAsInt()).isEqualTo(10);
    }

    @Test
    void findImplementations_emptyList_returnsFoundFalse() throws Exception {
        doReturn(List.of())
            .when(jdtlsClient).findImplementations(anyString(), anyInt(), anyInt());

        String json = javaTools.findImplementations("/workspace/src/Foo.java", 5, 8);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("found").getAsBoolean()).isFalse();
        assertThat(obj.get("count").getAsInt()).isZero();
    }

    // ============================================
    // getHover tests
    // ============================================

    @Test
    void getHover_found_returnsContentAndRange() throws Exception {
        Hover hover = new Hover();
        hover.setContents(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
            new MarkupContent("markdown", "**String** java.lang.String")));
        hover.setRange(new Range(new Position(4, 4), new Position(4, 10)));
        when(jdtlsClient.getHover(anyString(), eq(4), eq(7))).thenReturn(hover);

        String json = javaTools.getHover("/workspace/src/Foo.java", 5, 8, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("found").getAsBoolean()).isTrue();
        assertThat(obj.get("content").getAsString()).contains("String");
        assertThat(obj.getAsJsonObject("range").get("startLine").getAsInt()).isEqualTo(5);
    }

    @Test
    void getHover_null_returnsFalse() throws Exception {
        when(jdtlsClient.getHover(anyString(), anyInt(), anyInt())).thenReturn(null);

        String json = javaTools.getHover("/workspace/src/Foo.java", 5, 8, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("found").getAsBoolean()).isFalse();
        assertThat(obj.get("content").isJsonNull()).isTrue();
    }

    @Test
    void getHover_bySymbol_resolvesAndReturnsContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("Foo.java");
        Files.writeString(
            file,
            "public class Foo {\n    public String greet(String name) {\n        return name;\n    }\n}\n");
        Hover hover = new Hover();
        hover.setContents(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
            new MarkupContent("markdown", "public String greet(String name)")));
        // "greet" is at 1-based position 19 on line 2; LSP receives 0-based: line=1, char=17
        when(jdtlsClient.getHover(anyString(), eq(1), eq(18))).thenReturn(hover);

        String json = javaTools.getHover(file.toString(), 2, null, "greet");
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("found").getAsBoolean()).isTrue();
        assertThat(obj.get("content").getAsString()).contains("greet");
    }

    @Test
    void getHover_missingBothCharacterAndSymbol_returnsError() throws Exception {
        String json = javaTools.getHover("/workspace/src/Foo.java", 5, null, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        assertThat(obj.has("error")).isTrue();
    }

    // ============================================
    // call hierarchy tests
    // ============================================

    @Test
    void findIncomingCalls_found_returnsCallSites() throws Exception {
        CallHierarchyItem caller = new CallHierarchyItem();
        caller.setName("callerMethod");
        caller.setDetail("CallerClass");
        caller.setKind(SymbolKind.Method);
        caller.setUri("file:///Caller.java");
        caller.setRange(new Range(new Position(5, 0), new Position(15, 0)));
        caller.setSelectionRange(new Range(new Position(5, 0), new Position(5, 12)));
        CallHierarchyIncomingCall call = new CallHierarchyIncomingCall(
            caller, List.of(new Range(new Position(9, 8), new Position(9, 18))));
        doReturn(List.of(call))
            .when(jdtlsClient).findIncomingCalls(anyString(), eq(4), eq(7));

        String json = javaTools.findIncomingCalls("/workspace/src/Foo.java", 5, 8, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("found").getAsBoolean()).isTrue();
        assertThat(obj.get("count").getAsInt()).isEqualTo(1);
        assertThat(obj.getAsJsonArray("calls").get(0).getAsJsonObject()
            .get("file").getAsString()).endsWith("Caller.java");
    }

    @Test
    void findIncomingCalls_includesCallerNameAndContainer() throws Exception {
        CallHierarchyItem caller = new CallHierarchyItem();
        caller.setName("processOrder");
        caller.setDetail("OrderService");
        caller.setKind(SymbolKind.Method);
        caller.setUri("file:///OrderService.java");
        caller.setRange(new Range(new Position(18, 0), new Position(25, 0)));
        caller.setSelectionRange(new Range(new Position(18, 0), new Position(18, 12)));
        CallHierarchyIncomingCall call = new CallHierarchyIncomingCall(
            caller, List.of(new Range(new Position(20, 4), new Position(20, 16))));
        doReturn(List.of(call))
            .when(jdtlsClient).findIncomingCalls(anyString(), anyInt(), anyInt());

        String json = javaTools.findIncomingCalls("/workspace/src/Foo.java", 5, 8, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        JsonObject callResult = obj.getAsJsonArray("calls").get(0).getAsJsonObject();
        assertThat(callResult.get("name").getAsString()).isEqualTo("processOrder");
        assertThat(callResult.get("container").getAsString()).isEqualTo("OrderService");
    }

    @Test
    void findIncomingCalls_noHierarchyAtPosition_returnsFalse() throws Exception {
        when(jdtlsClient.findIncomingCalls(anyString(), anyInt(), anyInt()))
            .thenReturn(null);

        String json = javaTools.findIncomingCalls("/workspace/src/Foo.java", 5, 8, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("found").getAsBoolean()).isFalse();
        assertThat(obj.get("count").getAsInt()).isZero();
    }

    @Test
    void findOutgoingCalls_found_returnsCalledMethods() throws Exception {
        CallHierarchyItem callee = new CallHierarchyItem();
        callee.setName("barMethod");
        callee.setDetail("Bar");
        callee.setKind(SymbolKind.Method);
        callee.setUri("file:///Bar.java");
        callee.setRange(new Range(new Position(2, 0), new Position(6, 0)));
        callee.setSelectionRange(new Range(new Position(2, 0), new Position(2, 9)));
        CallHierarchyOutgoingCall call = new CallHierarchyOutgoingCall(
            callee, List.of(new Range(new Position(3, 4), new Position(3, 11))));
        doReturn(List.of(call))
            .when(jdtlsClient).findOutgoingCalls(anyString(), eq(4), eq(7));

        String json = javaTools.findOutgoingCalls("/workspace/src/Foo.java", 5, 8, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("found").getAsBoolean()).isTrue();
        assertThat(obj.get("count").getAsInt()).isEqualTo(1);
        assertThat(obj.getAsJsonArray("calls").get(0).getAsJsonObject()
            .get("file").getAsString()).endsWith("Foo.java");
    }

    @Test
    void findOutgoingCalls_includesCalleeNameAndContainer() throws Exception {
        CallHierarchyItem callee = new CallHierarchyItem();
        callee.setName("save");
        callee.setDetail("UserRepository");
        callee.setKind(SymbolKind.Method);
        callee.setUri("file:///UserRepository.java");
        callee.setRange(new Range(new Position(10, 0), new Position(18, 0)));
        callee.setSelectionRange(new Range(new Position(10, 0), new Position(10, 4)));
        CallHierarchyOutgoingCall call = new CallHierarchyOutgoingCall(
            callee, List.of(new Range(new Position(15, 8), new Position(15, 20))));
        doReturn(List.of(call))
            .when(jdtlsClient).findOutgoingCalls(anyString(), anyInt(), anyInt());

        String json = javaTools.findOutgoingCalls("/workspace/src/Foo.java", 5, 8, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        JsonObject callResult = obj.getAsJsonArray("calls").get(0).getAsJsonObject();
        assertThat(callResult.get("name").getAsString()).isEqualTo("save");
        assertThat(callResult.get("container").getAsString()).isEqualTo("UserRepository");
    }

    @Test
    void findOutgoingCalls_noHierarchyAtPosition_returnsFalse() throws Exception {
        when(jdtlsClient.findOutgoingCalls(anyString(), anyInt(), anyInt()))
            .thenReturn(null);

        String json = javaTools.findOutgoingCalls("/workspace/src/Foo.java", 5, 8, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("found").getAsBoolean()).isFalse();
        assertThat(obj.get("count").getAsInt()).isZero();
    }

    // ============================================
    // diagnostics tests
    // ============================================

    @Test
    void getDiagnostics_specificFile_returnsDiagnosticsForThatFile() {
        DiagnosticsCache cache = new DiagnosticsCache();
        Diagnostic d = new Diagnostic();
        d.setSeverity(DiagnosticSeverity.Error);
        d.setRange(new Range(new Position(4, 0), new Position(4, 10)));
        d.setMessage("cannot find symbol");
        cache.update("file:///workspace/src/Foo.java", List.of(d));
        when(jdtlsClient.getDiagnosticsCache()).thenReturn(cache);

        String json = javaTools.getDiagnostics(null, "/workspace/src/Foo.java");
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("cached").getAsBoolean()).isTrue();
        assertThat(obj.get("file").getAsString()).endsWith("Foo.java");
        assertThat(obj.getAsJsonArray("diagnostics").size()).isEqualTo(1);
        assertThat(obj.get("cache_updated_at_ms").getAsLong()).isPositive();
    }

    @Test
    void getDiagnostics_stringMessage_preservesEitherJsonShape() {
        DiagnosticsCache cache = new DiagnosticsCache();
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        diagnostic.setMessage("cannot find symbol");
        cache.update("file:///workspace/src/Foo.java", List.of(diagnostic));
        when(jdtlsClient.getDiagnosticsCache()).thenReturn(cache);

        String json = javaTools.getDiagnostics(null, "/workspace/src/Foo.java");
        JsonElement messageElement = gson.fromJson(json, JsonObject.class)
            .getAsJsonArray("diagnostics").get(0).getAsJsonObject()
            .get("message");

        assertThat(messageElement.isJsonObject()).isTrue();
        JsonObject message = messageElement.getAsJsonObject();
        assertThat(message.get("left").getAsString()).isEqualTo("cannot find symbol");
        assertThat(message.get("right").isJsonNull()).isTrue();
    }

    @Test
    void getDiagnostics_markupMessage_preservesEitherJsonShape() {
        DiagnosticsCache cache = new DiagnosticsCache();
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        diagnostic.setMessage(new MarkupContent("markdown", "**cannot find symbol**"));
        cache.update("file:///workspace/src/Foo.java", List.of(diagnostic));
        when(jdtlsClient.getDiagnosticsCache()).thenReturn(cache);

        String json = javaTools.getDiagnostics(null, "/workspace/src/Foo.java");
        JsonElement messageElement = gson.fromJson(json, JsonObject.class)
            .getAsJsonArray("diagnostics").get(0).getAsJsonObject()
            .get("message");

        assertThat(messageElement.isJsonObject()).isTrue();
        JsonObject message = messageElement.getAsJsonObject();
        assertThat(message.get("left").isJsonNull()).isTrue();
        assertThat(message.getAsJsonObject("right").get("kind").getAsString()).isEqualTo("markdown");
        assertThat(message.getAsJsonObject("right").get("value").getAsString())
            .isEqualTo("**cannot find symbol**");
    }

    @Test
    void getDiagnostics_summaryOnly_returnsSummaryPerFile() {
        DiagnosticsCache cache = new DiagnosticsCache();
        Diagnostic err = new Diagnostic();
        err.setSeverity(DiagnosticSeverity.Error);
        err.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        err.setMessage("error");
        cache.update("file:///workspace/src/Foo.java", List.of(err));
        when(jdtlsClient.getDiagnosticsCache()).thenReturn(cache);

        String json = javaTools.getDiagnostics(true, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("cached").getAsBoolean()).isTrue();
        JsonArray files = obj.getAsJsonArray("files");
        assertThat(files.size()).isEqualTo(1);
        JsonObject file = files.get(0).getAsJsonObject();
        assertThat(file.get("errors").getAsInt()).isEqualTo(1);
        assertThat(file.get("warnings").getAsInt()).isZero();
    }

    @Test
    void getDiagnostics_emptyCache_returnsEmptyFiles() {
        DiagnosticsCache cache = new DiagnosticsCache();
        when(jdtlsClient.getDiagnosticsCache()).thenReturn(cache);

        String json = javaTools.getDiagnostics(null, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.getAsJsonArray("files").size()).isZero();
        assertThat(obj.get("cache_updated_at_ms").getAsLong()).isZero();
    }

    @Test
    void getDiagnostics_fileNotInCache_returnsEmptyDiagnostics() {
        DiagnosticsCache cache = new DiagnosticsCache();
        when(jdtlsClient.getDiagnosticsCache()).thenReturn(cache);

        String json = javaTools.getDiagnostics(null, "/workspace/src/Missing.java");
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("file").getAsString()).endsWith("Missing.java");
        assertThat(obj.getAsJsonArray("diagnostics").size()).isZero();
        assertThat(obj.get("cached").getAsBoolean()).isTrue();
    }

    @Test
    void refreshDiagnostics_returnsOkWithDuration() throws Exception {
        doNothing().when(jdtlsClient).buildIncremental();

        String json = javaTools.refreshDiagnostics();
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("status").getAsString()).isEqualTo("ok");
        assertThat(obj.get("build_duration_ms").getAsLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void resolveStackTrace_found_returnsFileAndLine() throws Exception {
        JsonObject location = new JsonObject();
        location.addProperty("uri", "file:///workspace/src/com/example/Foo.java");
        JsonObject start = new JsonObject();
        start.addProperty("line", 41);
        start.addProperty("character", 0);
        JsonObject range = new JsonObject();
        range.add("start", start);
        range.add("end", start);
        location.add("range", range);
        when(jdtlsClient.resolveStackTraceLocation(anyString())).thenReturn(location);

        String json = javaTools.resolveStackTrace("at com.example.Foo.bar(Foo.java:42)");
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("file").getAsString()).endsWith("Foo.java");
        assertThat(obj.get("line").getAsInt()).isEqualTo(42);
    }

    @Test
    void resolveStackTrace_noLineInStackFrame_returnsNullLineWithMessage() throws Exception {
        // JDTLS returns URI string but stack frame has no parseable line number
        when(jdtlsClient.resolveStackTraceLocation(anyString()))
            .thenReturn("file:///workspace/src/com/example/Foo.java");

        String json = javaTools.resolveStackTrace("at com.example.Foo.bar(Unknown Source)");
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("file").getAsString()).endsWith("Foo.java");
        assertThat(obj.get("line").isJsonNull()).isTrue();
        assertThat(obj.get("message").getAsString()).isEqualTo("could not extract line number from stack frame");
    }

    @Test
    void resolveStackTrace_notFound_returnsNullFields() throws Exception {
        when(jdtlsClient.resolveStackTraceLocation(anyString())).thenReturn(null);

        String json = javaTools.resolveStackTrace("at com.example.Missing.method(Missing.java:1)");
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        assertThat(obj.get("file").isJsonNull()).isTrue();
        assertThat(obj.get("line").isJsonNull()).isTrue();
        assertThat(obj.get("message").getAsString()).isEqualTo("not found");
    }

    // ============================================
    // getDocumentSymbols hierarchical flattening
    // ============================================

    @Test
    void getDocumentSymbols_flattensHierarchicalSymbols() throws Exception {
        DocumentSymbol method = new DocumentSymbol();
        method.setName("myMethod");
        method.setKind(SymbolKind.Method);
        method.setRange(new Range(new Position(10, 4), new Position(20, 4)));
        method.setSelectionRange(new Range(new Position(10, 4), new Position(10, 12)));

        DocumentSymbol classSymbol = new DocumentSymbol();
        classSymbol.setName("MyClass");
        classSymbol.setKind(SymbolKind.Class);
        classSymbol.setRange(new Range(new Position(0, 0), new Position(30, 0)));
        classSymbol.setSelectionRange(new Range(new Position(0, 0), new Position(0, 7)));
        classSymbol.setChildren(List.of(method));

        doReturn(List.of(classSymbol))
            .when(jdtlsClient).getDocumentSymbols(anyString());

        String result = javaTools.getDocumentSymbols("MyClass.java");
        JsonObject json = gson.fromJson(result, JsonObject.class);

        assertThat(json.get("count").getAsInt()).isEqualTo(2);
        JsonArray symbols = json.getAsJsonArray("symbols");
        assertThat(symbols.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("MyClass");
        assertThat(symbols.get(1).getAsJsonObject().get("name").getAsString()).isEqualTo("myMethod");
    }

    // ============================================
    // getDiagnostics summary filtering
    // ============================================

    @Test
    void getDiagnostics_summaryOnly_excludesFilesWithNoDiagnostics() {
        DiagnosticsCache cache = new DiagnosticsCache();
        Diagnostic err = new Diagnostic();
        err.setSeverity(DiagnosticSeverity.Error);
        err.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        err.setMessage("error");
        cache.update("file:///workspace/src/Foo.java", List.of(err));
        cache.update("file:///workspace/src/Clean.java", List.of());
        when(jdtlsClient.getDiagnosticsCache()).thenReturn(cache);

        String json = javaTools.getDiagnostics(true, null);
        JsonObject obj = gson.fromJson(json, JsonObject.class);

        JsonArray files = obj.getAsJsonArray("files");
        assertThat(files.size()).isEqualTo(1);
        assertThat(files.get(0).getAsJsonObject().get("file").getAsString()).endsWith("Foo.java");
    }

    // ============================================
    // URI/Path conversion tests
    // ============================================

    @Test
    void findSymbols_stripsFileProtocolFromUri() throws Exception {
        // Given
        SymbolInformation symbol = new SymbolInformation(
            "TestClass",
            SymbolKind.Class,
            new Location("file:///test/path/TestClass.java", new Range(new Position(0, 0), new Position(10, 0))),
            "com.test"
        );
        doReturn(List.of(symbol)).when(jdtlsClient).findWorkspaceSymbols("TestClass");
        doReturn(List.of()).when(jdtlsClient).getDocumentSymbols(anyString());

        // When
        String result = javaTools.findSymbols("TestClass");

        // Then
        JsonObject json = gson.fromJson(result, JsonObject.class);
        JsonObject symbolResult = json.getAsJsonArray("symbols").get(0).getAsJsonObject();
        assertThat(symbolResult.get("file").getAsString()).isEqualTo("/test/path/TestClass.java");
    }

    @Test
    void findSymbols_decodesPercentEncodedCharactersInUri() throws Exception {
        // Given - URI with percent-encoded space characters
        SymbolInformation symbol = new SymbolInformation(
            "TestClass",
            SymbolKind.Class,
            new Location("file:///test/path%20with%20spaces/TestClass.java",
                new Range(new Position(0, 0), new Position(10, 0))),
            "com.test"
        );
        doReturn(List.of(symbol)).when(jdtlsClient).findWorkspaceSymbols("TestClass");
        doReturn(List.of()).when(jdtlsClient).getDocumentSymbols(anyString());

        // When
        String result = javaTools.findSymbols("TestClass");

        // Then - percent-encoded characters must be decoded to the real path
        JsonObject json = gson.fromJson(result, JsonObject.class);
        JsonObject symbolResult = json.getAsJsonArray("symbols").get(0).getAsJsonObject();
        assertThat(symbolResult.get("file").getAsString())
            .isEqualTo("/test/path with spaces/TestClass.java");
    }

    @Test
    void findSymbols_deduplicatesAnnotatedClass() throws Exception {
        // Given: two SymbolInformation entries for the same class
        // (one at annotation line with empty container, one at class keyword line with full package)
        SymbolInformation sym1 = new SymbolInformation(
            "Greeter", SymbolKind.Interface,
            new Location("file:///Greeter.java", new Range(new Position(2, 0), new Position(2, 10))),
            "" // empty containerName (annotation line)
        );
        SymbolInformation sym2 = new SymbolInformation(
            "Greeter", SymbolKind.Interface,
            new Location("file:///Greeter.java", new Range(new Position(3, 0), new Position(3, 10))),
            "com.example.sample" // non-empty containerName (class keyword line)
        );
        SymbolInformation sym3 = new SymbolInformation(
            "GreeterImpl", SymbolKind.Class,
            new Location("file:///GreeterImpl.java", new Range(new Position(5, 0), new Position(5, 10))),
            "com.example.sample"
        );

        doReturn(List.of(sym1, sym2, sym3)).when(jdtlsClient).findWorkspaceSymbols("Greet");
        doReturn(List.of()).when(jdtlsClient).getDocumentSymbols(anyString());

        String result = javaTools.findSymbols("Greet");

        // Verify count = 2 (deduped) and Greeter entry uses non-empty container
        JsonObject json = gson.fromJson(result, JsonObject.class);
        JsonArray symbols = json.getAsJsonArray("symbols");
        assertThat(symbols.size()).isEqualTo(2);

        // First entry should be Greeter with non-empty container (dedup kept the package entry)
        JsonObject greeter = symbols.get(0).getAsJsonObject();
        assertThat(greeter.get("name").getAsString()).isEqualTo("Greeter");
        assertThat(greeter.get("container").getAsString()).isEqualTo("com.example.sample");
    }

    // ============================================
    // findMethodDeclarations tests
    // ============================================

    @Test
    void findMethodDeclarations_searchInInterfaces_default_returnsOnlyInterfaceMethod() throws Exception {
        DocumentSymbol ifaceMethod = makeMethod("process", "()", 5);
        DocumentSymbol classMethod = makeMethod("process", "()", 15);
        DocumentSymbol iface = makeContainer("Processor", SymbolKind.Interface, 2, List.of(ifaceMethod));
        DocumentSymbol cls   = makeContainer("ProcessorImpl", SymbolKind.Class,  10, List.of(classMethod));

        doReturn(List.of(makeWorkspaceSymbol("Processor", SymbolKind.Interface, "file:///test/P.java")))
            .when(jdtlsClient).findWorkspaceSymbols("process");
        doReturn(List.of(makePkg("com.test"), iface, cls))
            .when(jdtlsClient).getDocumentSymbols("file:///test/P.java");

        String result = javaTools.findMethodDeclarations("process", "interfaces", null, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(1);
        JsonObject m = json.getAsJsonArray("methods").get(0).getAsJsonObject();
        assertThat(m.get("containerKind").getAsString()).isEqualTo("Interface");
        assertThat(m.get("container").getAsString()).isEqualTo("Processor");
        assertThat(m.get("name").getAsString()).isEqualTo("process");
    }

    @Test
    void findMethodDeclarations_searchInClasses_returnsOnlyClassMethod() throws Exception {
        DocumentSymbol ifaceMethod = makeMethod("process", "()", 5);
        DocumentSymbol classMethod = makeMethod("process", "()", 15);
        DocumentSymbol iface = makeContainer("Processor", SymbolKind.Interface, 2, List.of(ifaceMethod));
        DocumentSymbol cls   = makeContainer("ProcessorImpl", SymbolKind.Class,  10, List.of(classMethod));

        doReturn(List.of(makeWorkspaceSymbol("Processor", SymbolKind.Interface, "file:///test/P.java")))
            .when(jdtlsClient).findWorkspaceSymbols("process");
        doReturn(List.of(makePkg("com.test"), iface, cls))
            .when(jdtlsClient).getDocumentSymbols("file:///test/P.java");

        String result = javaTools.findMethodDeclarations("process", "classes", null, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(1);
        assertThat(json.getAsJsonArray("methods").get(0).getAsJsonObject()
            .get("containerKind").getAsString()).isEqualTo("Class");
    }

    @Test
    void findMethodDeclarations_searchInAll_returnsBothMethods() throws Exception {
        DocumentSymbol ifaceMethod = makeMethod("process", "()", 5);
        DocumentSymbol classMethod = makeMethod("process", "()", 15);
        DocumentSymbol iface = makeContainer("Processor", SymbolKind.Interface, 2, List.of(ifaceMethod));
        DocumentSymbol cls   = makeContainer("ProcessorImpl", SymbolKind.Class,  10, List.of(classMethod));

        doReturn(List.of(makeWorkspaceSymbol("Processor", SymbolKind.Interface, "file:///test/P.java")))
            .when(jdtlsClient).findWorkspaceSymbols("process");
        doReturn(List.of(makePkg("com.test"), iface, cls))
            .when(jdtlsClient).getDocumentSymbols("file:///test/P.java");

        String result = javaTools.findMethodDeclarations("process", "all", null, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(2);
    }

    @Test
    void findMethodDeclarations_packageFilter_matchesExact() throws Exception {
        DocumentSymbol m = makeMethod("fetch", "()", 5);
        DocumentSymbol iface = makeContainer("Repo", SymbolKind.Interface, 2, List.of(m));

        doReturn(List.of(makeWorkspaceSymbol("Repo", SymbolKind.Interface, "file:///test/R.java")))
            .when(jdtlsClient).findWorkspaceSymbols("fetch");
        doReturn(List.of(makePkg("com.example"), iface))
            .when(jdtlsClient).getDocumentSymbols("file:///test/R.java");

        String result = javaTools.findMethodDeclarations("fetch", null, "com.example", null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(1);
    }

    @Test
    void findMethodDeclarations_packageFilter_matchesPrefix() throws Exception {
        DocumentSymbol m = makeMethod("fetch", "()", 5);
        DocumentSymbol iface = makeContainer("Repo", SymbolKind.Interface, 2, List.of(m));

        doReturn(List.of(makeWorkspaceSymbol("Repo", SymbolKind.Interface, "file:///test/R.java")))
            .when(jdtlsClient).findWorkspaceSymbols("fetch");
        doReturn(List.of(makePkg("com.example.repo"), iface))
            .when(jdtlsClient).getDocumentSymbols("file:///test/R.java");

        String result = javaTools.findMethodDeclarations("fetch", null, "com.example", null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(1);
    }

    @Test
    void findMethodDeclarations_packageFilter_excludesNonMatchingPackage() throws Exception {
        DocumentSymbol m = makeMethod("fetch", "()", 5);
        DocumentSymbol iface = makeContainer("Repo", SymbolKind.Interface, 2, List.of(m));

        doReturn(List.of(makeWorkspaceSymbol("Repo", SymbolKind.Interface, "file:///test/R.java")))
            .when(jdtlsClient).findWorkspaceSymbols("fetch");
        doReturn(List.of(makePkg("org.example"), iface))
            .when(jdtlsClient).getDocumentSymbols("file:///test/R.java");

        String result = javaTools.findMethodDeclarations("fetch", null, "com.example", null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(0);
    }

    @Test
    void findMethodDeclarations_parameterCount_zero_returnsNoArgMethod() throws Exception {
        DocumentSymbol noArg   = makeMethod("foo", "()", 5);
        DocumentSymbol oneArg  = makeMethod("foo", "(String s)", 10);
        DocumentSymbol iface = makeContainer("Svc", SymbolKind.Interface, 2, List.of(noArg, oneArg));

        doReturn(List.of(makeWorkspaceSymbol("Svc", SymbolKind.Interface, "file:///test/Svc.java")))
            .when(jdtlsClient).findWorkspaceSymbols("foo");
        doReturn(List.of(makePkg("com.test"), iface))
            .when(jdtlsClient).getDocumentSymbols("file:///test/Svc.java");

        String result = javaTools.findMethodDeclarations("foo", null, null, 0);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(1);
        assertThat(json.getAsJsonArray("methods").get(0).getAsJsonObject()
            .get("name").getAsString()).isEqualTo("foo");
    }

    @Test
    void findMethodDeclarations_parameterCount_two_returnsTwoParamMethod() throws Exception {
        DocumentSymbol oneArg = makeMethod("foo", "(String s)",    5);
        DocumentSymbol twoArg = makeMethod("foo", "(String s, int n)", 10);
        DocumentSymbol iface = makeContainer("Svc", SymbolKind.Interface, 2, List.of(oneArg, twoArg));

        doReturn(List.of(makeWorkspaceSymbol("Svc", SymbolKind.Interface, "file:///test/Svc.java")))
            .when(jdtlsClient).findWorkspaceSymbols("foo");
        doReturn(List.of(makePkg("com.test"), iface))
            .when(jdtlsClient).getDocumentSymbols("file:///test/Svc.java");

        String result = javaTools.findMethodDeclarations("foo", null, null, 2);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(1);
        assertThat(json.getAsJsonArray("methods").get(0).getAsJsonObject()
            .get("line").getAsInt()).isEqualTo(11);
    }

    @Test
    void findMethodDeclarations_parameterCount_absent_returnsBothMethods() throws Exception {
        DocumentSymbol noArg  = makeMethod("foo", "()", 5);
        DocumentSymbol oneArg = makeMethod("foo", "(String s)", 10);
        DocumentSymbol iface = makeContainer("Svc", SymbolKind.Interface, 2, List.of(noArg, oneArg));

        doReturn(List.of(makeWorkspaceSymbol("Svc", SymbolKind.Interface, "file:///test/Svc.java")))
            .when(jdtlsClient).findWorkspaceSymbols("foo");
        doReturn(List.of(makePkg("com.test"), iface))
            .when(jdtlsClient).getDocumentSymbols("file:///test/Svc.java");

        String result = javaTools.findMethodDeclarations("foo", null, null, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(2);
    }

    @Test
    void findMethodDeclarations_innerInterface_nestedInClass_isFound() throws Exception {
        DocumentSymbol innerMethod = makeMethod("handle", "()", 12);
        DocumentSymbol innerIface  = makeContainer("Handler", SymbolKind.Interface, 10, List.of(innerMethod));
        DocumentSymbol outerClass  = makeContainer("OuterClass", SymbolKind.Class, 2, List.of(innerIface));

        doReturn(List.of(makeWorkspaceSymbol("OuterClass", SymbolKind.Class, "file:///test/O.java")))
            .when(jdtlsClient).findWorkspaceSymbols("handle");
        doReturn(List.of(makePkg("com.test"), outerClass))
            .when(jdtlsClient).getDocumentSymbols("file:///test/O.java");

        String result = javaTools.findMethodDeclarations("handle", "interfaces", null, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(1);
        JsonObject m = json.getAsJsonArray("methods").get(0).getAsJsonObject();
        assertThat(m.get("containerKind").getAsString()).isEqualTo("Interface");
        assertThat(m.get("container").getAsString()).isEqualTo("Handler");
    }

    @Test
    void findMethodDeclarations_topLevelMethod_notIncluded() throws Exception {
        DocumentSymbol topLevelMethod = makeMethod("orphan", "()", 3);

        doReturn(List.of(makeWorkspaceSymbol("orphan", SymbolKind.Method, "file:///test/Bad.java")))
            .when(jdtlsClient).findWorkspaceSymbols("orphan");
        doReturn(List.of(makePkg("com.test"), topLevelMethod))
            .when(jdtlsClient).getDocumentSymbols("file:///test/Bad.java");

        String result = javaTools.findMethodDeclarations("orphan", "all", null, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(0);
    }

    @Test
    void findMethodDeclarations_fallback_scansSourceFiles(@TempDir Path tempDir) throws Exception {
        doReturn(List.of()).when(jdtlsClient).findWorkspaceSymbols("getAll");

        Path javaFile = tempDir.resolve("StoragePort.java");
        Files.writeString(javaFile, "package com.test; interface StoragePort { List getAll(); }");

        DocumentSymbol method = makeMethod("getAll", "()", 0);
        DocumentSymbol iface  = makeContainer("StoragePort", SymbolKind.Interface, 0, List.of(method));
        DocumentSymbol pkg    = makePkg("com.test");
        doReturn(List.of(pkg, iface)).when(jdtlsClient).getDocumentSymbols(anyString());

        JavaTools tools = new JavaTools(jdtlsClient, tempDir);
        String result = tools.findMethodDeclarations("getAll", null, null, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("count").getAsInt()).isEqualTo(1);
        JsonObject found = json.getAsJsonArray("methods").get(0).getAsJsonObject();
        assertThat(found.get("name").getAsString()).isEqualTo("getAll");
        assertThat(found.get("container").getAsString()).isEqualTo("StoragePort");
        assertThat(found.get("containerKind").getAsString()).isEqualTo("Interface");
    }

    // ============================================
    // decompileClass tests
    // ============================================

    @Test
    void decompileClass_returnsDecompiledSource() throws Exception {
        doReturn("class Foo { }")
            .when(jdtlsClient).decompileClass("jdt://contents/java.lang/Foo.class");

        String result = javaTools.decompileClass("jdt://contents/java.lang/Foo.class");

        assertThat(result).isEqualTo("class Foo { }");
    }

    @Test
    void decompileClass_returnsErrorWhenResultIsBlank() throws Exception {
        doReturn("")
            .when(jdtlsClient).decompileClass(anyString());

        String result = javaTools.decompileClass("jdt://contents/java.lang/Foo.class");

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("error").getAsString())
            .contains("no source available for: jdt://contents/java.lang/Foo.class");
    }

    @Test
    void decompileClass_returnsErrorWhenResultIsFalse() throws Exception {
        doReturn("false")
            .when(jdtlsClient).decompileClass(anyString());

        String result = javaTools.decompileClass("jdt://contents/java.lang/Foo.class");

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("error").getAsString())
            .contains("no source available for: jdt://contents/java.lang/Foo.class");
    }

    // ============================================
    // getProjects tests
    // ============================================

    @Test
    void getProjects_returnsMappedProjectList() throws Exception {
        JsonArray arr = new JsonArray();
        arr.add("file:///workspace/my-service");
        doReturn(arr).when(jdtlsClient).getProjects();

        String result = javaTools.getProjects();

        JsonArray json = gson.fromJson(result, JsonArray.class);
        assertThat(json.size()).isEqualTo(1);
        JsonObject project = json.get(0).getAsJsonObject();
        assertThat(project.get("name").getAsString()).isEqualTo("my-service");
        assertThat(project.get("uri").getAsString()).isEqualTo("file:///workspace/my-service");
    }

    @Test
    void getProjects_returnsEmptyListWhenNull() throws Exception {
        doReturn(null).when(jdtlsClient).getProjects();

        String result = javaTools.getProjects();

        JsonArray json = gson.fromJson(result, JsonArray.class);
        assertThat(json.size()).isEqualTo(0);
    }

    // ============================================
    // getClasspath tests
    // ============================================

    @Test
    void getClasspath_separatesJarsFromSources() throws Exception {
        JsonObject raw = new JsonObject();
        JsonArray sourcePaths = new JsonArray();
        sourcePaths.add("/src/main/java");
        raw.add("org.eclipse.jdt.ls.core.sourcePaths", sourcePaths);
        JsonArray referencedLibraries = new JsonArray();
        referencedLibraries.add("/home/user/.m2/spring-core-6.0.jar");
        raw.add("org.eclipse.jdt.ls.core.referencedLibraries", referencedLibraries);
        doReturn(raw).when(jdtlsClient).getClasspath(anyString());

        String result = javaTools.getClasspath("/test/workspace/Foo.java");

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.getAsJsonArray("sources").size()).isEqualTo(1);
        assertThat(json.getAsJsonArray("sources").get(0).getAsString())
            .isEqualTo("/src/main/java");
        assertThat(json.getAsJsonArray("jars").size()).isEqualTo(1);
        assertThat(json.getAsJsonArray("jars").get(0).getAsString())
            .isEqualTo("/home/user/.m2/spring-core-6.0.jar");
    }

    @Test
    void getClasspath_returnsEmptyListsWhenNull() throws Exception {
        doReturn(null).when(jdtlsClient).getClasspath(anyString());

        String result = javaTools.getClasspath("/test/workspace/Foo.java");

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.getAsJsonArray("sources").size()).isEqualTo(0);
        assertThat(json.getAsJsonArray("jars").size()).isEqualTo(0);
    }

    // ============================================
    // getTypeDefinition tests
    // ============================================

    @Test
    void getTypeDefinition_returnsTypeLocation() throws Exception {
        Location loc = new Location(
            "file:///test/Greeter.java",
            new Range(new Position(2, 0), new Position(4, 1))
        );
        doReturn(List.of(loc))
            .when(jdtlsClient).getTypeDefinition("file:///test/workspace/App.java", 5, 16);

        String result = javaTools.getTypeDefinition("App.java", 6, 17, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.getAsJsonArray("definitions").size()).isEqualTo(1);
        JsonObject def = json.getAsJsonArray("definitions").get(0).getAsJsonObject();
        assertThat(def.get("file").getAsString()).isEqualTo("/test/Greeter.java");
        assertThat(def.get("startLine").getAsInt()).isEqualTo(3);
    }

    @Test
    void getTypeDefinition_returnsEmptyWhenNotFound() throws Exception {
        doReturn(List.of())
            .when(jdtlsClient).getTypeDefinition(anyString(), anyInt(), anyInt());

        String result = javaTools.getTypeDefinition("App.java", 6, 17, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.getAsJsonArray("definitions")).isEmpty();
    }

    @Test
    void getTypeDefinition_resolvesCharacterBySymbol(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("App.java");
        Files.writeString(file, "class App {\n    void m() {\n        Greeter g;\n    }\n}");
        Location loc = new Location(
            "file:///Greeter.java",
            new Range(new Position(0, 0), new Position(1, 0)));
        doReturn(List.of(loc))
            .when(jdtlsClient).getTypeDefinition(anyString(), eq(2), eq(8));

        String result = javaTools.getTypeDefinition(file.toString(), 3, null, "Greeter");

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.getAsJsonArray("definitions").size()).isEqualTo(1);
        assertThat(json.get("character").getAsInt()).isEqualTo(9);
    }

    @Test
    void getTypeDefinition_returnsErrorWhenSymbolNotFound(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("App.java");
        Files.writeString(file, "class App {}");

        String result = javaTools.getTypeDefinition(file.toString(), 1, null, "Missing");

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("error").getAsString()).contains("Missing");
    }

    // ============================================
    // getTypeHierarchy tests
    // ============================================

    @Test
    void getTypeHierarchy_returnsHierarchyWithSubtypes() throws Exception {
        TypeHierarchyItem greeter = makeTypeHierarchyItem("Greeter", "file:///Greeter.java", 2, 0, 4, 1);
        TypeHierarchyItem impl = makeTypeHierarchyItem("GreeterImpl", "file:///GreeterImpl.java", 2, 0, 8, 1);
        TypeHierarchyData data = new TypeHierarchyData(
            greeter, List.of(), List.of(impl));
        doReturn(data).when(jdtlsClient).getTypeHierarchy("file:///test/workspace/Greeter.java", 2, 17);

        String result = javaTools.getTypeHierarchy("Greeter.java", 3, 18, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("name").getAsString()).isEqualTo("Greeter");
        assertThat(json.getAsJsonArray("supertypes")).isEmpty();
        assertThat(json.getAsJsonArray("subtypes").size()).isEqualTo(1);
        assertThat(json.getAsJsonArray("subtypes").get(0)
            .getAsJsonObject().get("name").getAsString()).isEqualTo("GreeterImpl");
    }

    @Test
    void getTypeHierarchy_returnsErrorWhenNotFound() throws Exception {
        doReturn(null).when(jdtlsClient).getTypeHierarchy(anyString(), anyInt(), anyInt());

        String result = javaTools.getTypeHierarchy("Greeter.java", 3, 18, null);

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.has("error")).isTrue();
    }

    @Test
    void getTypeHierarchy_resolvesCharacterBySymbol(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("Greeter.java");
        Files.writeString(file, "public interface Greeter {\n    String greet(String name);\n}");
        TypeHierarchyItem greeter = makeTypeHierarchyItem("Greeter", "file:///Greeter.java", 0, 0, 1, 0);
        TypeHierarchyData data = new TypeHierarchyData(greeter, List.of(), List.of());
        doReturn(data).when(jdtlsClient).getTypeHierarchy(anyString(), eq(0), eq(17));

        String result = javaTools.getTypeHierarchy(file.toString(), 1, null, "Greeter");

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("name").getAsString()).isEqualTo("Greeter");
    }

    @Test
    void getTypeHierarchy_returnsErrorWhenSymbolNotFound(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("Greeter.java");
        Files.writeString(file, "public interface Greeter {}");

        String result = javaTools.getTypeHierarchy(file.toString(), 1, null, "Missing");

        JsonObject json = gson.fromJson(result, JsonObject.class);
        assertThat(json.get("error").getAsString()).contains("Missing");
    }

    // ── DSL helpers for building DocumentSymbol trees ─────────────────────────

    private DocumentSymbol makeMethod(String name, String detail, int line) {
        DocumentSymbol m = new DocumentSymbol();
        m.setName(name);
        m.setKind(SymbolKind.Method);
        m.setRange(new Range(new Position(line, 4), new Position(line, 4 + name.length())));
        m.setSelectionRange(m.getRange());
        m.setDetail(detail);
        return m;
    }

    private DocumentSymbol makeContainer(String name, SymbolKind kind, int line,
                                         List<DocumentSymbol> children) {
        DocumentSymbol c = new DocumentSymbol();
        c.setName(name);
        c.setKind(kind);
        c.setRange(new Range(new Position(line, 0), new Position(line + 20, 0)));
        c.setSelectionRange(new Range(new Position(line, 0), new Position(line, name.length())));
        c.setChildren(children);
        return c;
    }

    private DocumentSymbol makePkg(String pkgName) {
        DocumentSymbol p = new DocumentSymbol();
        p.setName(pkgName);
        p.setKind(SymbolKind.Package);
        p.setRange(new Range(new Position(0, 0), new Position(0, pkgName.length())));
        p.setSelectionRange(p.getRange());
        return p;
    }

    private SymbolInformation makeWorkspaceSymbol(String name, SymbolKind kind, String uri) {
        return new SymbolInformation(
            name, kind,
            new Location(uri, new Range(new Position(0, 0), new Position(50, 0))),
            "com.test"
        );
    }

    private TypeHierarchyItem makeTypeHierarchyItem(
        String name, String uri,
        int startLine, int startChar,
        int endLine, int endChar) {
        Range range = new Range(new Position(startLine, startChar), new Position(endLine, endChar));
        return new TypeHierarchyItem(name, SymbolKind.Interface, uri, range, range);
    }
}
