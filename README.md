# MCP Java SDK
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/license/MIT)
[![Build Status](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml/badge.svg)](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.modelcontextprotocol.sdk/mcp.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)


A set of projects that provide Java SDK integration for the [Model Context Protocol](https://modelcontextprotocol.io/docs/concepts/architecture). 
This SDK enables Java applications to interact with AI models and tools through a standardized interface, supporting both synchronous and asynchronous communication patterns.

## 📚 Reference Documentation 

#### MCP Java SDK documentation
For comprehensive guides and SDK API documentation

- [Features](https://modelcontextprotocol.github.io/java-sdk/#features) - Overview the features provided by the Java MCP SDK
- [Architecture](https://modelcontextprotocol.github.io/java-sdk/#architecture) - Java MCP SDK architecture overview.
- [Java Dependencies / BOM](https://modelcontextprotocol.github.io/java-sdk/quickstart/#dependencies) - Java dependencies and BOM.
- [Java MCP Client](https://modelcontextprotocol.github.io/java-sdk/client/) - Learn how to use the MCP client to interact with MCP servers.
- [Java MCP Server](https://modelcontextprotocol.github.io/java-sdk/server/) - Learn how to implement and configure a MCP servers.

#### Spring AI MCP documentation
[Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) extends the MCP Java SDK with Spring Boot integration, providing both [client](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) and [server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) starters. 
The [MCP Annotations](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-overview.html) - provides annotation-based method handling for MCP servers and clients in Java.
The [MCP Security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html) - provides comprehensive OAuth 2.0 and API key-based security support for Model Context Protocol implementations in Spring AI.
Bootstrap your AI applications with MCP support using [Spring Initializer](https://start.spring.io).

## Development

### Building from Source

```bash
./mvnw clean install -DskipTests
```

### Running Tests

To run the tests you have to pre-install `Docker` and `npx`.

```bash
./mvnw test
```

## Contributing

Contributions are welcome!
Please follow the [Contributing Guidelines](CONTRIBUTING.md).

## Team

- Christian Tzolov
- Dariusz Jędrzejczyk
- Daniel Garnier-Moiroux

## Links

- [GitHub Repository](https://github.com/modelcontextprotocol/java-sdk)
- [Issue Tracker](https://github.com/modelcontextprotocol/java-sdk/issues)
- [CI/CD](https://github.com/modelcontextprotocol/java-sdk/actions)

## Architecture and Design Decisions

### Introduction

Building a general-purpose MCP Java SDK requires making technology decisions in areas where the JDK provides limited or no support. The Java ecosystem is powerful but fragmented: multiple valid approaches exist, each with strong communities.
Our goal is not to prescribe "the one true way," but to provide a reference implementation of the MCP specification that is:

* **Pragmatic** – makes developers productive quickly
* **Interoperable** – aligns with widely used libraries and practices
* **Pluggable** – allows alternatives where projects prefer different stacks
* **Grounded in team familiarity** – we chose technologies the team can be productive with today, while remaining open to community contributions that broaden the SDK

### Key Choices and Considerations

The SDK had to make decisions in the following areas:

1. **JSON serialization** – mapping between JSON and Java types

2. **Programming model** – supporting asynchronous processing, cancellation, and streaming while staying simple for blocking use cases

3. **Observability** – logging and enabling integration with metrics/tracing

4. **Remote clients and servers** – supporting both consuming MCP servers (client transport) and exposing MCP endpoints (server transport with authorization)

The following sections explain what we chose, why it made sense, and how the choices align with the SDK's goals.

### 1. JSON Serialization

* **SDK Choice**: Jackson for JSON serialization and deserialization, behind an SDK abstraction (package `io.modelcontextprotocol.json` in `mcp-core`)

* **Why**: Jackson is widely adopted across the Java ecosystem, provides strong performance and a mature annotation model, and is familiar to the SDK team and many potential contributors.

* **How we expose it**: Public APIs use a bundled abstraction. Jackson is shipped as the default implementation (`mcp-json-jackson3`), but alternatives can be plugged in.

* **How it fits the SDK**: This offers a pragmatic default while keeping flexibility for projects that prefer different JSON libraries.

### 2. Programming Model

* **SDK Choice**: Reactive Streams for public APIs, with Project Reactor as the internal implementation and a synchronous facade for blocking use cases

* **Why**: MCP builds on JSON-RPC's asynchronous nature and defines a bidirectional protocol on top of it, enabling asynchronous and streaming interactions. MCP explicitly supports:

    * Multiple in-flight requests and responses
    * Notifications that do not expect a reply
    * STDIO transports for inter-process communication using pipes
    * Streaming transports such as Server-Sent Events and Streamable HTTP

    These requirements call for a programming model more powerful than single-result futures like `CompletableFuture`.

    * **Reactive Streams: the Community Standard**

        Reactive Streams is a small Java specification that standardizes asynchronous stream processing with backpressure. It defines four minimal interfaces (Publisher, Subscriber, Subscription, and Processor). These interfaces are widely recognized as the standard contract for async, non-blocking pipelines in Java.

    * **Reactive Streams Implementation**

        The SDK uses Project Reactor as its implementation of the Reactive Streams specification. Reactor is mature, widely adopted, provides rich operators, and integrates well with observability through context propagation. Team familiarity also allowed us to deliver a solid foundation quickly.
        We plan to convert the public API to only expose Reactive Streams interfaces. By defining the public API in terms of Reactive Streams interfaces and using Reactor internally, the SDK stays standards-based while benefiting from a practical, production-ready implementation.

    * **Synchronous Facade in the SDK**

        Not all MCP use cases require streaming pipelines. Many scenarios are as simple as "send a request and block until I get the result."
        To support this, the SDK provides a synchronous facade layered on top of the reactive core. Developers can stay in a blocking model when it's enough, while still having access to asynchronous streaming when needed.

* **How it fits the SDK**: This design balances scalability, approachability, and future evolution such as Virtual Threads and Structured Concurrency in upcoming JDKs.

### 3. Observability

* **SDK Choice**: SLF4J for logging; Reactor Context for observability propagation

* **Why**: SLF4J is the de facto logging facade in Java, with broad compatibility. Reactor Context enables propagation of observability data such as correlation IDs and tracing state across async boundaries. This ensures interoperability with modern observability frameworks.

* **How we expose it**: Public APIs log through SLF4J only, with no backend included. Observability metadata flows through Reactor pipelines. The SDK itself does not ship metrics or tracing implementations.

* **How it fits the SDK**: This provides reliable logging by default and seamless integration with Micrometer, OpenTelemetry, or similar systems for metrics and tracing.

### 4. Remote MCP Clients and Servers

MCP supports both clients (applications consuming MCP servers) and servers (applications exposing MCP endpoints). The SDK provides support for both sides.

#### Client Transport in the SDK

* **SDK Choice**: JDK HttpClient (Java 11+) as the default client, with optional Spring WebClient support

* **Why**: The JDK HttpClient is built-in, portable, and supports streaming responses. This keeps the default lightweight with no extra dependencies. Spring WebClient support is available for Spring-based projects.

* **How we expose it**: MCP Client APIs are transport-agnostic. The core module ships with JDK HttpClient transport. A Spring module provides WebClient integration.

* **How it fits the SDK**: This ensures all applications can talk to MCP servers out of the box, while allowing richer integration in Spring and other environments.

#### Server Transport in the SDK

* **SDK Choice**: Jakarta Servlet implementation in core, with optional Spring WebFlux and Spring WebMVC providers

* **Why**: Servlet is the most widely deployed Java server API. WebFlux and WebMVC cover a significant part of the Spring community. Together these provide reach across blocking and non-blocking models.

* **How we expose it**: Server APIs are transport-agnostic. Core includes Servlet support. Spring modules extend support for WebFlux and WebMVC.

* **How it fits the SDK**: This allows developers to expose MCP servers in the most common Java environments today, while enabling other transport implementations such as Netty, Vert.x, or Helidon.

#### Authorization in the SDK

* **SDK Choice**: Pluggable authorization hooks for MCP servers; no built-in implementation

* **Why**: MCP servers must restrict access to authenticated and authorized clients. Authorization needs differ across environments such as Spring Security, MicroProfile JWT, or custom solutions. Providing hooks avoids lock-in and leverages proven libraries.

* **How we expose it**: Authorization is integrated into the server transport layer. The SDK does not include its own authorization system.

* **How it fits the SDK**: This keeps server-side security ecosystem-neutral, while ensuring applications can plug in their preferred authorization strategy.

### Project Structure of the SDK

The SDK is organized into modules to separate concerns and allow adopters to bring in only what they need:
* `mcp-bom` – Dependency versions
* `mcp-core` – Reference implementation (STDIO, JDK HttpClient, Servlet), JSON binding interface definitions
* `mcp-json-jackson2` – Jackson 2 implementation of JSON binding
* `mcp-json-jackson3` – Jackson 3 implementation of JSON binding
* `mcp` – Convenience bundle (core + Jackson 3)
* `mcp-test` – Shared testing utilities
* `mcp-spring` – Spring integrations (WebClient, WebFlux, WebMVC)

For example, a minimal adopter may depend only on `mcp` (core + Jackson), while a Spring-based application can use `mcp-spring` for deeper framework integration.

Additionally, `mcp-test` contains integration tests for `mcp-core`.
`mcp-core` needs a JSON implementation to run full integration tests.
Implementations such as `mcp-json-jackson3`, depend on `mcp-core`, and therefore cannot be imported in `mcp-core` for tests.
Instead, all integration tests that need a JSON implementation are now in `mcp-test`, and use `jackson3` by default.
A `jackson2` maven profile allows to run integration tests with Jackson 2, like so:


```bash
./mvnw -pl mcp-test -am -Pjackson2 test
```

### Future Directions

The SDK is designed to evolve with the Java ecosystem. Areas we are actively watching include:
Concurrency in the JDK – Virtual Threads and Structured Concurrency may simplify the synchronous API story

## License

This project is licensed under the [MIT License](LICENSE).
