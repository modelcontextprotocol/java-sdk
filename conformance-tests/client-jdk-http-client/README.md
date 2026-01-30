# MCP Conformance Tests - JDK HTTP Client

This module provides a conformance test client implementation for the Java MCP SDK using the JDK HTTP Client with Streamable HTTP transport.

## Overview

The conformance test client is designed to work with the [MCP Conformance Test Framework](https://github.com/modelcontextprotocol/conformance). It validates that the Java MCP SDK client properly implements the MCP specification.

## Architecture

The client reads test scenarios from environment variables and accepts the server URL as a command-line argument, following the conformance framework's conventions:

- **MCP_CONFORMANCE_SCENARIO**: Environment variable specifying which test scenario to run
- **Server URL**: Passed as the last command-line argument

## Supported Scenarios

Currently implemented scenarios:

- **initialize**: Tests the MCP client initialization handshake only
  - ✅ Validates protocol version negotiation
  - ✅ Validates clientInfo (name and version)
  - ✅ Validates proper handling of server capabilities
  - Does NOT call any tools or perform additional operations

- **tools_call**: Tests tool discovery and invocation
  - ✅ Initializes the client
  - ✅ Lists available tools from the server
  - ✅ Calls the `add_numbers` tool with test arguments (a=5, b=3)
  - ✅ Validates the tool result

- **elicitation-sep1034-client-defaults**: Tests client applies default values for omitted elicitation fields (SEP-1034)
  - ✅ Initializes the client
  - ✅ Lists available tools from the server
  - ✅ Calls the `test_client_elicitation_defaults` tool
  - ✅ Validates that the client properly applies default values from JSON schema to elicitation responses (5/5 checks pass)

- **sse-retry**: Tests client respects SSE retry field timing and reconnects properly (SEP-1699)
  - ⚠️ Initializes the client
  - ⚠️ Lists available tools from the server
  - ⚠️ Calls the `test_reconnection` tool which triggers SSE stream closure
  - ✅ Client reconnects after stream closure (PASSING)
  - ❌ Client does not respect retry timing (FAILING)
  - ⚠️ Client does not send Last-Event-ID header (WARNING - SHOULD requirement)

## Building

Build the executable JAR:

```bash
cd conformance-tests/client-jdk-http-client
../../mvnw clean package -DskipTests
```

This creates an executable JAR at:
```
target/client-jdk-http-client-0.18.0-SNAPSHOT.jar
```

## Running Tests

### Using the Conformance Framework

Run a single scenario:

```bash
npx @modelcontextprotocol/conformance client \
  --command "java -jar conformance-tests/client-jdk-http-client/target/client-jdk-http-client-0.18.0-SNAPSHOT.jar" \
  --scenario initialize

npx @modelcontextprotocol/conformance client \
  --command "java -jar conformance-tests/client-jdk-http-client/target/client-jdk-http-client-0.18.0-SNAPSHOT.jar" \
  --scenario tools_call

npx @modelcontextprotocol/conformance client \
  --command "java -jar conformance-tests/client-jdk-http-client/target/client-jdk-http-client-0.18.0-SNAPSHOT.jar" \
  --scenario elicitation-sep1034-client-defaults

npx @modelcontextprotocol/conformance client \
  --command "java -jar conformance-tests/client-jdk-http-client/target/client-jdk-http-client-0.18.0-SNAPSHOT.jar" \
  --scenario sse-retry
```

Run with verbose output:

```bash
npx @modelcontextprotocol/conformance client \
  --command "java -jar conformance-tests/client-jdk-http-client/target/client-jdk-http-client-0.18.0-SNAPSHOT.jar" \
  --scenario initialize \
  --verbose
```

### Manual Testing

You can also run the client manually if you have a test server:

```bash
export MCP_CONFORMANCE_SCENARIO=initialize
java -jar conformance-tests/client-jdk-http-client/target/client-jdk-http-client-0.18.0-SNAPSHOT.jar http://localhost:3000/mcp
```

## Test Results

The conformance framework generates test results showing:

**Current Status (3/4 scenarios passing):**
- ✅ initialize: 1/1 checks passed
- ✅ tools_call: 1/1 checks passed
- ✅ elicitation-sep1034-client-defaults: 5/5 checks passed
- ⚠️ sse-retry: 1/2 checks passed, 1 warning

Test result files are generated in `results/<scenario>-<timestamp>/`:
- `checks.json`: Array of conformance check results with pass/fail status
- `stdout.txt`: Client stdout output
- `stderr.txt`: Client stderr output

### Known Issue: SSE Retry Handling

The `sse-retry` scenario currently fails because:
1. The client treats the SSE `retry:` field as invalid instead of parsing it
2. The client does not implement retry timing (reconnects immediately)
3. The client does not send the Last-Event-ID header on reconnection

This is a known limitation in the `HttpClientStreamableHttpTransport` implementation.

## Next Steps

Future enhancements:

- Fix SSE retry field handling (SEP-1699) to properly parse and respect retry timing
- Implement Last-Event-ID header on reconnection for resumability
- Add auth scenarios (currently excluded as per requirements)
- Implement a comprehensive "everything-client" pattern
- Add to CI/CD pipeline
- Create expected-failures baseline for known issues
