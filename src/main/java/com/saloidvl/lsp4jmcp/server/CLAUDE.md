# MCP Server

This package wires JDTLS capabilities into MCP tools.

## Class Roles

| Class           | Role                                                                                                                                                  |
|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| `JavaMcpServer` | Creates a `McpSyncServer` with all tool registrations. Accepts arbitrary `InputStream`/`OutputStream` so it works over TCP (worker) or stdio (tests). |
| `McpServerMain` | Provides `serverName()` and `serverVersion()` loaded from `build-info.properties`.                                                                    |

## Tool Registration

All tools are registered in `JavaMcpServer.create(...)`. Each registration is a
`toolCall(Tool, handler)` call on the `McpServer.sync(...)` builder. Handlers delegate to
`JavaTools`.

The set of registered tool names is available via `JavaMcpServer.registeredToolNames()` (used in
tests to verify no tool is accidentally missing).

## Adding a New Tool

1. Add the implementation method to `JavaTools`
2. Add a `toolCall(...)` entry in `JavaMcpServer.create(...)` with a `Tool` definition (name,
   description, JSON schema) and a handler lambda
3. Add the tool name to `REGISTERED_TOOL_NAMES` in `JavaMcpServer`
4. Add a test case in `JavaToolsTest` (unit) and a golden-file fixture in
   `src/test/resources/integration/` for `McpToolsIT`

## Transport

`StdioServerTransportProvider` from the MCP SDK is used, but fed with the TCP socket streams (not
actual `System.in`/`System.out`). This is intentional — the worker receives MCP JSON-RPC over TCP,
not stdio.
