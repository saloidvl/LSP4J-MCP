# Tests

## Integration Tests — Black-Box Criterion

[`McpToolsIT`](integration/McpToolsIT.java) is the primary correctness gate for the entire stack. It
launches the real shaded JAR against the fixture workspace (`src/it/fixtures/java-sample/`) via
stdio MCP, waits for JDTLS to reach `ready`, and calls every tool. Results are compared against
golden JSON files in `src/test/resources/integration/`.

**Black-box contract:** McpToolsIT tests the system the same way an MCP client would — over stdio,
through the full launcher→supervisor→worker→jdtls chain. If a feature change passes unit tests but
breaks McpToolsIT, the feature is broken for users.

### When to update McpToolsIT

- Adding a new MCP tool → add a new `@Test` method + golden file in
  `src/test/resources/integration/`
- Changing tool output shape → update the affected golden JSON file
- Changing tool behavior (different results, new fields) → update golden file and verify diff is
  intentional

### Run integration tests

Get the version from `pom.xml` first (`<version>` in the top-level project block), then:

```bash
JDTLS_PATH=/opt/homebrew/bin/jdtls mvn verify -Dit.test=McpToolsIT -Dproject.version=<version>
```

Example with current version:

```bash
JDTLS_PATH=/opt/homebrew/bin/jdtls mvn verify -Dit.test=McpToolsIT -Dproject.version=2.0.4
```

McpToolsIT rebuilds the JAR internally via `mvn package -DskipTests`, so `mvn clean package` is not
required beforehand.

---

## Unit Test Rules

These rules apply to all test classes in this directory. Follow them when writing or reviewing
tests.

### A test must fail when the code under test is broken

Before writing an assertion, ask: **if the production code returned garbage, would this assertion
catch it?** If not, it is not a test — it is noise.

### Tautological mocks are forbidden

Never mock the class under test. Mocking a class and asserting on what the mock returns only proves
Mockito works.

```java
// WRONG — mocks JavaTools, which is the class under test
JavaTools tools = mock(JavaTools.class);

when(tools.findSymbols("Foo")).

thenReturn("{...}");

assertThat(tools.findSymbols("Foo")).

contains("Foo"); // proves nothing
```

```java
// CORRECT — mock the dependency (JdtlsClient), exercise the real JavaTools
JdtlsClient client = mock(JdtlsClient.class);

when(client.findWorkspaceSymbols("Foo")).

thenReturn(List.of(...));
JavaTools tools = new JavaTools(client, workspacePath);
String result = tools.findSymbols("Foo");

assertThat(result).

contains("\"name\":\"Foo\""); // real logic ran
```

### Assert meaningful content, not existence

`isNotNull()`, `isNotEmpty()`, and `isGreaterThan(0)` alone are never sufficient for a structured
response. Verify at least one field that would differ if the logic were wrong.

```java
// WRONG
assertThat(tools.findSymbols("Greeter")).

isNotEmpty();

// CORRECT
assertThat(response.results()).

hasSize(2);

assertThat(response.results().

get(0).

name()).

isEqualTo("Greeter");

assertThat(response.results().

get(0).

kind()).

isEqualTo("Interface");
```

### Do not assert mock return values directly

If a call goes straight to a mock with no production code in between, the assertion is testing
Mockito, not your code.

```java
// WRONG — no JavaTools logic between mock setup and assertion
when(client.findWorkspaceSymbols("X")).

thenReturn(symbols);

assertThat(client.findWorkspaceSymbols("X")).

isEqualTo(symbols);
```

### `verify()` alone is not a correctness assertion

`verify(mock).someMethod(...)` confirms a method was called. It does not verify the result was
correct. Always pair it with an assertion on the return value or side effect.

### Argument matchers: be specific where it matters

Use `any()` for arguments the test genuinely does not care about. Use specific values when the
correctness of the output depends on what was passed. A test that uses `any()` for every argument
will pass even if the wrong data is sent.

### JSON golden files (integration tests)

Golden files in `src/test/resources/integration/` are the source of truth for tool output. When
changing tool output, update the golden file and review the diff — it should match exactly what you
intended to change and nothing else.
