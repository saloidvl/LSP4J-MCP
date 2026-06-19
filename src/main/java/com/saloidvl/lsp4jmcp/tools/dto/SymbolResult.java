package com.saloidvl.lsp4jmcp.tools.dto;

public record SymbolResult(String name, String kind, String container, String file, int line, int column) {
}
