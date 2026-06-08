package com.saloidvl.lsp4jmcp.tools;

import com.saloidvl.lsp4jmcp.client.DiagnosticsCache;
import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for Java development powered by JDTLS.
 */
public class JavaTools {
    private static final Logger LOG = LoggerFactory.getLogger(JavaTools.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

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

    public String findImplementations(String filePath, int line, int character) {
        try {
            String uri = toUri(filePath);
            LOG.info("Finding implementations at {}:{}:{}", filePath, line, character);

            List<? extends Location> locations = client.findImplementations(uri, line - 1, character - 1);
            boolean found = locations != null;
            List<LocationResult> results = found
                ? locations.stream().map(this::toLocationResult).toList()
                : List.of();

            return GSON.toJson(Map.of(
                "found", found,
                "count", results.size(),
                "implementations", results
            ));
        } catch (Exception e) {
            LOG.error("Error finding implementations", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String getHover(String filePath, int line, int character) {
        try {
            String uri = toUri(filePath);
            LOG.info("Getting hover at {}:{}:{}", filePath, line, character);

            Hover hover = client.getHover(uri, line - 1, character - 1);
            Map<String, Object> response = new LinkedHashMap<>();
            if (hover == null) {
                response.put("found", false);
                response.put("content", null);
                return GSON.toJson(response);
            }

            response.put("found", true);
            response.put("content", extractHoverContent(hover));
            if (hover.getRange() != null) {
                response.put("range", toRangeMap(hover.getRange()));
            }
            return GSON.toJson(response);
        } catch (Exception e) {
            LOG.error("Error getting hover", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String findIncomingCalls(String filePath, int line, int character) {
        try {
            String uri = toUri(filePath);
            LOG.info("Finding incoming calls at {}:{}:{}", filePath, line, character);

            List<? extends Location> locations = client.findIncomingCalls(uri, line - 1, character - 1);
            return buildCallsResponse(locations);
        } catch (Exception e) {
            LOG.error("Error finding incoming calls", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String findOutgoingCalls(String filePath, int line, int character) {
        try {
            String uri = toUri(filePath);
            LOG.info("Finding outgoing calls at {}:{}:{}", filePath, line, character);

            List<? extends Location> locations = client.findOutgoingCalls(uri, line - 1, character - 1);
            return buildCallsResponse(locations);
        } catch (Exception e) {
            LOG.error("Error finding outgoing calls", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String getDiagnostics(Boolean summaryOnly, String filePath) {
        try {
            DiagnosticsCache cache = client.getDiagnosticsCache();
            long updatedAtMs = cache.getLastUpdatedMs();

            if (filePath != null) {
                String uri = toUri(filePath);
                Optional<DiagnosticsCache.Entry> entry = cache.getForUri(uri);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("file", filePath);
                response.put("diagnostics", entry.map(e -> e.diagnostics()).orElse(List.of())
                    .stream().map(this::toDiagnosticMap).toList());
                response.put("cached", true);
                response.put("timestamp", entry.map(e -> e.timestamp().toString()).orElse(null));
                response.put("cache_updated_at_ms", updatedAtMs);
                return GSON.toJson(response);
            }

            if (Boolean.TRUE.equals(summaryOnly)) {
                List<Map<String, Object>> files = cache.getSummary().entrySet().stream()
                    .map(e -> {
                        Map<String, Object> file = new LinkedHashMap<>();
                        file.put("file", uriToPath(e.getKey()));
                        file.put("errors", e.getValue().errors());
                        file.put("warnings", e.getValue().warnings());
                        return file;
                    }).toList();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("files", files);
                response.put("cached", true);
                response.put("cache_updated_at_ms", updatedAtMs);
                return GSON.toJson(response);
            }

            List<Map<String, Object>> files = cache.getAll().entrySet().stream()
                .map(e -> {
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("file", uriToPath(e.getKey()));
                    file.put("diagnostics", e.getValue().diagnostics().stream()
                        .map(this::toDiagnosticMap).toList());
                    return file;
                }).toList();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("files", files);
            response.put("cached", true);
            response.put("cache_updated_at_ms", updatedAtMs);
            return GSON.toJson(response);
        } catch (Exception e) {
            LOG.error("Error getting diagnostics", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String refreshDiagnostics() {
        try {
            long start = System.currentTimeMillis();
            client.buildWorkspace();
            long durationMs = System.currentTimeMillis() - start;
            return GSON.toJson(Map.of(
                "status", "ok",
                "build_duration_ms", durationMs
            ));
        } catch (Exception e) {
            LOG.error("Error refreshing diagnostics", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String resolveStackTrace(String stackFrame) {
        try {
            Object raw = client.resolveStackTraceLocation(stackFrame);
            if (raw == null) {
                return GSON.toJson(notFoundStackTraceResponse());
            }

            JsonObject obj = GSON.toJsonTree(raw).getAsJsonObject();
            if (!obj.has("uri")) {
                return GSON.toJson(notFoundStackTraceResponse());
            }

            String file = uriToPath(obj.get("uri").getAsString());
            int line = obj.getAsJsonObject("range")
                .getAsJsonObject("start")
                .get("line").getAsInt() + 1;
            return GSON.toJson(Map.of("file", file, "line", line));
        } catch (Exception e) {
            LOG.error("Error resolving stack trace", e);
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

    private String extractHoverContent(Hover hover) {
        Either<List<Either<String, MarkedString>>, MarkupContent> contents = hover.getContents();
        if (contents == null) {
            return "";
        }
        if (contents.isRight()) {
            return contents.getRight().getValue();
        }
        return contents.getLeft().stream()
            .map(e -> e.isLeft() ? e.getLeft() : e.getRight().getValue())
            .collect(Collectors.joining("\n\n"));
    }

    private Map<String, Integer> toRangeMap(Range range) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("startLine", range.getStart().getLine() + 1);
        result.put("startCharacter", range.getStart().getCharacter() + 1);
        result.put("endLine", range.getEnd().getLine() + 1);
        result.put("endCharacter", range.getEnd().getCharacter() + 1);
        return result;
    }

    private String buildCallsResponse(List<? extends Location> locations) {
        boolean found = locations != null;
        List<LocationResult> results = found
            ? locations.stream().map(this::toLocationResult).toList()
            : List.of();
        return GSON.toJson(Map.of(
            "found", found,
            "count", results.size(),
            "calls", results
        ));
    }

    private Map<String, Object> toDiagnosticMap(Diagnostic diagnostic) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("severity", diagnostic.getSeverity() != null ? diagnostic.getSeverity().name() : "Unknown");
        result.put("message", diagnostic.getMessage());
        result.put("line", diagnostic.getRange().getStart().getLine() + 1);
        result.put("character", diagnostic.getRange().getStart().getCharacter() + 1);
        if (diagnostic.getCode() != null && diagnostic.getCode().isLeft()) {
            result.put("code", diagnostic.getCode().getLeft());
        }
        return result;
    }

    private Map<String, Object> notFoundStackTraceResponse() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", null);
        result.put("line", null);
        result.put("message", "not found");
        return result;
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
