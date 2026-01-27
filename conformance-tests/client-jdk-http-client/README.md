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
  - Validates protocol version negotiation
  - Validates clientInfo (name and version)
  - Validates proper handling of server capabilities
  - Does NOT call any tools or perform additional operations

- **tools_call**: Tests tool discovery and invocation
  - Initializes the client
  - Lists available tools from the server
  - Calls the `add_numbers` tool with test arguments (a=5, b=3)
  - Validates the tool result

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

The conformance framework generates test results in `results/initialize-<timestamp>/`:

- `checks.json`: Array of conformance check results with pass/fail status
- `stdout.txt`: Client stdout output
- `stderr.txt`: Client stderr output

## Implementation Details

### Scenario Separation

The implementation follows a clean separation of concerns:

- **initialize scenario**: Only performs initialization, no additional operations
- **tools_call scenario**: Performs initialization, lists tools, and calls the `add_numbers` tool

This separation ensures that each scenario tests exactly what it's supposed to test without side effects.

### Transport

Uses `HttpClientStreamableHttpTransport` which:
- Implements the latest Streamable HTTP protocol (2025-03-26)
- Uses the standard JDK `HttpClient` (no external HTTP client dependencies)
- Supports protocol version negotiation
- Handles SSE streams for server-to-client notifications

### Client Configuration

The client is configured with:
- Client info: `test-client` version `1.0.0`
- Request timeout: 30 seconds
- Default capabilities (no special features required for basic tests)

### Error Handling

The client:
- Exits with code 0 on success
- Exits with code 1 on failure
- Prints error messages to stderr
- Each scenario handler is independent and self-contained

## Adding New Scenarios

To add support for new scenarios:

1. Add the scenario name to the switch statement in `Main.java`
2. Implement a dedicated handler method (e.g., `runAuthScenario()`, `runElicitationScenario()`)
3. Register the scenario in the available scenarios list in the default case
4. Rebuild the JAR

Example:
```java
case "new-scenario":
    runNewScenario(serverUrl);
    break;
```

## Next Steps

Future enhancements:

- Add more scenarios (auth, elicitation, etc.)
- Implement a comprehensive "everything-client" pattern
- Add to CI/CD pipeline
- Create expected-failures baseline for known issues
