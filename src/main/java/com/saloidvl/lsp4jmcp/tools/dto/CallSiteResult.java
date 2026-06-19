package com.saloidvl.lsp4jmcp.tools.dto;

public record CallSiteResult(
    String name,
    String container,
    String file,
    int startLine,
    int startColumn,
    int endLine,
    int endColumn
) {
}