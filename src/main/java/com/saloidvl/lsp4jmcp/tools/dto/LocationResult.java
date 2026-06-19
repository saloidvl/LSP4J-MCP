package com.saloidvl.lsp4jmcp.tools.dto;

public record LocationResult(String file, int startLine, int startColumn, int endLine, int endColumn) {
}
