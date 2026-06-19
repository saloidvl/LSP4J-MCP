package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record DocumentSymbolsResponse(String file, int count, List<DocumentSymbolResult> symbols) {
}
