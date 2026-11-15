# MCP Conformance Tests - Modern Client

Conformance client for the stateless **2026-07-28** MCP revision, built on the synchronous
`io.modelcontextprotocol.modern.client.McpSyncClient` over `HttpClientMcpTransport`. The legacy
(2025-11-25) clients live in [`client-jdk-http-client`](../client-jdk-http-client) and
[`client-spring-http-client`](../client-spring-http-client).

Each scenario uses the client as an application would: list, call, read, get. The protocol details the
suite checks are the client's job, so the scenarios contain none of them.

## Scenarios

| Scenario | What the client does |
|---|---|
| `tools_call` | Lists the tools and calls the first one |
| `request-metadata` | `server/discover`, then `tools/list`; the server rejects the first request with `-32022` |
| `sep-2322-client-request-state` | Continues one call round by round with `callOnce()`, calling another tool in between, and lets the client run the others with its elicitation handler |
| `http-standard-headers` | Lists and uses every tool, resource and prompt |
| `http-custom-headers` | Calls the tools named in `MCP_CONFORMANCE_CONTEXT` with their arguments |
| `http-invalid-tool-headers` | Calls every listed tool; tools with invalid `x-mcp-header` annotations aren't listed |
| `json-schema-ref-no-deref` | Lists the tools |
| `json-schema-2020-12-preservation` | Sends the listed input schema back through `json_schema_echo` |

The `auth/*` scenarios of the 2026-07-28 requirement set aren't covered: the modern client has no OAuth
support of its own, only the HTTP hooks an authorization integration plugs into.

## Running

```bash
./mvnw clean install -DskipTests

npx @modelcontextprotocol/conformance@0.2.0-alpha.12 client \
  --command "java -jar conformance-tests/client-modern/target/client-modern-2.2.0-SNAPSHOT.jar" \
  --scenario http-custom-headers \
  --spec-version 2026-07-28
```

The client reads the scenario from `MCP_CONFORMANCE_SCENARIO`, optional scenario data from
`MCP_CONFORMANCE_CONTEXT`, and the server URL from its last argument. It logs to stderr.

See [VALIDATION_RESULTS.md](../VALIDATION_RESULTS.md) for the latest results.
