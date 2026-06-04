package com.saloidvl.lsp4jmcp.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SocketPathsTest {

    @Test
    void supervisorSocketPath_isUnderUserCacheDirectory() {
        Path path = SocketPaths.supervisorSocketPath();

        assertThat(path.toString()).contains("lsp4j-mcp");
        assertThat(path.getFileName().toString()).startsWith("supervisor-");
        assertThat(path.getFileName().toString()).endsWith(".sock");
    }

    @Test
    void supervisorSocketPath_filenameContainsVersion() {
        String filename = SocketPaths.supervisorSocketPath().getFileName().toString();
        String version = BuildInfo.version();

        assertThat(filename).isEqualTo("supervisor-" + version + ".sock");
    }

    @Test
    void supervisorSocketDirectory_isParentOfSocketPath() {
        assertThat(SocketPaths.supervisorSocketDirectory())
            .isEqualTo(SocketPaths.supervisorSocketPath().getParent());
    }
}
