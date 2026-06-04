package com.saloidvl.lsp4jmcp.tools;

import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for Java development powered by JDTLS.
 */
public class JavaTools {
    private static final Logger LOG = LoggerFactory.getLogger(JavaTools.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final JdtlsClient client;
    private final Path workspaceRoot;

    public JavaTools(JdtlsClient client, Path workspaceRoot) {
        this.client = client;
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * Search for symbols (classes, methods, fields) matching a query.
     * 
     * This performs a two-step search:
     * 1. First searches workspace symbols (returns classes/interfaces)
     * 2. Then searches document symbols in each matching file for methods/fields
     */
    public String findSymbols(String query) {
        try {
            LOG.info("Searching for symbols matching: {}", query);
            List<SymbolResult> results = new ArrayList<>();
            Set<String> seen = new HashSet<>(); // "uri:line" keys for deduplication
            String lowerQuery = query.toLowerCase();

            // Step 1: workspace/symbol — finds classes, interfaces, enums, methods, fields
            List<? extends SymbolInformation> workspaceSymbols = client.findWorkspaceSymbols(query);
            for (SymbolInformation si : workspaceSymbols) {
                String key = si.getLocation().getUri() + ":" + si.getLocation().getRange().getStart().getLine();
                if (seen.add(key)) {
                    results.add(toSymbolResult(si));
                }
            }

            // Step 2: document symbols only for files already matched in Step 1
            // (catches nested symbols like inner class methods not returned by workspace search)
            Set<String> matchingUris = workspaceSymbols.stream()
                .map(si -> si.getLocation().getUri())
                .collect(Collectors.toSet());

            for (String uri : matchingUris) {
                try {
                    List<? extends DocumentSymbol> docSymbols = client.getDocumentSymbols(uri);
                    searchDocumentSymbols(docSymbols, lowerQuery, uri, results, seen);
                } catch (Exception e) {
                    LOG.debug("Could not get document symbols for {}: {}", uri, e.getMessage());
                }
            }

            return GSON.toJson(Map.of(
                "query", query,
                "count", results.size(),
                "symbols", results
            ));
        } catch (Exception e) {
            LOG.error("Error finding symbols", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * Recursively search document symbols for matching names.
     */
    private void searchDocumentSymbols(List<? extends DocumentSymbol> symbols, String lowerQuery,
                                       String uri, List<SymbolResult> results, Set<String> seen) {
        for (DocumentSymbol ds : symbols) {
            if (ds.getName().toLowerCase().contains(lowerQuery)) {
                String key = uri + ":" + ds.getRange().getStart().getLine();
                if (seen.add(key)) {
                    results.add(new SymbolResult(
                        ds.getName(),
                        ds.getKind().toString(),
                        ds.getDetail(),
                        uriToPath(uri),
                        ds.getRange().getStart().getLine() + 1,
                        ds.getRange().getStart().getCharacter() + 1
                    ));
                }
            }
            if (ds.getChildren() != null && !ds.getChildren().isEmpty()) {
                searchDocumentSymbols(ds.getChildren(), lowerQuery, uri, results, seen);
            }
        }
    }

    /**
     * Find all references to a symbol at the given file location.
     */
    public String findReferences(String filePath, int line, int character) {
        try {
            String uri = toUri(filePath);
            LOG.info("Finding references at {}:{}:{}", filePath, line, character);

            List<? extends Location> locations = client.findReferences(uri, line, character);

            List<LocationResult> results = locations.stream()
                .map(this::toLocationResult)
                .toList();

            return GSON.toJson(Map.of(
                "file", filePath,
                "line", line,
                "character", character,
                "count", results.size(),
                "references", results
            ));
        } catch (Exception e) {
            LOG.error("Error finding references", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Go to the definition of a symbol at the given file location.
     */
    public String findDefinition(String filePath, int line, int character) {
        try {
            String uri = toUri(filePath);
            LOG.info("Finding definition at {}:{}:{}", filePath, line, character);

            List<? extends Location> locations = client.findDefinition(uri, line, character);

            List<LocationResult> results = locations.stream()
                .map(this::toLocationResult)
                .toList();

            return GSON.toJson(Map.of(
                "file", filePath,
                "line", line,
                "character", character,
                "definitions", results
            ));
        } catch (Exception e) {
            LOG.error("Error finding definition", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get all symbols defined in a document.
     */
    public String getDocumentSymbols(String filePath) {
        try {
            String uri = toUri(filePath);
            LOG.info("Getting document symbols for {}", filePath);

            List<? extends DocumentSymbol> symbols = client.getDocumentSymbols(uri);

            List<DocumentSymbolResult> results = symbols.stream()
                .map(this::toDocumentSymbolResult)
                .toList();

            return GSON.toJson(Map.of(
                "file", filePath,
                "count", results.size(),
                "symbols", results
            ));
        } catch (Exception e) {
            LOG.error("Error getting document symbols", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Find all classes/interfaces containing a method with the given name.
     */
    public String findInterfacesWithMethod(String methodName) {
        try {
            LOG.info("Finding classes/interfaces with method: {}", methodName);
            List<SymbolResult> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String lowerMethodName = methodName.toLowerCase();

            // Use workspace symbol search to find methods directly (avoids scanning all files)
            List<? extends SymbolInformation> methodSymbols = client.findWorkspaceSymbols(methodName);
            for (SymbolInformation si : methodSymbols) {
                if (si.getKind() == org.eclipse.lsp4j.SymbolKind.Method
                        && si.getName().toLowerCase().contains(lowerMethodName)) {
                    String key = si.getLocation().getUri() + ":" + si.getLocation().getRange().getStart().getLine();
                    if (seen.add(key)) {
                        results.add(toSymbolResult(si));
                    }
                }
            }

            // Also scan document symbols in matched files to catch nested/overloaded methods
            Set<String> matchingUris = methodSymbols.stream()
                .map(si -> si.getLocation().getUri())
                .collect(Collectors.toSet());

            for (String uri : matchingUris) {
                try {
                    List<? extends DocumentSymbol> docSymbols = client.getDocumentSymbols(uri);
                    findMethodsInDocument(docSymbols, lowerMethodName, uri, "", results, seen);
                } catch (Exception e) {
                    LOG.debug("Could not get document symbols for {}: {}", uri, e.getMessage());
                }
            }

            return GSON.toJson(Map.of(
                "methodName", methodName,
                "count", results.size(),
                "methods", results
            ));
        } catch (Exception e) {
            LOG.error("Error finding interfaces with method", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * Recursively find methods matching the given name in document symbols.
     */
    private void findMethodsInDocument(List<? extends DocumentSymbol> symbols, String lowerMethodName,
                                       String uri, String containerName, List<SymbolResult> results, Set<String> seen) {
        for (DocumentSymbol ds : symbols) {
            if (ds.getKind() == org.eclipse.lsp4j.SymbolKind.Method &&
                    ds.getName().toLowerCase().contains(lowerMethodName)) {
                String key = uri + ":" + ds.getRange().getStart().getLine();
                if (seen.add(key)) {
                    results.add(new SymbolResult(
                        ds.getName(),
                        ds.getKind().toString(),
                        containerName,
                        uriToPath(uri),
                        ds.getRange().getStart().getLine() + 1,
                        ds.getRange().getStart().getCharacter() + 1
                    ));
                }
            }
            if (ds.getChildren() != null && !ds.getChildren().isEmpty()) {
                String childContainer = ds.getKind() == org.eclipse.lsp4j.SymbolKind.Class ||
                                        ds.getKind() == org.eclipse.lsp4j.SymbolKind.Interface
                    ? ds.getName() : containerName;
                findMethodsInDocument(ds.getChildren(), lowerMethodName, uri, childContainer, results, seen);
            }
        }
    }

    private String toUri(String filePath) {
        Path path = Path.of(filePath);
        if (!path.isAbsolute()) {
            path = workspaceRoot.resolve(path);
        }
        return path.toUri().toString();
    }

    private SymbolResult toSymbolResult(SymbolInformation si) {
        Location loc = si.getLocation();
        return new SymbolResult(
            si.getName(),
            si.getKind().toString(),
            si.getContainerName(),
            uriToPath(loc.getUri()),
            loc.getRange().getStart().getLine() + 1,
            loc.getRange().getStart().getCharacter() + 1
        );
    }

    private LocationResult toLocationResult(Location loc) {
        return new LocationResult(
            uriToPath(loc.getUri()),
            loc.getRange().getStart().getLine() + 1,
            loc.getRange().getStart().getCharacter() + 1,
            loc.getRange().getEnd().getLine() + 1,
            loc.getRange().getEnd().getCharacter() + 1
        );
    }

    private DocumentSymbolResult toDocumentSymbolResult(DocumentSymbol ds) {
        return new DocumentSymbolResult(
            ds.getName(),
            ds.getKind().toString(),
            ds.getDetail(),
            ds.getRange().getStart().getLine() + 1,
            ds.getRange().getEnd().getLine() + 1
        );
    }

    private String uriToPath(String uri) {
        if (uri.startsWith("file://")) {
            return uri.substring(7);
        }
        return uri;
    }

    // Result record types for clean JSON output
    public record SymbolResult(
        String name,
        String kind,
        String container,
        String file,
        int line,
        int column
    ) {}

    public record LocationResult(
        String file,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn
    ) {}

    public record DocumentSymbolResult(
        String name,
        String kind,
        String detail,
        int startLine,
        int endLine
    ) {}
}
