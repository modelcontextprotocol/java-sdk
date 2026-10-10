# MCP Conformance Tests - Modern Servlet Server

Conformance server for the stateless **2026-07-28** MCP revision, built on
`io.modelcontextprotocol.modern.server.McpServer` and `HttpServletMcpTransport` in an embedded Tomcat.
The legacy (2025-11-25) server lives in [`server-servlet`](../server-servlet).

See [VALIDATION_RESULTS.md](../VALIDATION_RESULTS.md) for the latest results.

## Running

```bash
./mvnw clean install -DskipTests
./mvnw exec:java -pl conformance-tests/server-servlet-modern
```

The server listens on `http://localhost:8081/mcp` (override the port with `-Dport=<port>`).

```bash
npx @modelcontextprotocol/conformance@0.2.0-alpha.12 server \
  --url http://localhost:8081/mcp --requirements 2026-07-28
```

## Fixtures

Besides the standard `test_*` tools, resources and prompts, the server exposes the fixtures the
2026-07-28 scenarios probe for:

- **SEP-2575 diagnostics:** `test_missing_capability`, `test_streaming_elicitation`, `test_logging_tool`,
  `test_trigger_tool_change`, `test_trigger_prompt_change`
- **SEP-2322 MRTR:** `test_input_required_result_*` tools and the `test_input_required_result_prompt` prompt
- **SEP-1613 / SEP-2106:** `json_schema_2020_12_tool`

SEP-2243 custom headers (`x-mcp-header` / `Mcp-Param-{Name}`) are not supported yet; see
[Known Limitations](../VALIDATION_RESULTS.md#known-limitations).
