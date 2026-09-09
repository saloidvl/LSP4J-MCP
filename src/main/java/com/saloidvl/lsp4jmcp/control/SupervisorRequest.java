package com.saloidvl.lsp4jmcp.control;

import com.saloidvl.lsp4jmcp.config.JdtlsSettingsInputs;

public record SupervisorRequest(
    SupervisorCommand command,
    String repoId,
    String workspacePath,
    String jdtlsCommand,
    JdtlsSettingsInputs settingsInputs
) {
}
