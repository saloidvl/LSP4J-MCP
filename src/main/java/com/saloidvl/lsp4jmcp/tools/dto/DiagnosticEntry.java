package com.saloidvl.lsp4jmcp.tools.dto;

import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public record DiagnosticEntry(
    String severity,
    Either<String, MarkupContent> message,
    String code,
    int line,
    int character
) {
}
