package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record FindMethodDeclarationsResponse(String methodName, int count, List<MethodDeclarationResult> methods) {
}
