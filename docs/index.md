---
title: Overview
description: Introduction to the Model Context Protocol (MCP) Java SDK
---

# MCP Java SDK

Java SDK for the [Model Context Protocol](https://modelcontextprotocol.io/docs/concepts/architecture)
enables standardized integration between AI models and tools.

## Features

- MCP Client and MCP Server implementations supporting:
    - Protocol [version compatibility negotiation](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#initialization) with multiple protocol versions
    - [Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools) discovery, execution, list change notifications, and structured output with schema validation
    - [Resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources) management with URI templates
    - [Roots](https://modelcontextprotocol.io/specification/2025-11-25/client/roots) list management and notifications
    - [Prompts](https://modelcontextprotocol.io/specification/2025-11-25/server/prompts) handling and management
    - [Sampling](https://modelcontextprotocol.io/specification/2025-11-25/client/sampling) support for AI model interactions
    - [Elicitation](https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation) support for requesting user input from servers
    - [Completions](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion) for argument autocompletion suggestions
    - [Progress](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress) - progress notifications for tracking long-running operations
    - [Logging](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/logging) - structured logging with configurable severity levels
- Multiple transport implementations:
    - Default transports (included in core `mcp` module, no external web frameworks required):
        - [STDIO](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#stdio)-based transport for process-based communication
        - Java HttpClient-based SSE client transport for HTTP SSE Client-side streaming
        - Servlet-based SSE server transport for HTTP SSE Server streaming
        - [Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#streamable-http) transport for efficient bidirectional communication (client and server)
    - Optional Spring-based transports (available in [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 2.0+, no longer part of this SDK):
        - WebFlux SSE client and server transports for reactive HTTP streaming
        - WebFlux Streamable HTTP server transport
        - WebMVC SSE server transport for servlet-based HTTP streaming
        - WebMVC Streamable HTTP server transport
        - WebMVC Stateless server transport
- Supports Synchronous and Asynchronous programming paradigms
- Pluggable JSON serialization (Jackson 2.x and Jackson 3.x)
- Pluggable authorization hooks for server security
- DNS rebinding protection with Host/Origin header validation

!!! tip
    The core `io.modelcontextprotocol.sdk:mcp` module provides default STDIO, SSE, and Streamable HTTP client and server transport implementations without requiring external web frameworks.

    Spring-specific transports (WebFlux, WebMVC) are now part of [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 2.0+ and are no longer shipped by this SDK.
    Use the [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) and [MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) from Spring AI.
    Also consider the [MCP Annotations](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-overview.html) and [MCP Security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html).

## Architecture

The SDK follows a layered architecture with clear separation of concerns:

![MCP Stack Architecture](images/mcp-stack.svg)

- **Client/Server Layer (McpClient/McpServer)**: Both use McpSession for sync/async operations,
  with McpClient handling client-side protocol operations and McpServer managing server-side protocol operations.
- **Session Layer (McpSession)**: Manages communication patterns and state.
- **Transport Layer (McpTransport)**: Handles JSON-RPC message serialization/deserialization via:
    - StdioTransport (stdin/stdout) in the core module
    - HTTP SSE transports in dedicated transport modules (Java HttpClient, Servlet)
    - Streamable HTTP transports for efficient bidirectional communication
    - Spring WebFlux and Spring WebMVC transports (available in [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 2.0+)

The MCP Client is a key component in the Model Context Protocol (MCP) architecture, responsible for establishing and managing connections with MCP servers.
It implements the client-side of the protocol.

![Java MCP Client Architecture](images/java-mcp-client-architecture.jpg)

The MCP Server is a foundational component in the Model Context Protocol (MCP) architecture that provides tools, resources, and capabilities to clients.
It implements the server-side of the protocol.

![Java MCP Server Architecture](images/java-mcp-server-architecture.jpg)

Key Interactions:

- **Client/Server Initialization**: Transport setup, protocol compatibility check, capability negotiation, and implementation details exchange.
- **Message Flow**: JSON-RPC message handling with validation, type-safe response processing, and error handling.
- **Resource Management**: Resource discovery, URI template-based access, subscription system, and content retrieval.

## Module Structure

The SDK is organized into modules to separate concerns and allow adopters to bring in only what they need:

| Module | Artifact ID | Group | Purpose |
|--------|------------|-------|---------|
| `mcp-bom` | `mcp-bom` | `io.modelcontextprotocol.sdk` | Bill of Materials for dependency management |
| `mcp-core` | `mcp-core` | `io.modelcontextprotocol.sdk` | Core reference implementation (STDIO, JDK HttpClient, Servlet, Streamable HTTP) |
| `mcp-json-jackson2` | `mcp-json-jackson2` | `io.modelcontextprotocol.sdk` | Jackson 2.x JSON serialization implementation |
| `mcp-json-jackson3` | `mcp-json-jackson3` | `io.modelcontextprotocol.sdk` | Jackson 3.x JSON serialization implementation |
| `mcp` | `mcp` | `io.modelcontextprotocol.sdk` | Convenience bundle (`mcp-core` + `mcp-json-jackson3`) |
| `mcp-test` | `mcp-test` | `io.modelcontextprotocol.sdk` | Shared testing utilities and integration tests |
| `mcp-spring-webflux` _(external)_ | `mcp-spring-webflux` | `org.springframework.ai` | Spring WebFlux integration — part of [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 2.0+ |
| `mcp-spring-webmvc` _(external)_ | `mcp-spring-webmvc` | `org.springframework.ai` | Spring WebMVC integration — part of [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 2.0+ |

!!! tip
    A minimal adopter may depend only on `mcp` (core + Jackson 3). Spring-based applications should use the `mcp-spring-webflux` or `mcp-spring-webmvc` artifacts from [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`), no longer part of this SDK.

## Next Steps

<div class="grid cards" markdown>

-   :rocket:{ .lg .middle } **Quickstart**

    ---

    Get started with dependencies and BOM configuration.

    [:octicons-arrow-right-24: Quickstart](quickstart.md)

-   :material-monitor:{ .lg .middle } **MCP Client**

    ---

    Learn how to create and configure MCP clients.

    [:octicons-arrow-right-24: Client](client.md)

-   :material-server:{ .lg .middle } **MCP Server**

    ---

    Learn how to implement and configure MCP servers.

    [:octicons-arrow-right-24: Server](server.md)

-   :fontawesome-brands-github:{ .lg .middle } **GitHub**

    ---

    View the source code and contribute.

    [:octicons-arrow-right-24: Repository](https://github.com/modelcontextprotocol/java-sdk)

</div>
