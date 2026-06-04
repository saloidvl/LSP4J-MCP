package com.saloidvl.lsp4jmcp.control;

public record SupervisorResponse(
    boolean ok,
    String message,
    String leaseId,
    String host,
    Integer port,
    Long workerPid
) {
}
