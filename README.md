# MCP Java SDK
[[!xây dựng trạng thái](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml/badge.svg)](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml)

Một tập hợp các dự án cung cấp tích hợp Java SDK cho  [Giao thức ngữ cảnh mô hình](https://modelcontextprotocol.org/docs/concepts/architecture).  
This SDK enables Java applications to interact with AI models and tools through a standardized interface, supporting both synchronous and asynchronous communication patterns.

## Tài liệu tham khảo 📚 

#### Tài liệu MCP Java SDK
Để biết hướng dẫn chi tiết và tài liệu SDK API, hãy truy cập.  [Tài liệu tham khảo MCP Java SDK](https://modelcontextprotocol.io/sdk/java/mcp-overview).

#### Tài liệu MCP mùa xuân AI
[MCP mùa xuân](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)    mở rộng MCP Java SDK với tích hợp Spring Boot, cung cấp cả hai    [khách hàng](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)    Và    [máy chủ](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)    bắt đầu. Các ứng dụng AI của bạn với hỗ trợ MCP sử dụng    [Spring Initializer](https://start.spring.io).

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
