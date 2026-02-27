# MCP Java SDK Conformance Test Validation Results

## Summary

**Server Tests:** 37/40 passed (92.5%)
**Client Tests:** 3/4 scenarios passed (9/10 checks passed)
**Auth Tests:** 12/14 scenarios fully passing (178 passed, 1 failed, 1 warning, 85.7% scenarios, 98.9% checks)

## Server Test Results

### Passing (37/40)

- **Lifecycle & Utilities (4/4):** initialize, ping, logging-set-level, completion-complete
- **Tools (11/11):** All scenarios including progress notifications ✨
- **Elicitation (10/10):** SEP-1034 defaults (5 checks), SEP-1330 enums (5 checks)
- **Resources (4/6):** list, read-text, read-binary, templates-read
- **Prompts (4/4):** list, simple, with-args, embedded-resource, with-image
- **SSE Transport (2/2):** Multiple streams
- **Security (2/2):** Localhost validation passes, DNS rebinding protection

### Failing (3/40)

1. **resources-subscribe** - Not implemented in SDK
2. **resources-unsubscribe** - Not implemented in SDK

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

**Status: 178 passed, 1 failed, 1 warning across 14 scenarios**

Uses the `client-spring-http-client` module with Spring Security OAuth2 and the [mcp-client-security](https://github.com/springaicommunity/mcp-client-security) library.

### Fully Passing (12/14 scenarios)

- **auth/metadata-default (12/12):** Default metadata discovery
- **auth/metadata-var1 (12/12):** Metadata discovery variant 1
- **auth/metadata-var2 (12/12):** Metadata discovery variant 2
- **auth/metadata-var3 (12/12):** Metadata discovery variant 3
- **auth/scope-from-www-authenticate (13/13):** Scope extraction from WWW-Authenticate header
- **auth/scope-from-scopes-supported (13/13):** Scope extraction from scopes_supported
- **auth/scope-omitted-when-undefined (13/13):** Scope omitted when not defined
- **auth/scope-retry-limit (11/11):** Scope retry limit handling
- **auth/token-endpoint-auth-basic (17/17):** Token endpoint with HTTP Basic auth
- **auth/token-endpoint-auth-post (17/17):** Token endpoint with POST body auth
- **auth/token-endpoint-auth-none (17/17):** Token endpoint with no client auth
- **auth/pre-registration (6/6):** Pre-registered client credentials flow

### Partially Passing (2/14 scenarios)

- **auth/basic-cimd (12/12 + 1 warning):** Basic Client-Initiated Metadata Discovery — all checks pass, minor warning
- **auth/scope-step-up (11/12):** Scope step-up challenge — 1 failure, client does not fully handle scope escalation after initial authorization

## Known Limitations

1. **Resource Subscriptions:** SDK doesn't implement `resources/subscribe` and `resources/unsubscribe` handlers
2. **Client SSE Retry:** Client doesn't parse or respect the `retry:` field, reconnects immediately, and doesn't send Last-Event-ID header
3. **Auth Scope Step-Up:** Client does not fully handle scope step-up challenges where the server requests additional scopes after initial authorization
4. **Auth Basic CIMD:** Minor conformance warning in the basic Client-Initiated Metadata Discovery flow

## Running Tests

### Server
```bash
# Start server
cd conformance-tests/server-servlet
../../mvnw compile exec:java -Dexec.mainClass="io.modelcontextprotocol.conformance.server.ConformanceServlet"

# Run tests (in another terminal)
npx @modelcontextprotocol/conformance server --url http://localhost:8080/mcp --suite active
```

### Client
```bash
# Build
cd conformance-tests/client-jdk-http-client
../../mvnw clean package -DskipTests

# Run all scenarios
for scenario in initialize tools_call elicitation-sep1034-client-defaults sse-retry; do
  npx @modelcontextprotocol/conformance client \
    --command "java -jar target/client-jdk-http-client-1.0.0-SNAPSHOT.jar" \
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
  --command "java -jar target/client-spring-http-client-0.18.0-SNAPSHOT.jar" \
  --suite auth
```

## Recommendations

### High Priority
1. Fix client SSE retry field handling in `HttpClientStreamableHttpTransport`
2. Implement resource subscription handlers in `McpStatelessAsyncServer`
3. Implement CIMD
4. Implement scope step up
