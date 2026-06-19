package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record DefinitionResponse(String file, int line, int character, boolean position_resolved, List<LocationResult> definitions) {
}
