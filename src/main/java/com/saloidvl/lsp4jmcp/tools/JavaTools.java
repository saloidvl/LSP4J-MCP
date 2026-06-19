package com.saloidvl.lsp4jmcp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.saloidvl.lsp4jmcp.client.DiagnosticsCache;
import com.saloidvl.lsp4jmcp.client.JdtlsClient;
import com.saloidvl.lsp4jmcp.client.TypeHierarchyData;
import com.saloidvl.lsp4jmcp.tools.dto.CallSiteResult;
import com.saloidvl.lsp4jmcp.tools.dto.CallsResponse;
import com.saloidvl.lsp4jmcp.tools.dto.ClasspathResult;
import com.saloidvl.lsp4jmcp.tools.dto.DefinitionResponse;
import com.saloidvl.lsp4jmcp.tools.dto.DiagnosticEntry;
import com.saloidvl.lsp4jmcp.tools.dto.DiagnosticsResponse;
import com.saloidvl.lsp4jmcp.tools.dto.DocumentSymbolResult;
import com.saloidvl.lsp4jmcp.tools.dto.DocumentSymbolsResponse;
import com.saloidvl.lsp4jmcp.tools.dto.FindMethodDeclarationsResponse;
import com.saloidvl.lsp4jmcp.tools.dto.FindSymbolsResponse;
import com.saloidvl.lsp4jmcp.tools.dto.HoverResponse;
import com.saloidvl.lsp4jmcp.tools.dto.ImplementationsResponse;
import com.saloidvl.lsp4jmcp.tools.dto.LocationResult;
import com.saloidvl.lsp4jmcp.tools.dto.MethodDeclarationResult;
import com.saloidvl.lsp4jmcp.tools.dto.ProjectResult;
import com.saloidvl.lsp4jmcp.tools.dto.RangeResult;
import com.saloidvl.lsp4jmcp.tools.dto.ReferencesResponse;
import com.saloidvl.lsp4jmcp.tools.dto.RefreshDiagnosticsResponse;
import com.saloidvl.lsp4jmcp.tools.dto.StackTraceResponse;
import com.saloidvl.lsp4jmcp.tools.dto.SymbolResult;
import com.saloidvl.lsp4jmcp.tools.dto.TypeHierarchyEntry;
import com.saloidvl.lsp4jmcp.tools.dto.TypeHierarchyResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Returns 1-based character position of {@code symbol} on the given line of the file.
     * Matches whole identifiers only: next char after match must not be isJavaIdentifierPart.
     *
     * @param filePath absolute file path
     * @param line 1-based line number
     * @param symbol identifier name to locate (e.g. class or method name)
     * @return 1-based character position of symbol
     * @throws IOException if file cannot be read
     * @throws IllegalArgumentException if symbol not found on the line as a whole identifier
     */
    private int resolveCharacter(String filePath, int line, String symbol) throws IOException {
        String content = Files.readString(Path.of(filePath));
        String[] lines = content.split("\n", -1);
        if (line < 1 || line > lines.length) {
            throw new IllegalArgumentException(
                String.format("Line %d out of range (file has %d lines)", line, lines.length)
            );
        }

        String lineContent = lines[line - 1];

        // Guard: symbol must fit in the line
        if (symbol.length() > lineContent.length()) {
            throw new IllegalArgumentException(
                String.format("Symbol '%s' not found on line %d", symbol, line)
            );
        }

        int startIdx = 0;
        while (startIdx <= lineContent.length() - symbol.length()) {
            int foundIdx = lineContent.indexOf(symbol, startIdx);
            if (foundIdx == -1) {
                break;
            }

            // Check that symbol is a whole identifier
            boolean validStart = (foundIdx == 0 || !Character.isJavaIdentifierPart(lineContent.charAt(foundIdx - 1)));
            int endIdx = foundIdx + symbol.length();
            boolean validEnd = (endIdx >= lineContent.length() || !Character.isJavaIdentifierPart(lineContent.charAt(endIdx)));

            if (validStart && validEnd) {
                return foundIdx + 1; // Convert to 1-based
            }

            startIdx = foundIdx + 1;
        }

        throw new IllegalArgumentException(
            String.format("Symbol '%s' not found on line %d", symbol, line)
        );
    }

    /**
     * Search for symbols (classes, methods, fields) matching a query.
     * <p>
     * This performs a two-step search:
     * 1. First searches workspace symbols (returns classes/interfaces)
     * 2. Then searches document symbols in each matching file for methods/fields
     */
    public String findSymbols(String query) {
        try {
            LOG.debug("Searching for symbols matching: {}", query);
            List<SymbolResult> results = new ArrayList<>();
            Set<String> seen = new HashSet<>(); // "uri:line" keys for deduplication
            String lowerQuery = query.toLowerCase();

            // Step 1: workspace/symbol — finds classes, interfaces, enums, methods, fields
            List<? extends SymbolInformation> workspaceSymbols = client.findWorkspaceSymbols(query);

            // Deduplicate workspace symbols by name + container.
            // Keep entry with non-empty containerName when duplicates exist.
            Map<String, SymbolInformation> deduped = new LinkedHashMap<>();
            for (SymbolInformation sym : workspaceSymbols) {
                String key = sym.getName();
                if (!deduped.containsKey(key)) {
                    deduped.put(key, sym);
                } else {
                    SymbolInformation existing = deduped.get(key);
                    // Replace if current has non-empty container and existing doesn't
                    if ((existing.getContainerName() == null || existing.getContainerName().isEmpty())
                        && sym.getContainerName() != null && !sym.getContainerName().isEmpty()) {
                        deduped.put(key, sym);
                    }
                }
            }
            List<SymbolInformation> deduplicatedSymbols = new ArrayList<>(deduped.values());

            for (SymbolInformation si : deduplicatedSymbols) {
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

            return GSON.toJson(new FindSymbolsResponse(query, results.size(), results));
        } catch (Exception e) {
            LOG.error("Error finding symbols", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Recursively search document symbols for matching names.
     */
    private void searchDocumentSymbols(
        List<? extends DocumentSymbol> symbols, String lowerQuery,
        String uri, List<SymbolResult> results, Set<String> seen) {
        for (DocumentSymbol ds : symbols) {
            if (ds.getName().toLowerCase().contains(lowerQuery)) {
                String key = uri + ":" + ds.getSelectionRange().getStart().getLine();
                if (seen.add(key)) {
                    results.add(new SymbolResult(
                        ds.getName(),
                        ds.getKind().toString(),
                        ds.getDetail(),
                        uriToPath(uri),
                        toMcpLineNumber(ds.getSelectionRange().getStart().getLine()),
                        toMcpLineNumber(ds.getSelectionRange().getStart().getCharacter())
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
            LOG.debug("Finding references at {}:{}:{}", filePath, line, character);

            List<? extends Location> locations =
                client.findReferences(uri, toJdtlsLineNumber(line), toJdtlsLineNumber(character));

            List<LocationResult> results = locations.stream()
                .map(this::toLocationResult)
                .toList();

            return GSON.toJson(new ReferencesResponse(filePath, line, character, results.size(), results));
        } catch (Exception e) {
            LOG.error("Error finding references", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Go to the definition of a symbol at the given file location.
     */
    public String findDefinition(String filePath, int line, Integer character, String symbol) {
        try {
            // Validate: at least one of character or symbol must be present
            if (character == null && symbol == null) {
                Map<String, Object> error = Map.of(
                    "error", "One of 'character' or 'symbol' is required"
                );
                return GSON.toJson(error);
            }

            // Resolve character from symbol if provided
            int resolvedChar;
            boolean positionResolved = false;

            if (symbol != null) {
                try {
                    resolvedChar = resolveCharacter(filePath, line, symbol);
                    // resolveCharacter validates symbol is on an identifier boundary; position is resolved
                    positionResolved = true;
                } catch (IOException e) {
                    // File cannot be read — return error immediately
                    Map<String, Object> error = Map.of(
                        "error", "Cannot read file: " + e.getMessage()
                    );
                    return GSON.toJson(error);
                } catch (IllegalArgumentException e) {
                    // Symbol not found on line
                    Map<String, Object> error = Map.of(
                        "error", e.getMessage()
                    );
                    return GSON.toJson(error);
                }
            } else {
                // character was provided; validate it points to an identifier (requires file read)
                resolvedChar = character; // guaranteed non-null by validation above
                try {
                    String content = Files.readString(Path.of(filePath));
                    String[] lines = content.split("\n", -1);
                    if (line >= 1 && line <= lines.length) {
                        String lineContent = lines[line - 1];
                        if (resolvedChar >= 1 && resolvedChar <= lineContent.length()) {
                            char ch = lineContent.charAt(resolvedChar - 1); // Convert to 0-based
                            positionResolved = Character.isJavaIdentifierPart(ch);
                        }
                    }
                } catch (IOException e) {
                    // File read failed; positionResolved remains false
                }
            }

            // If position not resolved, return early with empty definitions
            if (!positionResolved) {
                DefinitionResponse response = new DefinitionResponse(
                    filePath, line, resolvedChar, false, Collections.emptyList()
                );
                return GSON.toJson(response);
            }

            String uri = toUri(filePath);
            LOG.debug("Finding definition at {}:{}:{}", filePath, line, resolvedChar);

            List<? extends Location> locations =
                client.findDefinition(uri, toJdtlsLineNumber(line), toJdtlsLineNumber(resolvedChar));

            List<LocationResult> results = locations.stream()
                .map(this::toLocationResult)
                .toList();

            DefinitionResponse response = new DefinitionResponse(
                filePath, line, resolvedChar, true, results
            );
            return GSON.toJson(response);
        } catch (Exception e) {
            LOG.error("Error finding definition", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String findImplementations(String filePath, int line, int character) {
        try {
            String uri = toUri(filePath);
            LOG.debug("Finding implementations at {}:{}:{}", filePath, line, character);

            List<? extends Location> locations =
                client.findImplementations(uri, toJdtlsLineNumber(line), toJdtlsLineNumber(character));
            List<LocationResult> results = locations.stream().map(this::toLocationResult).toList();
            return GSON.toJson(new ImplementationsResponse(!results.isEmpty(), results.size(), results));
        } catch (Exception e) {
            LOG.error("Error finding implementations", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String getHover(String filePath, int line, Integer character, String symbol) {
        try {
            if (character == null && symbol == null) {
                return GSON.toJson(Map.of("error", "One of 'character' or 'symbol' is required"));
            }
            int resolvedChar;
            if (symbol != null) {
                try {
                    resolvedChar = resolveCharacter(filePath, line, symbol);
                } catch (IOException e) {
                    return GSON.toJson(Map.of("error", "Cannot read file: " + e.getMessage()));
                } catch (IllegalArgumentException e) {
                    return GSON.toJson(Map.of("error", e.getMessage()));
                }
            } else {
                resolvedChar = character;
            }
            String uri = toUri(filePath);
            LOG.debug("Getting hover at {}:{}:{}", filePath, line, resolvedChar);

            Hover hover = client.getHover(uri, toJdtlsLineNumber(line), toJdtlsLineNumber(resolvedChar));
            if (hover == null) {
                return GSON.toJson(new HoverResponse(false, null, null));
            }
            RangeResult range = hover.getRange() != null ? toRangeResult(hover.getRange()) : null;
            return GSON.toJson(new HoverResponse(true, extractHoverContent(hover), range));
        } catch (Exception e) {
            LOG.error("Error getting hover", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String findIncomingCalls(String filePath, int line, Integer character, String symbol) {
        try {
            // Validate: at least one of character or symbol must be present
            if (character == null && symbol == null) {
                Map<String, Object> error = Map.of(
                    "error", "One of 'character' or 'symbol' is required"
                );
                return GSON.toJson(error);
            }

            // Resolve character from symbol if provided
            int resolvedChar;
            if (symbol != null) {
                try {
                    resolvedChar = resolveCharacter(filePath, line, symbol);
                } catch (IOException e) {
                    Map<String, Object> error = Map.of(
                        "error", "Cannot read file: " + e.getMessage()
                    );
                    return GSON.toJson(error);
                } catch (IllegalArgumentException e) {
                    Map<String, Object> error = Map.of(
                        "error", e.getMessage()
                    );
                    return GSON.toJson(error);
                }
            } else {
                resolvedChar = character; // guaranteed non-null by validation above
            }

            String uri = toUri(filePath);
            LOG.debug("Finding incoming calls at {}:{}:{}", filePath, line, resolvedChar);

            List<CallHierarchyIncomingCall> calls =
                client.findIncomingCalls(uri, toJdtlsLineNumber(line), toJdtlsLineNumber(resolvedChar));
            boolean found = calls != null;
            List<CallSiteResult> results = found
                ? calls.stream()
                .flatMap(call -> call.getFromRanges().stream()
                    .map(range -> toCallSiteResult(
                        call.getFrom().getName(),
                        call.getFrom().getDetail(),
                        call.getFrom().getUri(),
                        range)))
                .toList()
                : List.of();
            return GSON.toJson(new CallsResponse(found, results.size(), results));
        } catch (Exception e) {
            LOG.error("Error finding incoming calls", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String findOutgoingCalls(String filePath, int line, Integer character, String symbol) {
        try {
            // Validate: at least one of character or symbol must be present
            if (character == null && symbol == null) {
                Map<String, Object> error = Map.of(
                    "error", "One of 'character' or 'symbol' is required"
                );
                return GSON.toJson(error);
            }

            // Resolve character from symbol if provided
            int resolvedChar;
            if (symbol != null) {
                try {
                    resolvedChar = resolveCharacter(filePath, line, symbol);
                } catch (IOException e) {
                    Map<String, Object> error = Map.of(
                        "error", "Cannot read file: " + e.getMessage()
                    );
                    return GSON.toJson(error);
                } catch (IllegalArgumentException e) {
                    Map<String, Object> error = Map.of(
                        "error", e.getMessage()
                    );
                    return GSON.toJson(error);
                }
            } else {
                resolvedChar = character; // guaranteed non-null by validation above
            }

            String uri = toUri(filePath);
            LOG.debug("Finding outgoing calls at {}:{}:{}", filePath, line, resolvedChar);

            List<CallHierarchyOutgoingCall> calls =
                client.findOutgoingCalls(uri, toJdtlsLineNumber(line), toJdtlsLineNumber(resolvedChar));
            boolean found = calls != null;
            List<CallSiteResult> results = found
                ? calls.stream()
                .flatMap(call -> call.getFromRanges().stream()
                    .map(range -> toCallSiteResult(
                        call.getTo().getName(),
                        call.getTo().getDetail(),
                        uri,
                        range)))
                .toList()
                : List.of();
            return GSON.toJson(new CallsResponse(found, results.size(), results));
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
                return GSON.toJson(new DiagnosticsResponse.ForFile(
                    filePath,
                    entry.map(e -> e.diagnostics().stream().map(this::toDiagnosticEntry).toList()).orElse(List.of()),
                    true,
                    entry.map(e -> e.timestamp().toString()).orElse(null),
                    updatedAtMs
                ));
            }

            if (Boolean.TRUE.equals(summaryOnly)) {
                List<DiagnosticsResponse.FileSummary> files = cache.getSummary().entrySet().stream()
                    .filter(e -> e.getValue().errors() > 0 || e.getValue().warnings() > 0)
                    .map(e -> new DiagnosticsResponse.FileSummary(
                        uriToPath(e.getKey()), e.getValue().errors(), e.getValue().warnings()))
                    .toList();
                return GSON.toJson(new DiagnosticsResponse.Summary(files, true, updatedAtMs));
            }

            List<DiagnosticsResponse.FileEntry> files = cache.getAll().entrySet().stream()
                .map(e -> new DiagnosticsResponse.FileEntry(
                    uriToPath(e.getKey()),
                    e.getValue().diagnostics().stream().map(this::toDiagnosticEntry).toList()))
                .toList();
            return GSON.toJson(new DiagnosticsResponse.Full(files, true, updatedAtMs));
        } catch (Exception e) {
            LOG.error("Error getting diagnostics", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String refreshDiagnostics() {
        try {
            long start = System.currentTimeMillis();
            client.buildIncremental();
            long durationMs = System.currentTimeMillis() - start;
            return GSON.toJson(new RefreshDiagnosticsResponse("ok", durationMs));
        } catch (Exception e) {
            LOG.error("Error refreshing diagnostics", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String resolveStackTrace(String stackFrame) {
        try {
            Object raw = client.resolveStackTraceLocation(stackFrame);
            if (raw == null) {
                return GSON.toJson(new StackTraceResponse(null, null, "not found"));
            }

            JsonElement tree = GSON.toJsonTree(raw);
            String file;
            int line;

            if (tree.isJsonPrimitive()) {
                // JDTLS returned a plain URI string instead of a Location object
                file = uriToPath(tree.getAsString());
                line = extractLineFromStackFrame(stackFrame);
            } else {
                JsonObject obj = tree.getAsJsonObject();
                if (!obj.has("uri")) {
                    return GSON.toJson(new StackTraceResponse(null, null, "not found"));
                }
                file = uriToPath(obj.get("uri").getAsString());
                if (!obj.has("range")) {
                    line = extractLineFromStackFrame(stackFrame);
                } else {
                    line = toMcpLineNumber(obj.getAsJsonObject("range")
                        .getAsJsonObject("start")
                        .get("line").getAsInt());
                }
            }

            if (line == -1) {
                return GSON.toJson(new StackTraceResponse(
                    file,
                    null,
                    "could not extract line number from stack frame"));
            }
            return GSON.toJson(new StackTraceResponse(file, line, null));
        } catch (Exception e) {
            LOG.error("Error resolving stack trace", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    private int extractLineFromStackFrame(String stackFrame) {
        java.util.regex.Matcher m =
            Pattern.compile("\\(\\w+\\.java:(\\d+)\\)").matcher(stackFrame);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Get all symbols defined in a document, flattening any hierarchical structure.
     */
    public String getDocumentSymbols(String filePath) {
        try {
            String uri = toUri(filePath);
            LOG.debug("Getting document symbols for {}", filePath);

            List<? extends DocumentSymbol> symbols = client.getDocumentSymbols(uri);

            List<DocumentSymbolResult> results = new ArrayList<>();
            flattenDocumentSymbols(symbols, results);

            return GSON.toJson(new DocumentSymbolsResponse(filePath, results.size(), results));
        } catch (Exception e) {
            LOG.error("Error getting document symbols", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    private void flattenDocumentSymbols(List<? extends DocumentSymbol> symbols, List<DocumentSymbolResult> out) {
        for (DocumentSymbol ds : symbols) {
            if (SymbolKind.Package.equals(ds.getKind()))
                continue;
            out.add(toDocumentSymbolResult(ds));
            if (ds.getChildren() != null && !ds.getChildren().isEmpty()) {
                flattenDocumentSymbols(ds.getChildren(), out);
            }
        }
    }

    public String findMethodDeclarations(
        String methodName, String searchIn, String packageFilter, Integer parameterCount) {
        try {
            String normalizedSearchIn = searchIn == null ? null : searchIn.toLowerCase();
            if (normalizedSearchIn != null
                && !normalizedSearchIn.equals("interfaces")
                && !normalizedSearchIn.equals("classes")
                && !normalizedSearchIn.equals("all")) {
                return GSON.toJson(Map.of(
                    "error",
                    "Invalid search_in value: '" + searchIn + "'. Expected: interfaces, classes, all"));
            }

            LOG.info("Finding method declarations: {}", methodName);
            List<MethodDeclarationResult> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String lowerMethodName = methodName.toLowerCase();
            Params params = new Params(normalizedSearchIn, packageFilter, parameterCount);

            List<? extends SymbolInformation> methodSymbols = client.findWorkspaceSymbols(methodName);
            Set<String> matchingUris = methodSymbols.stream()
                .map(si -> si.getLocation().getUri())
                .filter(uri -> uri != null && uri.startsWith("file://"))
                .collect(Collectors.toSet());

            for (String uri : matchingUris) {
                try {
                    List<? extends DocumentSymbol> docSymbols = client.getDocumentSymbols(uri);
                    String packageName = extractPackage(docSymbols);
                    if (params.packageFilter() != null && !packageName.startsWith(params.packageFilter())) {
                        continue;
                    }
                    findMethodDeclarationsInDoc(
                        docSymbols, lowerMethodName, uri, "", null, params, results, seen);
                } catch (Exception e) {
                    LOG.debug("Could not get document symbols for {}: {}", uri, e.getMessage());
                }
            }

            if (results.isEmpty()) {
                LOG.info("Workspace symbol search found no methods for '{}', scanning source files", methodName);
                scanSourceFilesForMethodDeclarations(lowerMethodName, params, results, seen);
            }

            return GSON.toJson(new FindMethodDeclarationsResponse(methodName, results.size(), results));
        } catch (Exception e) {
            LOG.error("Error finding method declarations", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String decompileClass(String classUri) {
        try {
            LOG.debug("Decompiling class: {}", classUri);
            String result = client.decompileClass(classUri);
            if (result == null || result.isBlank() || "false".equals(result)) {
                return GSON.toJson(Map.of("error", "no source available for: " + classUri));
            }
            return result;
        } catch (Exception e) {
            LOG.error("Error decompiling class", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String getProjects() {
        try {
            LOG.debug("Getting all projects");
            Object raw = client.getProjects();
            if (raw == null) {
                return GSON.toJson(List.of());
            }
            JsonElement tree = GSON.toJsonTree(raw);
            List<ProjectResult> results = new ArrayList<>();
            if (tree.isJsonArray()) {
                for (JsonElement el : tree.getAsJsonArray()) {
                    String uri = el.getAsString();
                    results.add(new ProjectResult(extractLastPathSegment(uri), uri));
                }
            }
            return GSON.toJson(results);
        } catch (Exception e) {
            LOG.error("Error getting projects", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String getClasspath(String filePath) {
        try {
            String fileUri = toUri(filePath);
            LOG.debug("Getting classpath for: {}", filePath);
            Object raw = client.getClasspath(fileUri);
            if (raw == null) {
                return GSON.toJson(new ClasspathResult(List.of(), List.of()));
            }
            JsonElement tree = GSON.toJsonTree(raw);
            List<String> sources = new ArrayList<>();
            List<String> jars = new ArrayList<>();
            if (tree.isJsonObject()) {
                JsonElement sourcePaths = tree.getAsJsonObject().get("org.eclipse.jdt.ls.core.sourcePaths");
                if (sourcePaths != null && sourcePaths.isJsonArray()) {
                    for (JsonElement entry : sourcePaths.getAsJsonArray()) {
                        sources.add(entry.getAsString());
                    }
                }
                JsonElement referencedLibraries =
                    tree.getAsJsonObject().get("org.eclipse.jdt.ls.core.referencedLibraries");
                if (referencedLibraries != null && referencedLibraries.isJsonArray()) {
                    for (JsonElement entry : referencedLibraries.getAsJsonArray()) {
                        jars.add(entry.getAsString());
                    }
                }
            }
            return GSON.toJson(new ClasspathResult(sources, jars));
        } catch (Exception e) {
            LOG.error("Error getting classpath", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String getTypeDefinition(String filePath, int line, Integer character, String symbol) {
        try {
            if (character == null && symbol == null) {
                return GSON.toJson(Map.of("error", "either character or symbol must be provided"));
            }
            int resolvedChar = symbol != null ? resolveCharacter(filePath, line, symbol) : character;
            String uri = toUri(filePath);
            LOG.debug("Getting type definition at {}:{}:{}", filePath, line, resolvedChar);
            List<? extends Location> locations =
                client.getTypeDefinition(uri, toJdtlsLineNumber(line), toJdtlsCharacter(resolvedChar));
            List<LocationResult> results = locations.stream().map(this::toLocationResult).toList();
            return GSON.toJson(new DefinitionResponse(filePath, line, resolvedChar, true, results));
        } catch (IllegalArgumentException e) {
            return GSON.toJson(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("Error getting type definition", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    public String getTypeHierarchy(String filePath, int line, Integer character, String symbol) {
        try {
            if (character == null && symbol == null) {
                return GSON.toJson(Map.of("error", "either character or symbol must be provided"));
            }
            int resolvedChar = symbol != null ? resolveCharacter(filePath, line, symbol) : character;
            String uri = toUri(filePath);
            LOG.debug("Getting type hierarchy at {}:{}:{}", filePath, line, resolvedChar);
            TypeHierarchyData data =
                client.getTypeHierarchy(uri, toJdtlsLineNumber(line), toJdtlsCharacter(resolvedChar));
            if (data == null) {
                return GSON.toJson(Map.of("error", "no type hierarchy at this position"));
            }
            TypeHierarchyEntry root = toTypeHierarchyEntry(data.item());
            List<TypeHierarchyEntry> supertypes = data.supertypes().stream()
                .map(this::toTypeHierarchyEntry).toList();
            List<TypeHierarchyEntry> subtypes = data.subtypes().stream()
                .map(this::toTypeHierarchyEntry).toList();
            return GSON.toJson(new TypeHierarchyResult(root.name(), root.uri(), supertypes, subtypes));
        } catch (IllegalArgumentException e) {
            return GSON.toJson(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("Error getting type hierarchy", e);
            return GSON.toJson(Map.of("error", e.getMessage()));
        }
    }

    private void findMethodDeclarationsInDoc(
        List<? extends DocumentSymbol> symbols,
        String lowerMethodName,
        String uri,
        String containerName,
        SymbolKind containerKind,
        Params params,
        List<MethodDeclarationResult> results,
        Set<String> seen) {
        for (DocumentSymbol ds : symbols) {
            if (ds.getKind() == SymbolKind.Method
                && ds.getName().toLowerCase().contains(lowerMethodName)
                && matchesSearchIn(containerKind, params.searchIn())) {
                int actualCount = extractParamCount(ds.getDetail());
                boolean paramMatch = params.parameterCount() == null
                    || actualCount < 0
                    || actualCount == params.parameterCount();
                if (paramMatch) {
                    String key = uri + ":" + ds.getRange().getStart().getLine();
                    if (seen.add(key)) {
                        results.add(new MethodDeclarationResult(
                            ds.getName(),
                            ds.getKind().toString(),
                            containerName,
                            uriToPath(uri),
                            toMcpLineNumber(ds.getRange().getStart().getLine()),
                            toMcpLineNumber(ds.getRange().getStart().getCharacter()),
                            containerKind.toString()
                        ));
                    }
                }
            }
            if (ds.getChildren() != null && !ds.getChildren().isEmpty()) {
                boolean isTypeDecl = ds.getKind() == SymbolKind.Class
                                  || ds.getKind() == SymbolKind.Interface;
                String childContainer = isTypeDecl ? ds.getName() : containerName;
                SymbolKind childKind    = isTypeDecl ? ds.getKind() : containerKind;
                findMethodDeclarationsInDoc(
                    ds.getChildren(), lowerMethodName, uri,
                    childContainer, childKind, params, results, seen);
            }
        }
    }

    private void scanSourceFilesForMethodDeclarations(
        String lowerMethodName, Params params, List<MethodDeclarationResult> results, Set<String> seen) {
        if (workspaceRoot == null) return;
        try (var walk = Files.walk(workspaceRoot)) {
            List<Path> candidates = walk
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    String s = p.toString();
                    return !s.contains("/target/") && !s.contains("/build/") && !s.contains("/.gradle/");
                })
                .filter(p -> {
                    try {
                        return Files.readString(p).toLowerCase().contains(lowerMethodName);
                    } catch (IOException e) {
                        return false;
                    }
                })
                .toList();

            for (Path p : candidates) {
                String uri = p.toUri().toString();
                try {
                    List<? extends DocumentSymbol> docSymbols = client.getDocumentSymbols(uri);
                    String packageName = extractPackage(docSymbols);
                    if (params.packageFilter() != null && !packageName.startsWith(params.packageFilter())) {
                        continue;
                    }
                    findMethodDeclarationsInDoc(
                        docSymbols, lowerMethodName, uri, "", null, params, results, seen);
                } catch (Exception e) {
                    LOG.debug("Could not scan {}: {}", uri, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not walk workspace for method scan: {}", e.getMessage());
        }
    }

    private record Params(String searchIn, String packageFilter, Integer parameterCount) {}

    private String extractPackage(List<? extends DocumentSymbol> symbols) {
        return symbols.stream()
            .filter(ds -> ds.getKind() == SymbolKind.Package)
            .map(DocumentSymbol::getName)
            .findFirst()
            .orElse("");
    }

    private int extractParamCount(String detail) {
        if (detail == null) return -1;
        int open = detail.indexOf('(');
        int close = detail.lastIndexOf(')');
        if (open < 0 || close <= open) return -1;
        String inner = detail.substring(open + 1, close).trim();
        if (inner.isEmpty()) return 0;
        int count = 1;
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<')
                depth++;
            else if (c == '>')
                depth--;
            else if (c == ',' && depth == 0)
                count++;
        }
        return count;
    }

    private boolean matchesSearchIn(SymbolKind kind, String searchIn) {
        if (kind == null) return false;
        return switch (searchIn == null ? "interfaces" : searchIn) {
            case "classes" -> kind == SymbolKind.Class;
            case "all" -> kind == SymbolKind.Class || kind == SymbolKind.Interface;
            case "interfaces" -> kind == SymbolKind.Interface;
            default -> throw new IllegalStateException("Unexpected searchIn: " + searchIn);
        };
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
            toMcpLineNumber(loc.getRange().getStart().getLine()),
            toMcpLineNumber(loc.getRange().getStart().getCharacter())
        );
    }

    private LocationResult toLocationResult(Location loc) {
        return new LocationResult(
            uriToPath(loc.getUri()),
            toMcpLineNumber(loc.getRange().getStart().getLine()),
            toMcpLineNumber(loc.getRange().getStart().getCharacter()),
            toMcpLineNumber(loc.getRange().getEnd().getLine()),
            toMcpLineNumber(loc.getRange().getEnd().getCharacter())
        );
    }

    private TypeHierarchyEntry toTypeHierarchyEntry(TypeHierarchyItem item) {
        RangeResult range = toRangeResult(item.getRange());
        return new TypeHierarchyEntry(item.getName(), item.getUri(), range);
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

    private RangeResult toRangeResult(Range range) {
        return new RangeResult(
            toMcpLineNumber(range.getStart().getLine()),
            toMcpLineNumber(range.getStart().getCharacter()),
            toMcpLineNumber(range.getEnd().getLine()),
            toMcpLineNumber(range.getEnd().getCharacter())
        );
    }

    private CallSiteResult toCallSiteResult(String name, String container, String locationUri, Range range) {
        return new CallSiteResult(
            name,
            container,
            uriToPath(locationUri),
            toMcpLineNumber(range.getStart().getLine()),
            toMcpLineNumber(range.getStart().getCharacter()),
            toMcpLineNumber(range.getEnd().getLine()),
            toMcpLineNumber(range.getEnd().getCharacter())
        );
    }

    private DiagnosticEntry toDiagnosticEntry(Diagnostic diagnostic) {
        return new DiagnosticEntry(
            diagnostic.getSeverity() != null ? diagnostic.getSeverity().name() : "Unknown",
            diagnostic.getMessage(),
            diagnostic.getCode() != null && diagnostic.getCode().isLeft() ? diagnostic.getCode().getLeft() : null,
            toMcpLineNumber(diagnostic.getRange().getStart().getLine()),
            toMcpLineNumber(diagnostic.getRange().getStart().getCharacter())
        );
    }

    private DocumentSymbolResult toDocumentSymbolResult(DocumentSymbol ds) {
        return new DocumentSymbolResult(
            ds.getName(),
            ds.getKind().toString(),
            ds.getDetail(),
            toMcpLineNumber(ds.getRange().getStart().getLine()),
            toMcpLineNumber(ds.getRange().getEnd().getLine())
        );
    }

    private String uriToPath(String uri) {
        if (uri.startsWith("file://")) {
            try {
                return Path.of(URI.create(uri)).toString();
            } catch (IllegalArgumentException e) {
                return uri.substring(7);
            }
        }
        return uri;
    }

    private int toMcpLineNumber(int lineNumber) {
        return lineNumber + 1;
    }

    private int toJdtlsLineNumber(int lineNumber) {
        return lineNumber - 1;
    }

    private int toJdtlsCharacter(int character) {
        return character - 1;
    }

    private String extractLastPathSegment(String uri) {
        String trimmed = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        int idx = trimmed.lastIndexOf('/');
        return idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
    }

    private String symbolKindName(int value) {
        SymbolKind kind = SymbolKind.forValue(value);
        return kind != null ? kind.name() : String.valueOf(value);
    }

}
