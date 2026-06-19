package com.saloidvl.lsp4jmcp.tools.dto;

public record HoverResponse(boolean found, String content, RangeResult range) {
}
