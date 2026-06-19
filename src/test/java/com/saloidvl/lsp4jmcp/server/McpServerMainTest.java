package com.saloidvl.lsp4jmcp.server;

import com.saloidvl.lsp4jmcp.tools.JavaTools;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerMainTest {

    private static final Set<String> EXPECTED_TOOL_NAMES = Set.of(
        "find_symbols",
        "find_references",
        "find_definition",
        "document_symbols",
        "indexing_status",
        "find_method_declarations",
        "restart_jdtls",
        "reindex_workspace",
        "find_implementations",
        "get_hover",
        "find_incoming_calls",
        "find_outgoing_calls",
        "get_diagnostics",
        "refresh_diagnostics",
        "resolve_stack_trace",
        "decompile_class",
        "get_type_hierarchy",
        "get_type_definition",
        "get_projects",
        "get_classpath"
    );

    @Test
    void mainMethodExists() throws NoSuchMethodException {
        var mainMethod = McpServerMain.class.getMethod("main", String[].class);
        assertThat(mainMethod).isNotNull();
        assertThat(mainMethod.getReturnType()).isEqualTo(void.class);
        assertThat(Modifier.isStatic(mainMethod.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(mainMethod.getModifiers())).isTrue();
    }

    @Test
    void serverNameConstantIsDefined() {
        assertThat(McpServerMain.serverName()).isEqualTo("java-lsp");
    }

    @Test
    void serverVersionConstantIsDefined() {
        assertThat(McpServerMain.serverVersion()).isNotBlank();
        assertThat(McpServerMain.serverVersion()).matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void serverVersionIsNotPinnedToLegacyConstant() {
        assertThat(McpServerMain.serverVersion()).isNotEqualTo("1.0.0");
        assertThat(McpServerMain.serverVersion()).matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void allRegisteredToolNamesAreSnakeCase() {
        JavaMcpServer.registeredToolNames().forEach(name ->
            assertThat(name)
                .as("Tool name should be snake_case: " + name)
                .matches("[a-z_]+"));
    }

    @Test
    void javaToolsHasRequiredMethods() {
        Method[] methods = JavaTools.class.getDeclaredMethods();
        Set<String> methodNames = Arrays.stream(methods)
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(methodNames).contains("findSymbols");
        assertThat(methodNames).contains("findReferences");
        assertThat(methodNames).contains("findDefinition");
        assertThat(methodNames).contains("getDocumentSymbols");
        assertThat(methodNames).doesNotContain("getIndexingStatus");
        assertThat(methodNames).contains("findMethodDeclarations");
        assertThat(methodNames).doesNotContain("findInterfacesWithMethod");
        assertThat(methodNames).contains("decompileClass");
        assertThat(methodNames).contains("getTypeHierarchy");
        assertThat(methodNames).contains("getTypeDefinition");
        assertThat(methodNames).contains("getProjects");
        assertThat(methodNames).contains("getClasspath");
    }

    @Test
    void findSymbolsMethodSignature_isCorrect() throws NoSuchMethodException {
        Method method = JavaTools.class.getMethod("findSymbols", String.class);
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void findReferencesMethodSignature_isCorrect() throws NoSuchMethodException {
        Method method = JavaTools.class.getMethod("findReferences", String.class, int.class, int.class);
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void findDefinitionMethodSignature_isCorrect() throws NoSuchMethodException {
        Method method = JavaTools.class.getMethod("findDefinition", String.class, int.class, Integer.class, String.class);
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void getDocumentSymbolsMethodSignature_isCorrect() throws NoSuchMethodException {
        Method method = JavaTools.class.getMethod("getDocumentSymbols", String.class);
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void findMethodDeclarationsMethodSignature_isCorrect() throws NoSuchMethodException {
        Method method = JavaTools.class.getMethod(
            "findMethodDeclarations", String.class, String.class, String.class, Integer.class);
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void runMethodExists() throws NoSuchMethodException {
        Method runMethod = McpServerMain.class.getDeclaredMethod("run",
            java.nio.file.Path.class, String.class);
        runMethod.setAccessible(true);

        assertThat(runMethod).isNotNull();
        assertThat(Modifier.isStatic(runMethod.getModifiers())).isTrue();
        assertThat(Modifier.isPrivate(runMethod.getModifiers())).isTrue();
    }

    @Test
    void expectedToolCount_isTwentyOne() {
        assertThat(EXPECTED_TOOL_NAMES).hasSize(20);
        assertThat(JavaMcpServer.registeredToolNames()).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOL_NAMES);
    }
}
