# MCP Conformance Tests - Servlet Server

This module contains a comprehensive MCP (Model Context Protocol) server implementation for conformance testing using the servlet stack with an embedded Tomcat server and streamable HTTP transport.

## Conformance Test Results

**Status: 36 out of 40 tests passing (90%)**

The server has been validated against the official [MCP conformance test suite](https://github.com/modelcontextprotocol/conformance). See [VALIDATION_RESULTS.md](../VALIDATION_RESULTS.md) for detailed results.

### What's Implemented

✅ **Lifecycle & Utilities** (4/4)
- Server initialization, ping, logging, completion

✅ **Tools** (10/11)
- Text, image, audio, embedded resources, mixed content
- Logging, error handling, sampling, elicitation
- ⚠️ Progress notifications (SDK issue)

✅ **Elicitation** (10/10)
- SEP-1034: Default values for all primitive types
- SEP-1330: All enum schema variants

✅ **Resources** (4/6)
- List, read text/binary, templates
- ⚠️ Subscribe/unsubscribe (SDK limitation)

✅ **Prompts** (4/4)
- Simple, parameterized, embedded resources, images

✅ **SSE Transport** (2/2)
- Multiple streams support

⚠️ **Security** (1/2)
- ⚠️ DNS rebinding protection (SDK limitation)

## Features

- Embedded Tomcat servlet container
- MCP server using HttpServletStreamableServerTransportProvider
- Comprehensive test coverage with 15+ tools
- Streamable HTTP transport with SSE on `/mcp` endpoint
- Support for all MCP content types (text, image, audio, resources)
- Advanced features: sampling, elicitation, progress (partial), completion

## Running the Server

To run the conformance server:

```bash
cd conformance-tests/server-servlet
../../mvnw compile exec:java -Dexec.mainClass="io.modelcontextprotocol.conformance.server.ConformanceServlet"
```

Or from the root directory:

```bash
./mvnw compile exec:java -pl conformance-tests/server-servlet -Dexec.mainClass="io.modelcontextprotocol.conformance.server.ConformanceServlet"
```

The server will start on port 8080 with the MCP endpoint at `/mcp`.

## Running Conformance Tests

Once the server is running, you can validate it against the official MCP conformance test suite using `npx`:

### Run Full Active Test Suite

```bash
npx @modelcontextprotocol/conformance server --url http://localhost:8080/mcp --suite active
```

### Run Specific Scenarios

```bash
# Test tools
npx @modelcontextprotocol/conformance server --url http://localhost:8080/mcp --scenario tools-list --verbose

# Test prompts
npx @modelcontextprotocol/conformance server --url http://localhost:8080/mcp --scenario prompts-list --verbose

# Test resources
npx @modelcontextprotocol/conformance server --url http://localhost:8080/mcp --scenario resources-read-text --verbose

# Test elicitation with defaults
npx @modelcontextprotocol/conformance server --url http://localhost:8080/mcp --scenario elicitation-sep1034-defaults --verbose
```

### Available Test Suites

- `active` (default) - All active/stable tests (30 scenarios)
- `all` - All tests including pending/experimental
- `pending` - Only pending/experimental tests

### Common Scenarios

**Lifecycle & Utilities:**
- `server-initialize` - Server initialization
- `ping` - Ping utility
- `logging-set-level` - Logging configuration
- `completion-complete` - Argument completion

**Tools:**
- `tools-list` - List available tools
- `tools-call-simple-text` - Simple text response
- `tools-call-image` - Image content
- `tools-call-audio` - Audio content
- `tools-call-with-logging` - Logging during execution
- `tools-call-with-progress` - Progress notifications
- `tools-call-sampling` - LLM sampling
- `tools-call-elicitation` - User input requests

**Resources:**
- `resources-list` - List resources
- `resources-read-text` - Read text resource
- `resources-read-binary` - Read binary resource
- `resources-templates-read` - Resource templates
- `resources-subscribe` - Subscribe to resource updates
- `resources-unsubscribe` - Unsubscribe from updates

**Prompts:**
- `prompts-list` - List prompts
- `prompts-get-simple` - Simple prompt
- `prompts-get-with-args` - Parameterized prompt
- `prompts-get-embedded-resource` - Prompt with resource
- `prompts-get-with-image` - Prompt with image

**Elicitation:**
- `elicitation-sep1034-defaults` - Default values (SEP-1034)
- `elicitation-sep1330-enums` - Enum schemas (SEP-1330)

## Testing with curl

You can also test the endpoint manually:

```bash
# Check endpoint (will show SSE requirement)
curl -X GET http://localhost:8080/mcp

# Initialize session with proper headers
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "mcp-session-id: test-session-123" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}'
```

## Architecture

- **Transport**: HttpServletStreamableServerTransportProvider (streamable HTTP with SSE)
- **Container**: Embedded Apache Tomcat
- **Protocol**: Streamable HTTP with Server-Sent Events
- **Port**: 8080 (default)
- **Endpoint**: `/mcp`
- **Request Timeout**: 30 seconds

## Implemented Tools

### Content Type Tools
- `test_simple_text` - Returns simple text content
- `test_image_content` - Returns a minimal PNG image (1x1 red pixel)
- `test_audio_content` - Returns a minimal WAV audio file
- `test_embedded_resource` - Returns embedded resource content
- `test_multiple_content_types` - Returns mixed text, image, and resource content

### Behavior Tools
- `test_tool_with_logging` - Sends log notifications during execution
- `test_error_handling` - Intentionally returns an error for testing
- `test_tool_with_progress` - Reports progress notifications (⚠️ SDK issue)

### Interactive Tools
- `test_sampling` - Requests LLM sampling from client
- `test_elicitation` - Requests user input from client
- `test_elicitation_sep1034_defaults` - Elicitation with default values (SEP-1034)
- `test_elicitation_sep1330_enums` - Elicitation with enum schemas (SEP-1330)

## Implemented Prompts

- `test_simple_prompt` - Simple prompt without arguments
- `test_prompt_with_arguments` - Prompt with required arguments (arg1, arg2)
- `test_prompt_with_embedded_resource` - Prompt with embedded resource content
- `test_prompt_with_image` - Prompt with image content

## Implemented Resources

- `test://static-text` - Static text resource
- `test://static-binary` - Static binary resource (PNG image)
- `test://watched-resource` - Resource that can be subscribed to
- `test://template/{id}/data` - Resource template with parameter substitution

## Known Limitations

See [VALIDATION_RESULTS.md](../VALIDATION_RESULTS.md) for details on:

1. **Progress Notifications** - Timeout issue in async execution flow
2. **Resource Subscriptions** - Not implemented in Java SDK
3. **DNS Rebinding Protection** - Missing Host/Origin validation

These are SDK-level limitations that require fixes in the core framework.

## References

- [MCP Specification](https://modelcontextprotocol.io/specification/)
- [MCP Conformance Tests](https://github.com/modelcontextprotocol/conformance)
- [SDK Integration Guide](https://github.com/modelcontextprotocol/conformance/blob/main/SDK_INTEGRATION.md)
