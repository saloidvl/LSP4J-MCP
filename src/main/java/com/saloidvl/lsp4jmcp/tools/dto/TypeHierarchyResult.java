package com.saloidvl.lsp4jmcp.tools.dto;

import java.util.List;

public record TypeHierarchyResult(
    String name,
    String uri,
    List<TypeHierarchyEntry> supertypes,
    List<TypeHierarchyEntry> subtypes
) {
}
