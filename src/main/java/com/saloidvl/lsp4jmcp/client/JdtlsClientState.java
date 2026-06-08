package com.saloidvl.lsp4jmcp.client;

public enum JdtlsClientState {
    STARTING,
    INDEXING,
    READY,
    RECOVERING_RESTART,
    RECOVERING_REINDEX,
    DEGRADED,
    FAILED
}
