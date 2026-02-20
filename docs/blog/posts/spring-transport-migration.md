---
date: 2026-02-20
authors:
  - mcp-team
categories:
  - Release
  - Migration
---

# Spring Transports Move to Spring AI 2.0

Starting with **MCP Java SDK 1.0** and **Spring AI 2.0**, the Spring-specific transport modules (`mcp-spring-webflux` and `mcp-spring-webmvc`) are no longer part of this SDK. They now live in the Spring AI project under the `org.springframework.ai` group.

<!-- more -->

## What Changed

The `mcp-spring-webflux` and `mcp-spring-webmvc` artifacts have moved from:

```xml
<groupId>io.modelcontextprotocol.sdk</groupId>
```

to:

```xml
<groupId>org.springframework.ai</groupId>
```

The class names are unchanged, but the Java packages have moved from `io.modelcontextprotocol.server.transport` / `io.modelcontextprotocol.client.transport` to `org.springframework.ai.mcp.server.webflux.transport`, `org.springframework.ai.mcp.server.webmvc.transport`, and `org.springframework.ai.mcp.client.webflux.transport`.

## Who Is Affected

- **Spring Boot users via auto-configuration**: update your `pom.xml` / `build.gradle` coordinates only — no Java code changes needed.
- **Direct users of transport classes**: update both the Maven/Gradle coordinates and the import statements in your code.

## What to Do

**1. Update your dependency coordinates:**

```xml
<!-- Before -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-webflux</artifactId>
</dependency>

<!-- After -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>mcp-spring-webflux</artifactId>
</dependency>
```

**2. Update imports (if you reference transport classes directly):**

```java
// Before
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;

// After
import org.springframework.ai.mcp.server.webflux.transport.WebFluxSseServerTransportProvider;
import org.springframework.ai.mcp.client.webflux.transport.WebFluxSseClientTransport;
```

The core `io.modelcontextprotocol.sdk:mcp` module is unaffected and continues to provide STDIO, SSE, and Streamable HTTP transports without any Spring dependency.

For the full list of moved classes and Spring AI starter options, see the [Spring AI MCP documentation](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html).
