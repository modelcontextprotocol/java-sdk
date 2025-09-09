# MCP Java SDK
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/license/MIT)
[![Build Status](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml/badge.svg)](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.modelcontextprotocol.sdk/mcp.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)


A set of projects that provide Java SDK integration for the [Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/architecture). 
This SDK enables Java applications to interact with AI models and tools through a standardized interface, supporting both synchronous and asynchronous communication patterns.

## 📚 Reference Documentation 

#### MCP Java SDK documentation
For comprehensive guides and SDK API documentation

- [Features](https://modelcontextprotocol.io/sdk/java/mcp-overview#features) - Overview the features provided by the Java MCP SDK
- [Architecture](https://modelcontextprotocol.io/sdk/java/mcp-overview#architecture) - Java MCP SDK architecture overview.
- [Java Dependencies / BOM](https://modelcontextprotocol.io/sdk/java/mcp-overview#dependencies) - Java dependencies and BOM.
- [Java MCP Client](https://modelcontextprotocol.io/sdk/java/mcp-client) - Learn how to use the MCP client to interact with MCP servers.
- [Java MCP Server](https://modelcontextprotocol.io/sdk/java/mcp-server) - Learn how to implement and configure a MCP servers.

#### Spring AI MCP documentation
[Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) extends the MCP Java SDK with Spring Boot integration, providing both [client](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) and [server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) starters. Bootstrap your AI applications with MCP support using [Spring Initializer](https://start.spring.io).

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

## Links

- [GitHub Repository](https://github.com/modelcontextprotocol/java-sdk)
- [Issue Tracker](https://github.com/modelcontextprotocol/java-sdk/issues)
- [CI/CD](https://github.com/modelcontextprotocol/java-sdk/actions)

## License

This project is licensed under the [MIT License](LICENSE).
