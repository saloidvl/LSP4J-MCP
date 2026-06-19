package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record CallsResponse(boolean found, int count, List<CallSiteResult> calls) {
}
