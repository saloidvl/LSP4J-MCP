package com.saloidvl.lsp4jmcp.tools.dto;

public record DocumentSymbolResult(String name, String kind, String detail, int startLine, int endLine) {
}
