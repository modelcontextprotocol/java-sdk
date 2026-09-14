# MCP Java SDK Conformance Test Validation Results

Last validated: **2026-08-17** against conformance suite
**`@modelcontextprotocol/conformance@0.2.0-alpha.11`** (SDK at `main`, 2.0.1-SNAPSHOT), targetting
version 2025-11-25 (`--spec-version 2025-11-25`).

## Summary

**Server Tests (active suite):** 73/73 checks passed (31 scenarios, 100%)
**Server Tests (SEP-1613 `json-schema-2020-12`):** 5/5 checks passed (SEP-2106 checks skipped — post-2025-11-25 spec additions)
**Client Tests:** 3/4 scenarios passed; `sse-retry` fails (tracked in `conformance-baseline.yml`)
**Auth Tests:** 14/14 scenarios passing (193 checks, 0 failed, 0 warnings)

Baseline check passed on every run: all failures are expected per
[`conformance-baseline.yml`](conformance-baseline.yml).

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

## Running Tests

### Server (active suite)
```bash
# Build and start server
./mvnw clean install -DskipTests
mvn exec:java -pl conformance-tests/server-servlet \
  -Dexec.mainClass="io.modelcontextprotocol.conformance.server.ConformanceServlet"

# Run tests (in another terminal, from the repo root)
npx @modelcontextprotocol/conformance@0.2.0-alpha.11 server \
  --url http://localhost:8080/mcp --suite active \
  --expected-failures ./conformance-tests/conformance-baseline.yml
```

### Server (SEP-1613 scenario)
```bash
npx @modelcontextprotocol/conformance@0.2.0-alpha.11 server \
  --url http://localhost:8080/mcp --scenario json-schema-2020-12
```

### Client
```bash
for scenario in initialize tools_call elicitation-sep1034-client-defaults sse-retry; do
  npx @modelcontextprotocol/conformance@0.2.0-alpha.11 client \
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
