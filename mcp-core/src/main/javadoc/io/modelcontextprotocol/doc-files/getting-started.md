# Getting Started

Add the following dependencies to your project:

## Maven

The core MCP functionality:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
</dependency>
```

The core `mcp` module already includes default `STDIO`, `SSE` and `Streamable-HTTP` transport implementations and doesn't require external web frameworks.

If you're using the Spring Framework and want to use Spring-specific transport implementations, add one of the following optional dependencies:

```xml
<!-- Optional: Spring WebFlux-based SSE and Streamable-HTTP client and server transports -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-webflux</artifactId>
</dependency>

<!-- Optional: Spring WebMVC-based SSE and Streamable-HTTP server transports -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-webmvc</artifactId>
</dependency>
```

## Gradle

The core MCP functionality:

```groovy
dependencies {
  implementation platform("io.modelcontextprotocol.sdk:mcp")
  //...
}
```

The core `mcp` module already includes default `STDIO`, `SSE` and `Streamable-HTTP` transport implementations and doesn't require external web frameworks.

If you're using the Spring Framework and want to use Spring-specific transport implementations, add one of the following optional dependencies:

```groovy
// Optional: Spring WebFlux-based SSE and Streamable-HTTP client and server transports
dependencies {
  implementation platform("io.modelcontextprotocol.sdk:mcp-spring-webflux")
}

// Optional: Spring WebMVC-based SSE and Streamable-HTTP server transports
dependencies {
  implementation platform("io.modelcontextprotocol.sdk:mcp-spring-webmvc")
}
```

---

- `io.modelcontextprotocol.sdk:mcp-spring-webflux` - WebFlux-based Client and Server, `Streamable-HTTP` and `SSE` transport implementations.
  The WebFlux implementation can be used in reactive applications while the WebClient-based MCP Client can be used in both reactive and imperative applications.
  It is a highly scalable option and suitable and recommended for high-throughput scenarios.
- `io.modelcontextprotocol.sdk:mcp-spring-webmvc` - WebMVC-based Server, `Streamable-HTTP` and `SSE` transport implementation for servlet-based applications.

## Bill of Materials (BOM)

The Bill of Materials (BOM) declares the recommended versions of all the dependencies used by a given release.
Using the BOM from your application's build script avoids the need for you to specify and maintain the dependency versions yourself.
Instead, the version of the BOM you're using determines the utilized dependency versions.
It also ensures that you're using supported and tested versions of the dependencies by default, unless you choose to override them.

Add the BOM to your project:

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp-bom</artifactId>
            <version>0.12.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### Gradle

```groovy
dependencies {
  implementation platform("io.modelcontextprotocol.sdk:mcp-bom:0.12.1")
  //...
}
```

Gradle users can also use the MCP BOM by leveraging Gradle (5.0+) native support for declaring dependency constraints using a Maven BOM.
This is implemented by adding a 'platform' dependency handler method to the dependencies section of your Gradle build script.
As shown in the snippet above, this can then be followed by version-less declarations of the MCP SDK modules you wish to use.

---

Replace the version number with the version of the BOM you want to use.

## Additional Dependencies

The following additional dependencies are available and managed by the BOM:

- `io.modelcontextprotocol.sdk:mcp-test` - Testing utilities and support for MCP-based applications.
