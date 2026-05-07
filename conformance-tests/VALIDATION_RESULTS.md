# MCP Java SDK Conformance Test Validation Results

## Summary

**Server Tests (active suite):** 44/44 passed (31 scenarios, 100%)
**Server Tests (spec 2025-11-25):** 4/4 passed — SEP-1613 `json-schema-2020-12` scenario ✨
**Client Tests:** 3/4 scenarios passed (9/10 checks passed)
**Auth Tests:** 14/15 scenarios fully passing (196 passed, 0 failed, 1 warning, 93.3% scenarios, 99.5% checks)

## Server Test Results

### Active Suite — Passing (31/31 scenarios, 44/44 checks)

- **Lifecycle & Utilities (4/4):** initialize, ping, logging-set-level, completion-complete
- **Tools (13/13):** All scenarios including progress notifications, sampling, elicitation ✨
- **Elicitation (10/10):** SEP-1034 defaults (5 checks), SEP-1330 enums (5 checks)
- **Resources (7/7):** list, read-text, read-binary, templates-read, subscribe, unsubscribe, SEP-2164 resource-not-found
- **Prompts (5/5):** list, simple, with-args, embedded-resource, with-image
- **SSE Transport (2/2):** Multiple streams
- **Security (2/2):** Localhost validation passes, DNS rebinding protection

### Spec 2025-11-25 Scenarios — Passing (1/1 scenario, 4/4 checks)

- **JSON Schema 2020-12 (SEP-1613) (4/4):** ✨
  - `json_schema_2020_12_tool` found
  - `inputSchema.$schema` field preserved
  - `inputSchema.$defs` field preserved
  - `inputSchema.additionalProperties` field preserved

## Client Test Results

### Passing (3/4 scenarios, 9/10 checks)

- **initialize (1/1):** Protocol negotiation, clientInfo, capabilities
- **tools_call (1/1):** Tool discovery and invocation
- **elicitation-sep1034-client-defaults (5/5):** Default values for string, integer, number, enum, boolean

### Partially Passing (1/4 scenarios, 1/2 checks)

- **sse-retry (1/2 + 1 warning):**
  - ✅ Reconnects after stream closure
  - ❌ Does not respect retry timing
  - ⚠️ Does not send Last-Event-ID header (SHOULD requirement)

**Issue:** Client treats `retry:` SSE field as invalid instead of parsing it for reconnection timing.

## Auth Test Results (Spring HTTP Client)

**Status: 196 passed, 0 failed, 1 warning across 15 scenarios**

Uses the `client-spring-http-client` module with Spring Security OAuth2 and the [mcp-client-security](https://github.com/springaicommunity/mcp-client-security) library.

### Fully Passing (14/15 scenarios)

- **auth/metadata-default (13/13):** Default metadata discovery
- **auth/metadata-var1 (13/13):** Metadata discovery variant 1
- **auth/metadata-var2 (13/13):** Metadata discovery variant 2
- **auth/metadata-var3 (13/13):** Metadata discovery variant 3
- **auth/scope-from-www-authenticate (14/14):** Scope extraction from WWW-Authenticate header
- **auth/scope-from-scopes-supported (14/14):** Scope extraction from scopes_supported
- **auth/scope-omitted-when-undefined (14/14):** Scope omitted when not defined
- **auth/scope-step-up (16/16):** Scope step-up challenge
- **auth/scope-retry-limit (11/11):** Scope retry limit handling
- **auth/token-endpoint-auth-basic (18/18):** Token endpoint with HTTP Basic auth
- **auth/token-endpoint-auth-post (18/18):** Token endpoint with POST body auth
- **auth/token-endpoint-auth-none (18/18):** Token endpoint with no client auth
- **auth/resource-mismatch (2/2):** Resource mismatch handling
- **auth/pre-registration (6/6):** Pre-registered client credentials flow

### Partially Passing (1/15 scenarios)

- **auth/basic-cimd (13/13 + 1 warning):** Basic Client-Initiated Metadata Discovery — all checks pass, minor warning

## Known Limitations

1. **Client SSE Retry:** Client doesn't parse or respect the `retry:` field, reconnects immediately, and doesn't send Last-Event-ID header
2. **Auth Basic CIMD:** Minor conformance warning in the basic Client-Initiated Metadata Discovery flow

## Running Tests

### Server (active suite)
```bash
# Start server
./mvnw compile -pl conformance-tests/server-servlet -am exec:java

# Run tests (in another terminal)
npx @modelcontextprotocol/conformance server --url http://localhost:8080/mcp --suite active
```

### Server (spec 2025-11-25 scenarios — SEP-1613)
```bash
# Start server (if not already running)
./mvnw compile -pl conformance-tests/server-servlet -am exec:java

# Run json-schema-2020-12 scenario
cd ../conformance && node --import tsx/esm src/index.ts server \
  --url http://localhost:8080/mcp \
  --scenario json-schema-2020-12
```

### Client
```bash
# Build
cd conformance-tests/client-jdk-http-client
../../mvnw clean package -DskipTests

# Run all scenarios
for scenario in initialize tools_call elicitation-sep1034-client-defaults sse-retry; do
  npx @modelcontextprotocol/conformance client \
    --command "java -jar target/client-jdk-http-client-1.1.0-SNAPSHOT.jar" \
    --scenario $scenario
done
```

### Auth (Spring HTTP Client)

Ensure you run with the conformance testing suite `0.1.15` or higher.

```bash
# Build
cd conformance-tests/client-spring-http-client
../../mvnw clean package -DskipTests

# Run auth suite
npx @modelcontextprotocol/conformance@0.1.15 client \
  --spec-version 2025-11-25 \
  --command "java -jar target/client-spring-http-client-1.1.0-SNAPSHOT.jar" \
  --suite auth
```

## Recommendations

### High Priority
1. Fix client SSE retry field handling in `HttpClientStreamableHttpTransport`
2. Implement CIMD
