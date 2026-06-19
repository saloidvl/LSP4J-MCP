package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record ReferencesResponse(String file, int line, int character, int count, List<LocationResult> references) {
}
