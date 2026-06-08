package com.saloidvl.lsp4jmcp.control;

public record SupervisorRequest(
    SupervisorCommand command,
    String repoId,
    String workspacePath,
    String jdtlsCommand,
    String leaseId
) {
}
