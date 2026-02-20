---
title: Index
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
    - Optional Spring-based transports (available in [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+, no longer part of this SDK):
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

    Spring-specific transports (WebFlux, WebMVC) are now part of [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ and are no longer shipped by this SDK.
    Use the [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-client-boot-starter-docs.html) and [MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-server-boot-starter-docs.html) from Spring AI.
    Also consider the [MCP Annotations](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-annotations-overview.html) and [MCP Security](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-security.html).

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
