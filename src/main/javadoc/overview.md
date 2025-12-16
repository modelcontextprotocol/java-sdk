# MCP Java SDK

Java SDK for the [Model Context Protocol](https://modelcontextprotocol.io/) (MCP), enabling Java applications to interact with AI models and tools through a standardized interface.

The source code is available at [github.com/modelcontextprotocol/java-sdk](https://github.com/modelcontextprotocol/java-sdk).

## Modules

- **mcp** - Convenience module that bundles core dependencies
- **mcp-core** - Core reference implementation
- **mcp-json** - JSON abstraction layer
- **mcp-json-jackson2** - Jackson JSON implementation
- **mcp-spring-webflux** - Spring WebFlux transport
- **mcp-spring-webmvc** - Spring WebMVC transport

## Features

- MCP Client and MCP Server implementations supporting:
  - Protocol [version compatibility negotiation](https://modelcontextprotocol.io/specification/latest/basic/lifecycle#initialization)
  - [Tool](https://modelcontextprotocol.io/specification/latest/server/tools/) discovery, execution, list change notifications
  - [Resource](https://modelcontextprotocol.io/specification/latest/server/resources/) management with URI templates
  - [Prompt](https://modelcontextprotocol.io/specification/latest/server/prompts/) handling and management
  - [Completion](https://modelcontextprotocol.io/specification/latest/server/utilities/completion/) argument autocompletion suggestions for prompts and resource URIs
  - [Progress](https://modelcontextprotocol.io/specification/latest/basic/utilities/progress/) progress tracking for long-running operations
  - [Ping](https://modelcontextprotocol.io/specification/latest/basic/utilities/ping/) lightweight health check mechanism
    - [Server Keepalive](https://modelcontextprotocol.io/specification/latest/basic/utilities/ping#implementation-considerations/) to maintain active server connections
  - [Logging](https://modelcontextprotocol.io/specification/latest/server/utilities/logging/) for sending structured log messages to clients
  - [Roots](https://modelcontextprotocol.io/specification/latest/client/roots/) list management and notifications
  - [Sampling](https://modelcontextprotocol.io/specification/latest/client/sampling/) support for AI model interactions
  - [Elicitation](https://modelcontextprotocol.io/specification/latest/client/elicitation/) for servers to request additional information from users through the client
- Multiple transport implementations:
  - Default transports (included in core `mcp` module, no external web frameworks required):
    - Stdio-based transport for process-based communication
    - Java HttpClient-based `SSE` and `Streamable-HTTP` client transport
    - Servlet-based `SSE` and `Streamable-HTTP` server transport
  - Optional Spring-based transports (convenience if using Spring Framework):
    - WebFlux `SSE` and `Streamable-HTTP` client and server transports
    - WebMVC `SSE` and `Streamable-HTTP` transport for servlet-based HTTP streaming
- Supports Synchronous and Asynchronous programming paradigms

> **Tip:** The core `io.modelcontextprotocol.sdk:mcp` module provides default `STDIO`, `SSE` and `Streamable-HTTP` client and server transport implementations without requiring external web frameworks.
>
> Spring-specific transports are available as optional dependencies for convenience when using the [Spring AI](https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/api/mcp/mcp-overview.html) Framework.

## Architecture

The SDK follows a layered architecture with clear separation of concerns:

- **Client/Server Layer (McpClient/McpServer)**: Both use McpSession for sync/async operations,
  with McpClient handling client-side protocol operations and McpServer managing server-side protocol operations.
- **Session Layer (McpSession)**: Manages communication patterns and state using DefaultMcpSession implementation.
- **Transport Layer (McpTransport)**: Handles JSON-RPC message serialization/deserialization via:
  - StdioTransport (stdin/stdout) in the core module
  - HTTP `Streamable-HTTP` and `SSE` transports in dedicated transport modules (Java HttpClient, Spring WebFlux, Spring WebMVC)

The [MCP Client](io/modelcontextprotocol/doc-files/client.html) is a key component in the Model Context Protocol (MCP) architecture, responsible for establishing and managing connections with MCP servers.
It implements the client-side of the protocol.

<img src="io/modelcontextprotocol/doc-files/images/java-mcp-client-architecture.jpg" alt="Java MCP Client Architecture" style="max-height: 80vh;">

The [MCP Server](io/modelcontextprotocol/doc-files/server.html) is a foundational component in the Model Context Protocol (MCP) architecture that provides tools, resources, and capabilities to clients.
It implements the server-side of the protocol.

<img src="io/modelcontextprotocol/doc-files/images/java-mcp-server-architecture.jpg" alt="Java MCP Server Architecture" style="max-height: 80vh;">

Key Interactions:

- **Client/Server Initialization**: Transport setup, protocol compatibility check, capability negotiation, and implementation details exchange.
- **Message Flow**: JSON-RPC message handling with validation, type-safe response processing, and error handling.
- **Resource Management**: Resource discovery, URI template-based access, subscription system, and content retrieval.

## SDK Documentation

- [Getting Started](io/modelcontextprotocol/doc-files/getting-started.html) - Dependencies and setup
- [MCP Client](io/modelcontextprotocol/doc-files/client.html) - Client implementation and transport options
- [MCP Server](io/modelcontextprotocol/doc-files/server.html) - Server implementation and transport providers

## Additional Documentation

- [MCP documentation](https://modelcontextprotocol.io)
- [MCP specification](https://modelcontextprotocol.io/specification/latest)
- [Spring AI MCP Documentation](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
