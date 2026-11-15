---
title: Home
description: Introduction to the Model Context Protocol (MCP) Java SDK
---

# MCP Java SDK

The Java SDK for the [Model Context Protocol](https://modelcontextprotocol.io/docs/concepts/architecture) (MCP), the open protocol that connects AI applications to tools and data.
Use it to build MCP clients and MCP servers in Java 17 or later, with a synchronous or an asynchronous API.

The SDK implements two generations of the protocol, side by side in the same module:

- **Modern**, for the stateless [2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28) specification, where every request stands alone.
- **Legacy**, for the [2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25) specification and earlier revisions, where client and server keep a session.

The names are the specification's own, and legacy does not mean deprecated here: the SDK supports both.
See [Modern or Legacy?](overview.md#modern-or-legacy) to choose between them.

## Features

What each implementation supports:

| Feature | Modern | Legacy |
|---|---|---|
| [Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools): discovery, calls, structured output | Yes | Yes |
| [Resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources) and URI templates | Yes | Yes |
| [Prompts](https://modelcontextprotocol.io/specification/2025-11-25/server/prompts) | Yes | Yes |
| [Completions](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion) for argument values | Yes | Yes |
| [Elicitation](https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation): asking the user for input | Yes, as a multi round-trip request (form and URL) | Yes, as a request from the server |
| [Sampling](https://modelcontextprotocol.io/specification/2025-11-25/client/sampling) | Server only | Yes |
| [Roots](https://modelcontextprotocol.io/specification/2025-11-25/client/roots) | Server only | Yes |
| [Progress](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress) notifications | Yes | Yes |
| [Logging](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/logging) | No (deprecated by the revision) | Yes |
| Change notifications | Yes, with `subscriptions/listen` | Yes, over the session |
| Finding out what a server supports | `server/discover` | `initialize` handshake |
| Caching hints on results | Yes | No |
| Custom HTTP headers for tool arguments (SEP-2243) | Yes | No |

Both implementations share the following:

- **Sync and async APIs.** Choose blocking calls or Project Reactor types.
- **Transports without a web framework.** STDIO, and Streamable HTTP on the JDK `HttpClient` (client) and the Servlet API (server). The legacy implementation also has the deprecated [SSE](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) transport.
- **Pluggable JSON serialization.** Jackson 3.x by default, or Jackson 2.x.
- **Security hooks.** Pluggable authorization hooks, and opt-in DNS rebinding protection with Host/Origin header validation (see [HTTP Transport Security](server.md#http-transport-security)).

!!! tip "Spring applications"
    Spring WebFlux and WebMVC transports for the legacy client and server are part of [Spring AI](https://docs.spring.io/spring-ai/reference/2.1.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ and are not shipped by this SDK.
    Use the [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/2.1.0-SNAPSHOT/api/mcp/mcp-client-boot-starter-docs.html) and the [MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/2.1.0-SNAPSHOT/api/mcp/mcp-server-boot-starter-docs.html) from Spring AI.
    For the modern client and server, Spring AI 2.1+ provides Boot starters and Spring transports; see [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/2.1.0-SNAPSHOT/api/mcp/mcp-client-boot-starter-docs.html) and [MCP 2026-07-28 Servers](https://docs.spring.io/spring-ai/reference/2.1.0-SNAPSHOT/api/mcp/mcp-modern-server-boot-starter-docs.html).
    Also consider the [MCP Annotations](https://docs.spring.io/spring-ai/reference/2.1.0-SNAPSHOT/api/mcp/mcp-annotations-overview.html) and [MCP Security](https://docs.spring.io/spring-ai/reference/2.1.0-SNAPSHOT/api/mcp/mcp-security.html).

## Getting Started

Add the `mcp` module, which bundles the core SDK with Jackson 3.x JSON serialization:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
</dependency>
```

The [Quickstart](quickstart.md) covers versions, the BOM, Gradle, and the other modules.

## Next Steps

<div class="grid cards" markdown>

-   :material-sitemap:{ .lg .middle } **Overview**

    ---

    Understand the architecture, and choose between modern and legacy.

    [:octicons-arrow-right-24: Overview](overview.md)

-   :rocket:{ .lg .middle } **Quickstart**

    ---

    Get started with dependencies and BOM configuration.

    [:octicons-arrow-right-24: Quickstart](quickstart.md)

-   :material-monitor:{ .lg .middle } **MCP Client (Modern)**

    ---

    Call stateless servers of the 2026-07-28 revision.

    [:octicons-arrow-right-24: Client](client-modern.md)

-   :material-server:{ .lg .middle } **MCP Server (Modern)**

    ---

    Build stateless servers for the 2026-07-28 revision.

    [:octicons-arrow-right-24: Server](server-modern.md)

-   :material-monitor:{ .lg .middle } **MCP Client (Legacy)**

    ---

    Create and configure clients for servers of the 2025-11-25 revision and earlier.

    [:octicons-arrow-right-24: Client](client.md)

-   :material-server:{ .lg .middle } **MCP Server (Legacy)**

    ---

    Implement and configure servers for the 2025-11-25 revision and earlier.

    [:octicons-arrow-right-24: Server](server.md)
</div>
