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

## Getting Started

Add the MCP SDK to your project:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
    <version>${mcp.version}</version>
</dependency>
```

## SDK Documentation

- [Java SDK Overview](io/modelcontextprotocol/doc-files/sdk-overview.html) - Features, architecture, and dependencies
- [MCP Client](io/modelcontextprotocol/doc-files/client.html) - Client implementation and transport options
- [MCP Server](io/modelcontextprotocol/doc-files/server.html) - Server implementation and transport providers

## Additional Documentation

- [MCP documentation](https://modelcontextprotocol.io)
- [MCP specification](https://modelcontextprotocol.io/specification/latest)
- [Spring AI MCP Documentation](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
