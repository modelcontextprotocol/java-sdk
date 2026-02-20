# MCP Conformance Tests - Spring HTTP Client (Auth Suite)

This module provides a conformance test client implementation for the Java MCP SDK's **auth** suite.

OAuth2 support is not implemented in the SDK itself, but we provide hooks to implement the Authorization section of the specification. One such implementation is done in Spring, with Sprign AI and the [mcp-client-security](https://github.com/springaicommunity/mcp-client-security) library.

This is a Spring web application, we interact with it through a normal HTTP-client that follows redirects and performs OAuth2 authorization flows.

## Overview

The conformance test client is designed to work with the [MCP Conformance Test Framework](https://github.com/modelcontextprotocol/conformance). It validates that the Java MCP SDK client, combined with Spring Security's OAuth2 support, properly implements the MCP authorization specification.

Test with @modelcontextprotocol/conformance@0.1.15.

## Conformance Test Results

**Status: 178 passed, 1 failed, 1 warning across 14 scenarios**

| Scenario | Result | Details |
|---|---|---|
| auth/metadata-default | ✅ Pass | 12/12 |
| auth/metadata-var1 | ✅ Pass | 12/12 |
| auth/metadata-var2 | ✅ Pass | 12/12 |
| auth/metadata-var3 | ✅ Pass | 12/12 |
| auth/basic-cimd | ⚠️ Warning | 12/12 passed, 1 warning |
| auth/scope-from-www-authenticate | ✅ Pass | 13/13 |
| auth/scope-from-scopes-supported | ✅ Pass | 13/13 |
| auth/scope-omitted-when-undefined | ✅ Pass | 13/13 |
| auth/scope-step-up | ❌ Fail | 11/12 (1 failed) |
| auth/scope-retry-limit | ✅ Pass | 11/11 |
| auth/token-endpoint-auth-basic | ✅ Pass | 17/17 |
| auth/token-endpoint-auth-post | ✅ Pass | 17/17 |
| auth/token-endpoint-auth-none | ✅ Pass | 17/17 |
| auth/pre-registration | ✅ Pass | 6/6 |

See [VALIDATION_RESULTS.md](../VALIDATION_RESULTS.md) for the full project validation results.

## Architecture

The client is a Spring Boot application that reads test scenarios from environment variables and accepts the server URL as a command-line argument, following the conformance framework's conventions:

- **MCP_CONFORMANCE_SCENARIO**: Environment variable specifying which test scenario to run
- **MCP_CONFORMANCE_CONTEXT**: Environment variable with JSON context (used by `auth/pre-registration`)
- **Server URL**: Passed as the last command-line argument

### Scenario Routing

The application uses Spring's conditional configuration to select the appropriate scenario at startup:

- **`DefaultConfiguration`** — Activated for all scenarios except `auth/pre-registration`. Uses the OAuth2 Authorization Code flow with dynamic client registration via `McpClientOAuth2Configurer`.
- **`PreRegistrationConfiguration`** — Activated only for `auth/pre-registration`. Uses the Client Credentials flow with pre-registered client credentials read from `MCP_CONFORMANCE_CONTEXT`.

### Key Dependencies

- **Spring Boot 4.0** with Spring Security OAuth2 Client
- **Spring AI MCP Client** (`spring-ai-starter-mcp-client`)
- **mcp-client-security** — Community library providing MCP-specific OAuth2 integration (metadata discovery, dynamic client registration, transport context)

## Building

Build the executable JAR:

```bash
cd conformance-tests/client-spring-http-client
../../mvnw clean package -DskipTests
```

This creates an executable JAR at:
```
target/client-spring-http-client-0.18.0-SNAPSHOT.jar
```

## Running Tests

### Using the Conformance Framework

Run the full auth suite:

```bash
npx @modelcontextprotocol/conformance@0.1.15 client \
  --spec-version 2025-11-25 \
  --command "java -jar conformance-tests/client-spring-http-client/target/client-spring-http-client-0.18.0-SNAPSHOT.jar" \
  --suite auth
```

Run a single scenario:

```bash
npx @modelcontextprotocol/conformance@0.1.15 client \
  --spec-version 2025-11-25 \
  --command "java -jar conformance-tests/client-spring-http-client/target/client-spring-http-client-0.18.0-SNAPSHOT.jar" \
  --scenario auth/metadata-default
```

Run with verbose output:

```bash
npx @modelcontextprotocol/conformance@0.1.15 client \
  --spec-version 2025-11-25 \
  --command "java -jar conformance-tests/client-spring-http-client/target/client-spring-http-client-0.18.0-SNAPSHOT.jar" \
  --scenario auth/metadata-default \
  --verbose
```

### Manual Testing

You can also run the client manually if you have a test server:

```bash
export MCP_CONFORMANCE_SCENARIO=auth/metadata-default
java -jar conformance-tests/client-spring-http-client/target/client-spring-http-client-0.18.0-SNAPSHOT.jar http://localhost:3000/mcp
```

## Known Issues

1. **auth/scope-step-up** (1 failure) — The client does not fully handle scope step-up challenges where the server requests additional scopes after initial authorization.
2. **auth/basic-cimd** (1 warning) — Minor conformance warning in the basic Client-Initiated Metadata Discovery flow.

## References

- [MCP Specification — Authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [MCP Conformance Tests](https://github.com/modelcontextprotocol/conformance)
- [mcp-client-security Library](https://github.com/springaicommunity/mcp-client-security)
- [SDK Integration Guide](https://github.com/modelcontextprotocol/conformance/blob/main/SDK_INTEGRATION.md)
