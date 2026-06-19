package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record FindSymbolsResponse(String query, int count, List<SymbolResult> symbols) {
}
