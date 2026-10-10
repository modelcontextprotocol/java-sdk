# MCP Java SDK Conformance Test Validation Results

Last validated: **2026-10-02** against conformance suite
**`@modelcontextprotocol/conformance@0.2.0-alpha.12`** (2.1.0-SNAPSHOT), targeting version 2025-11-25
(`--spec-version 2025-11-25`) for the legacy server and clients, and the 2026-07-28 requirement set
(`--requirements 2026-07-28`) for the modern server. Auth results below were last validated with
`0.2.0-alpha.11`.

## Summary

**Modern Server Tests (2026-07-28 requirements):** 37/37 required scenarios passed (`server-servlet-modern`)
**Server Tests (active suite):** 73/73 checks passed (31 scenarios, 100%)
**Server Tests (SEP-1613 `json-schema-2020-12`):** 5/5 checks passed (SEP-2106 checks skipped — post-2025-11-25 spec additions)
**Client Tests:** 3/4 scenarios passed; `sse-retry` fails (tracked in `conformance-baseline.yml`)
**Auth Tests:** 14/14 scenarios passing (193 checks, 0 failed, 0 warnings)

Baseline check passed on every run: all failures are expected per
[`conformance-baseline.yml`](conformance-baseline.yml).

## Modern Server Test Results (2026-07-28)

The `server-servlet-modern` module serves the stateless 2026-07-28 revision with
`io.modelcontextprotocol.modern.server.McpServer` over `HttpServletMcpTransport`.

### Required — Passing (37/37 scenarios)

- **Stateless lifecycle (SEP-2575):** `_meta` validation, `server/discover`, version negotiation,
  `MCP-Protocol-Version`/`Mcp-Method` header mismatch, `-32021` capability enforcement, removed
  methods answered `404`/`-32601`, `subscriptions/listen` acknowledgement and filtering
- **Tools, Resources, Prompts, Completion:** all content types, progress, resource templates,
  SEP-2164 not-found errors
- **Caching (SEP-2549):** `ttlMs`/`cacheScope` on list results and `resources/read`
- **InputRequiredResult / MRTR (SEP-2322):** all 14 scenarios, including multi-round, tampered
  `requestState` and capability checks
- **Security:** DNS rebinding protection, SSE streams

### Not scored for 2026-07-28

These run but don't count: the frozen requirement set marks extensions and anything that was pending
in the anchor release (`0.2.0-alpha.10`) as `not_scored`. Pending scenarios are still spec requirements.

- **Passing:** `json-schema-2020-12` (8/8 checks, including the SEP-2106 `allOf`/`anyOf`,
  `if`/`then`/`else` and `$anchor` checks), `http-header-validation` (14/14)
- **Failing — `http-custom-header-server-validation` (SEP-2243 custom headers):** not implemented,
  see [Known Limitations](#known-limitations)
- **Failing — tasks extension (`tasks-*`, SEP-2663):** not implemented

## Server Test Results

### Active Suite — Passing (31/31 scenarios, 73/73 checks)

- **Lifecycle & Utilities:** initialize, ping, logging-set-level, completion-complete
- **Tools (13/13):** all scenarios including progress notifications, sampling, elicitation
- **Elicitation:** SEP-1034 defaults (6 checks), SEP-1330 enums (6 checks)
- **Resources:** list, read-text, read-binary, templates-read, subscribe, unsubscribe
- **Prompts:** list, simple, with-args, embedded-resource, with-image
- **SSE Transport:** multiple streams
- **Security:** DNS rebinding protection

### SEP-1613 — JSON Schema 2020-12 (5/5 checks)

- `json_schema_2020_12_tool` found; `$schema`, `$defs`, and `additionalProperties`
  fields preserved; every JSON-RPC message valid per the spec JSON schema for the
  negotiated spec version (`wire-schema-valid`)
- SEP-2106 checks (composition/conditional/anchor keywords) reported SKIPPED:
  they postdate the 2025-11-25 spec release and are excluded from scoring

## Client Test Results

### Passing (3/4 scenarios)

- **initialize (1/1):** protocol negotiation, clientInfo, capabilities
- **tools_call (2/2):** tool discovery and invocation
- **elicitation-sep1034-client-defaults (5/5):** default values for string, integer, number, enum, boolean

### Failing — in baseline (1/4 scenarios)

- **sse-retry:** client does not parse/respect the `retry:` SSE field timing and
  does not send the `Last-Event-ID` header (SHOULD requirement). Expected failure,
  listed in `conformance-baseline.yml`.

## Auth Test Results (Spring HTTP Client)

**Status: 193 checks passed, 0 failed, 0 warnings across 14 scenarios**

Uses the `client-spring-http-client` module with Spring Security OAuth2 and the
[mcp-client-security](https://github.com/springaicommunity/mcp-client-security) library.

Fully passing: metadata-default, metadata-var1/2/3, basic-cimd,
scope-from-www-authenticate, scope-from-scopes-supported, scope-omitted-when-undefined,
scope-step-up, scope-retry-limit, token-endpoint-auth-basic/post/none, pre-registration.

Note: `auth/resource-mismatch` (present in earlier suite versions) is no longer part
of the 0.2.0-alpha auth suite.

## Known Limitations

1. **Client SSE Retry:** client doesn't parse or respect the `retry:` field,
   reconnects immediately, and doesn't send the `Last-Event-ID` header
2. **Modern server: SEP-2243 custom headers (`Mcp-Param-{Name}`) are not supported.** SEP-2243 is part
   of 2026-07-28 and the server-side requirements are MUSTs; only the scenario's pending status keeps
   it out of the score. Today the five custom-header checks report "not testable" because no tool
   carries `x-mcp-header`. Adding such a tool would turn them into real failures, because nothing
   validates the headers yet. Supporting it needs SDK work:
   - **Tool definitions:** `Tool.inputSchema` is a free-form map, so `x-mcp-header` can already be
     written, but nothing enforces the definition rules: value non-empty, ASCII without space or `:`,
     case-insensitively unique per tool, only on `integer`/`string`/`boolean` parameters (not `number`).
   - **Request validation on `tools/call`:** for each designated parameter, Base64-decode
     `=?base64?…?=` values, check the header matches the body value (integers as decimal strings,
     booleans as `true`/`false`), reject headers with invalid characters, don't expect a header when
     the value is null or omitted, and reject a missing required parameter. Failures are answered with
     `400` and `-32020`.
   - **Where it lives:** the check needs the called tool's `inputSchema`, which
     `HttpServletMcpTransport` doesn't have. `ToolsFeature` already looks the `Tool` up through
     `McpSyncToolRepository#find` / `McpAsyncToolRepository#find` and validates the arguments against
     `inputSchema` before calling the tool, so the header check fits there. What's missing is a way to
     get the raw `Mcp-Param-*` headers from the transport to the feature (e.g. through the
     `McpTransportContext` on `McpRequestContext`).

## Running Tests

### Modern Server (2026-07-28)
```bash
./mvnw clean install -DskipTests
./mvnw exec:java -pl conformance-tests/server-servlet-modern

# In another terminal
npx @modelcontextprotocol/conformance@0.2.0-alpha.12 server \
  --url http://localhost:8081/mcp --requirements 2026-07-28
```

### Server (active suite)
```bash
# Build and start server
./mvnw clean install -DskipTests
mvn exec:java -pl conformance-tests/server-servlet \
  -Dexec.mainClass="io.modelcontextprotocol.conformance.server.ConformanceServlet"

# Run tests (in another terminal, from the repo root)
npx @modelcontextprotocol/conformance@0.2.0-alpha.12 server \
  --url http://localhost:8080/mcp --suite active --spec-version 2025-11-25 \
  --expected-failures ./conformance-tests/conformance-baseline.yml
```

### Server (SEP-1613 scenario)
```bash
npx @modelcontextprotocol/conformance@0.2.0-alpha.12 server \
  --url http://localhost:8080/mcp --scenario json-schema-2020-12 --spec-version 2025-11-25
```

### Client
```bash
for scenario in initialize tools_call elicitation-sep1034-client-defaults sse-retry; do
  npx @modelcontextprotocol/conformance@0.2.0-alpha.12 client --spec-version 2025-11-25 \
    --command "java -jar conformance-tests/client-jdk-http-client/target/client-jdk-http-client-*.jar" \
    --scenario $scenario \
    --expected-failures ./conformance-tests/conformance-baseline.yml
done
```

### Auth (Spring HTTP Client)
```bash
npx @modelcontextprotocol/conformance@0.2.0-alpha.11 client \
  --spec-version 2025-11-25 \
  --command "java -jar conformance-tests/client-spring-http-client/target/client-spring-http-client-*.jar" \
  --suite auth \
  --expected-failures ./conformance-tests/conformance-baseline.yml
```

## Recommendations

### High Priority
1. Fix client SSE retry field handling in `HttpClientStreamableHttpTransport`
