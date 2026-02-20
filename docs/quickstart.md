---
title: Quickstart
description: Get started with the MCP Java SDK dependencies and configuration
---

# Quickstart

## Dependencies

Add the following dependency to your project:

=== "Maven"

    The convenience `mcp` module bundles `mcp-core` with Jackson 3.x JSON serialization:

    ```xml
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp</artifactId>
    </dependency>
    ```

    This includes default STDIO, SSE, and Streamable HTTP transport implementations without requiring external web frameworks.

    If you need only the core module without a JSON implementation (e.g., to bring your own):

    ```xml
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp-core</artifactId>
    </dependency>
    ```

    For Jackson 2.x instead of Jackson 3.x:

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

    If you're using Spring Framework, the Spring-specific transport implementations are now part of [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`):

    ```xml
    <!-- Optional: Spring WebFlux-based SSE and Streamable HTTP client and server transport (Spring AI 2.0+) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>mcp-spring-webflux</artifactId>
    </dependency>

    <!-- Optional: Spring WebMVC-based SSE and Streamable HTTP server transport (Spring AI 2.0+) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>mcp-spring-webmvc</artifactId>
    </dependency>
    ```

    !!! note
        When using the `spring-ai-bom` or Spring AI starter dependencies (`spring-ai-starter-mcp-server-webflux`, `spring-ai-starter-mcp-server-webmvc`, `spring-ai-starter-mcp-client-webflux`) no explicit version is needed — the BOM manages it automatically.

=== "Gradle"

    The convenience `mcp` module bundles `mcp-core` with Jackson 3.x JSON serialization:

    ```groovy
    dependencies {
        implementation "io.modelcontextprotocol.sdk:mcp"
    }
    ```

    This includes default STDIO, SSE, and Streamable HTTP transport implementations without requiring external web frameworks.

    If you need only the core module without a JSON implementation (e.g., to bring your own):

    ```groovy
    dependencies {
        implementation "io.modelcontextprotocol.sdk:mcp-core"
    }
    ```

    For Jackson 2.x instead of Jackson 3.x:

    ```groovy
    dependencies {
        implementation "io.modelcontextprotocol.sdk:mcp-core"
        implementation "io.modelcontextprotocol.sdk:mcp-json-jackson2"
    }
    ```

    If you're using Spring Framework, the Spring-specific transport implementations are now part of [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`):

    ```groovy
    // Optional: Spring WebFlux-based SSE and Streamable HTTP client and server transport (Spring AI 2.0+)
    dependencies {
        implementation "org.springframework.ai:mcp-spring-webflux"
    }

    // Optional: Spring WebMVC-based SSE and Streamable HTTP server transport (Spring AI 2.0+)
    dependencies {
        implementation "org.springframework.ai:mcp-spring-webmvc"
    }
    ```

## Bill of Materials (BOM)

The Bill of Materials (BOM) declares the recommended versions of all the dependencies used by a given release.
Using the BOM from your application's build script avoids the need for you to specify and maintain the dependency versions yourself.
Instead, the version of the BOM you're using determines the utilized dependency versions.
It also ensures that you're using supported and tested versions of the dependencies by default, unless you choose to override them.

Add the BOM to your project:

=== "Maven"

    ```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp-bom</artifactId>
                <version>1.0.0-RC1</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    ```

=== "Gradle"

    ```groovy
    dependencies {
        implementation platform("io.modelcontextprotocol.sdk:mcp-bom:latest")
        //...
    }
    ```

    Gradle users can also leverage Gradle (5.0+) native support for declaring dependency constraints using a Maven BOM.
    This is implemented by adding a 'platform' dependency handler method to the dependencies section of your Gradle build script.
    As shown in the snippet above this can then be followed by version-less declarations of the dependencies.

Replace the version number with the latest version from [Maven Central](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp).

## Available Dependencies

The following dependencies are available and managed by the BOM:

- **Core Dependencies**
    - `io.modelcontextprotocol.sdk:mcp-core` - Core MCP library providing the base functionality, APIs, and default transport implementations (STDIO, SSE, Streamable HTTP). JSON binding is abstracted for pluggability.
    - `io.modelcontextprotocol.sdk:mcp` - Convenience bundle that combines `mcp-core` with `mcp-json-jackson3` for out-of-the-box usage.
- **JSON Serialization**
    - `io.modelcontextprotocol.sdk:mcp-json-jackson3` - Jackson 3.x JSON serialization implementation (included in `mcp` bundle).
    - `io.modelcontextprotocol.sdk:mcp-json-jackson2` - Jackson 2.x JSON serialization implementation for projects that require Jackson 2.x compatibility.
- **Optional Spring Transport Dependencies** (part of [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 2.0+, group `org.springframework.ai`)
    - `org.springframework.ai:mcp-spring-webflux` - WebFlux-based SSE and Streamable HTTP transport implementation for reactive applications.
    - `org.springframework.ai:mcp-spring-webmvc` - WebMVC-based SSE and Streamable HTTP transport implementation for servlet-based applications.
- **Testing Dependencies**
    - `io.modelcontextprotocol.sdk:mcp-test` - Testing utilities and support for MCP-based applications.
