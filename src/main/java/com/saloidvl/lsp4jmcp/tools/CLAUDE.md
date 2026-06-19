# Tools

`JavaTools` implements all MCP tool logic and maps LSP4J results to MCP-friendly JSON.

## Line Number Convention

**Critical:** LSP4J uses 0-based lines and characters. MCP tools accept and return 1-based.

- `toJdtlsLineNumber(n)` = `n - 1` (MCP → LSP)
- `toMcpLineNumber(n)` = `n + 1` (LSP → MCP)

This conversion applies to all `line` and `character` parameters in every tool.

## `find_symbols` Algorithm

Two-step to catch nested symbols (inner class methods) that workspace/symbol misses:

1. `workspace/symbol` query → classes, interfaces, enums, top-level methods
2. For each URI returned in step 1: `textDocument/documentSymbol` → recurse into children matching
   the query

Results deduplicated by `"uri:line"` key.

## `find_method_declarations` Algorithm

1. `workspace/symbol(methodName)` → collect URIs of files with symbol matches
2. For each URI: `textDocument/documentSymbol` → walk tree, collect `Method` children of `Class`/
   `Interface` nodes
3. If step 1+2 yield nothing: walk the workspace filesystem for `.java` files containing the method
   name, then repeat step 2

Filters: `search_in` (interfaces/classes/all), `package_filter` (prefix match on package name from
`Package` document symbol), `parameter_count` (exact match parsed from symbol detail string).

## DTOs

All tool responses are serialized to JSON via Gson. DTO records live in `tools/dto/`:

| DTO                                                                                     | Used by                                      |
|-----------------------------------------------------------------------------------------|----------------------------------------------|
| `FindSymbolsResponse` / `SymbolResult`                                                  | `find_symbols`                               |
| `ReferencesResponse` / `LocationResult`                                                 | `find_references`                            |
| `DefinitionResponse`                                                                    | `find_definition`                            |
| `ImplementationsResponse`                                                               | `find_implementations`                       |
| `DocumentSymbolsResponse` / `DocumentSymbolResult`                                      | `document_symbols`                           |
| `FindMethodDeclarationsResponse` / `MethodDeclarationResult`                            | `find_method_declarations`                   |
| `HoverResponse`                                                                         | `get_hover`                                  |
| `CallsResponse` / `CallSiteResult`                                                      | `find_incoming_calls`, `find_outgoing_calls` |
| `DiagnosticsResponse` (inner classes: `ForFile`, `Summary`, `Full`) / `DiagnosticEntry` | `get_diagnostics`                            |
| `RefreshDiagnosticsResponse`                                                            | `refresh_diagnostics`                        |
| `StackTraceResponse`                                                                    | `resolve_stack_trace`                        |

## Error Handling

Each tool method wraps its body in `try/catch(Exception)` and returns `{"error": "<message>"}` JSON
on failure. Errors are also logged at ERROR level. This keeps all tools non-throwing from the MCP
layer's perspective.
