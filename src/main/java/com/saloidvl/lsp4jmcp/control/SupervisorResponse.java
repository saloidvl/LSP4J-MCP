package com.saloidvl.lsp4jmcp.control;

public record SupervisorResponse(
    boolean ok,
    String message,
    String host,
    Integer port,
    Long workerPid
) {
}
