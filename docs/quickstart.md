---
title: Quickstart
description: Get started with the MCP Java SDK dependencies and configuration
---

# Quickstart

Add the SDK to your build, then make a first call to an MCP server. The SDK requires Java 17 or later.

## Dependencies

### Bill of Materials (BOM)

Import the BOM so that all SDK modules use the same version and the dependencies below need no version of their own:

=== "Maven"

    ```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp-bom</artifactId>
                <version>2.1.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    ```

=== "Gradle"

    ```groovy
    dependencies {
        implementation platform("io.modelcontextprotocol.sdk:mcp-bom:2.1.0")
    }
    ```

Replace the version number with the latest version from [Maven Central](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp).

!!! note
    The modern client and server, for the 2026-07-28 specification, are available from 2.2.0.

### SDK

Add the `mcp` module, which bundles `mcp-core` with Jackson 3.x JSON serialization:

=== "Maven"

    ```xml
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp</artifactId>
    </dependency>
    ```

=== "Gradle"

    ```groovy
    dependencies {
        implementation "io.modelcontextprotocol.sdk:mcp"
    }
    ```

This is all a client needs. It includes the classic and the modern client and server, with their STDIO and HTTP transports, and requires no web framework.

!!! note "HTTP servers"
    The HTTP server transports are built on the Servlet API, which the SDK does not bring in. Run them in a Servlet container, such as Tomcat or Jetty, with async support enabled.

### Other Setups

**Jackson 2.x instead of Jackson 3.x.** Use `mcp-core` with the Jackson 2 module in place of `mcp`:

=== "Maven"

    ```xml
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp-json-jackson2</artifactId>
    </dependency>
    ```

=== "Gradle"

    ```groovy
    dependencies {
        implementation "io.modelcontextprotocol.sdk:mcp-core"
        implementation "io.modelcontextprotocol.sdk:mcp-json-jackson2"
    }
    ```

**Your own JSON implementation.** Depend on `mcp-core` alone and provide the JSON binding yourself.

**Spring applications.** Spring WebFlux and WebMVC transports for the classic client and server are part of [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`), not of this SDK:

=== "Maven"

    ```xml
    <!-- Spring WebFlux-based SSE and Streamable HTTP client and server transport -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>mcp-spring-webflux</artifactId>
    </dependency>

    <!-- Spring WebMVC-based SSE and Streamable HTTP server transport -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>mcp-spring-webmvc</artifactId>
    </dependency>
    ```

=== "Gradle"

    ```groovy
    dependencies {
        // Spring WebFlux-based SSE and Streamable HTTP client and server transport
        implementation "org.springframework.ai:mcp-spring-webflux"

        // Spring WebMVC-based SSE and Streamable HTTP server transport
        implementation "org.springframework.ai:mcp-spring-webmvc"
    }
    ```

With the `spring-ai-bom` or the Spring AI starters (`spring-ai-starter-mcp-server-webflux`, `spring-ai-starter-mcp-server-webmvc`, `spring-ai-starter-mcp-client-webflux`), no explicit version is needed.

## Your First Client

The SDK has two clients, one for each generation of the protocol; see [Classic or Modern?](overview.md#classic-or-modern) to choose. Both examples list the tools of a server at `http://localhost:8080/mcp` and call one of them.

=== "Classic"

    For servers of the 2025-11-25 specification and earlier. The client sets up a session with `initialize()`:

    ```java
    import java.util.Map;

    import io.modelcontextprotocol.client.McpClient;
    import io.modelcontextprotocol.client.McpSyncClient;
    import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
    import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
    import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

    McpSyncClient client = McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://localhost:8080").build())
        .build();

    client.initialize();

    client.listTools().tools().forEach(tool -> System.out.println(tool.name()));

    CallToolResult result = client.callTool(
            CallToolRequest.builder("echo").arguments(Map.of("text", "Hello")).build());
    System.out.println(result.content());

    client.closeGracefully();
    ```

    Continue with the [MCP Client](client.md) guide.

=== "Modern"

    For servers of the 2026-07-28 specification. There is nothing to initialize and nothing to close on the client:

    ```java
    import java.util.Map;

    import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
    import io.modelcontextprotocol.modern.client.McpClient;
    import io.modelcontextprotocol.modern.client.McpSyncClient;
    import io.modelcontextprotocol.modern.client.transport.HttpClientMcpTransport;

    McpSyncClient client = McpClient.sync(
            HttpClientMcpTransport.builder("http://localhost:8080/mcp").build())
        .build();

    client.listTools().list().tools().forEach(tool -> System.out.println(tool.name()));

    CallToolResult result = client.tool("echo")
        .arguments(Map.of("text", "Hello"))
        .call();
    System.out.println(result.content());
    ```

    Continue with the [MCP Client (2026-07-28)](client-modern.md) guide.

To build the server side, see the [MCP Server](server.md) guide for the classic server and the [Quick Start](server-modern.md#quick-start) of the modern server guide, which serves the `echo` tool the modern example calls.

## Available Dependencies

The BOM manages the following modules, all in the group `io.modelcontextprotocol.sdk`:

| Module | Purpose |
|--------|---------|
| `mcp` | Convenience bundle that combines `mcp-core` with `mcp-json-jackson3`. |
| `mcp-core` | The classic and modern clients and servers, with the STDIO, JDK HttpClient and Servlet transports. JSON binding is pluggable. |
| `mcp-json-jackson3` | Jackson 3.x JSON serialization (included in `mcp`). |
| `mcp-json-jackson2` | Jackson 2.x JSON serialization, for projects that need Jackson 2.x. |
| `mcp-test` | Testing utilities and support for MCP-based applications. |

The Spring transports, `mcp-spring-webflux` and `mcp-spring-webmvc`, are published by [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ in the group `org.springframework.ai`.
