package com.saloidvl.lsp4jmcp.server;

import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.saloidvl.lsp4jmcp.client.JdtlsLanguageClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaMcpServerTest {

    @Test
    void create_acceptsArbitraryInputAndOutputStreams() {
        JdtlsClient client = mock(JdtlsClient.class);
        when(client.getLanguageClient()).thenReturn(new JdtlsLanguageClient());

        assertThatCode(() -> JavaMcpServer.create(
            new ByteArrayInputStream(new byte[0]),
            OutputStream.nullOutputStream(),
            client,
            Path.of("/tmp/workspace")
        )).doesNotThrowAnyException();
    }

    @Test
    void registeredToolNames_matchExpectedInventory() {
        assertThatCode(() -> JavaMcpServer.registeredToolNames())
            .doesNotThrowAnyException();
    }

    @Test
    void registeredToolNames_includeRecoveryTools() {
        assertThat(JavaMcpServer.registeredToolNames()).contains("restart_jdtls", "reindex_workspace");
    }

    @Test
    void registeredToolNames_matchExpandedInventory() {
        assertThat(JavaMcpServer.registeredToolNames()).isEqualTo(Set.of(
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
        ));
    }
}
