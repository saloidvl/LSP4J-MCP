package com.saloidvl.lsp4jmcp.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoTest {

    @Test
    void version_returnsNonBlankString() {
        assertThat(BuildInfo.version()).isNotBlank();
    }

    @Test
    void version_matchesSemanticVersioningPattern() {
        assertThat(BuildInfo.version()).matches("\\d+\\.\\d+\\.\\d+.*");
    }
}
