package com.saloidvl.lsp4jmcp.client;

import java.util.List;
import org.eclipse.lsp4j.TypeHierarchyItem;

public record TypeHierarchyData(
    TypeHierarchyItem item,
    List<TypeHierarchyItem> supertypes,
    List<TypeHierarchyItem> subtypes
) {
}
