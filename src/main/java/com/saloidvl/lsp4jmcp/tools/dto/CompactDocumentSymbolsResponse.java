package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record CompactDocumentSymbolsResponse(String file, int count, List<CompactSymbolResult> symbols) {
}
