# MCP Java SDK
[![Build Status](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/continuous-integration.yml/badge.svg)](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/continuous-integration.yml)

A set of projects that provide Java SDK integration for the [Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/architecture). 
This SDK enables Java applications to interact with AI models and tools through a standardized interface, supporting both synchronous and asynchronous communication patterns.

## 📚 Reference Documentation

#### MCP Java SDK documentation
For comprehensive guides and SDK API documentation, visit the [MCP Java SDK Reference Documentation](https://modelcontextprotocol.io/sdk/java/mcp-overview).

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

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Submit a Pull Request

## Team

- Christian Tzolov
- Dariusz Jędrzejczyk

## Links

- [GitHub Repository](https://github.com/modelcontextprotocol/java-sdk)
- [Issue Tracker](https://github.com/modelcontextprotocol/java-sdk/issues)
- [CI/CD](https://github.com/modelcontextprotocol/java-sdk/actions)

## License

This project is licensed under the [MIT License](LICENSE).
