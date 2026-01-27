# MCP Conformance Tests - Servlet Server

This module contains a basic MCP (Model Context Protocol) server implementation using the servlet stack with an embedded Tomcat server and streamable HTTP transport.

## Features

- Embedded Tomcat servlet container
- MCP server using HttpServletStreamableServerTransportProvider
- Single "hello_world" tool that returns "Hello World!"
- Streamable HTTP transport on `/mcp` endpoint

## Running the Server

To run the server:

```bash
cd conformance-tests/server-servlet
../../mvnw compile exec:java -Dexec.mainClass="io.modelcontextprotocol.conformance.server.ConformanceServlet"
```

Or from the root directory:

```bash
mvn compile exec:java -pl conformance-tests/server-servlet -Dexec.mainClass="io.modelcontextprotocol.conformance.server.ConformanceServlet"
```

The server will start on port 8080 with the MCP endpoint at `/mcp`.

## Testing the Server

Once the server is running, you can test it using an MCP client. The server exposes:

- **Endpoint**: `http://localhost:8080/mcp`
- **Tool**: `hello_world` - Returns "Hello World!" message

Example using curl to check the endpoint:

```bash
curl -X GET http://localhost:8080/mcp
```

## Architecture

- **Transport**: HttpServletStreamableServerTransportProvider (streamable HTTP with SSE)
- **Container**: Embedded Apache Tomcat
- **Protocol**: Streamable HTTP
- **Port**: 8080 (default)
- **Endpoint**: /mcp

## Tool Description

This is temporary just to kick off the effort.

### hello_world

- **Name**: `hello_world`
- **Description**: Returns a hello world message
- **Input Schema**: Empty object (no parameters required)
- **Output**: TextContent with "Hello World!"
