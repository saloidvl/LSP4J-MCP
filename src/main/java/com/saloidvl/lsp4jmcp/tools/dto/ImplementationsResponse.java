package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record ImplementationsResponse(boolean found, int count, List<LocationResult> implementations) {
}
