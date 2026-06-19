package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record ClasspathResult(List<String> sources, List<String> jars) {
}
