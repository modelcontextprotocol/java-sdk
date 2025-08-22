# MCP In-Memory Transport

[![Maven Central](https://img.shields.io/maven-central/v/io.modelcontextprotocol.sdk/mcp-inmemory-transport)](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp-inmemory-transport)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

In-memory transport implementation for the Model Context Protocol (MCP) Java SDK.

## Overview

The `mcp-inmemory-transport` module provides an in-memory transport layer for the Model Context Protocol (MCP) Java SDK. This transport is particularly useful for testing, local development, and scenarios where you need to establish direct communication between MCP client and server components without network overhead.

## Features

- **In-Memory Communication**: Enables direct communication between MCP client and server without network calls
- **Reactive Streams**: Built on Project Reactor for non-blocking, reactive communication
- **Easy Integration**: Seamlessly integrates with the MCP Java SDK
- **Testing-Friendly**: Ideal for unit and integration testing of MCP implementations

## Getting Started

### Prerequisites

- Java 17 or higher
- Maven or Gradle build system
- MCP Java SDK core dependency

### Installation

#### Maven

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-inmemory-transport</artifactId>
    <version>0.12.0-SNAPSHOT</version>
</dependency>
```

#### Gradle

Add the following to your `build.gradle`:

```gradle
implementation 'io.modelcontextprotocol.sdk:mcp-inmemory-transport:0.12.0-SNAPSHOT'
```

### Usage

#### Basic Setup

To use the in-memory transport, you'll need to create an `InMemoryTransport` instance and use it to create both client and server transports:

```java
// Create the shared in-memory transport
InMemoryTransport transport = new InMemoryTransport();

// Create server transport provider
InMemoryServerTransportProvider serverProvider = new InMemoryServerTransportProvider(transport);

// Create client transport
InMemoryClientTransport clientTransport = new InMemoryClientTransport(transport);
```

#### Creating an MCP Server

```java
McpServer server = McpServer.sync(serverProvider)
    .toolCall(McpSchema.Tool.builder()
        .name("echo")
        .description("Echoes the input")
        .inputSchema(new McpSchema.JsonSchema(
            "object", 
            Map.of("message", Map.of("type", "string")), 
            List.of("message"), 
            true, 
            null, 
            null))
        .build(), (exchange, request) -> {
            String message = (String) request.arguments().get("message");
            return new McpSchema.CallToolResult("Echo: " + message, false);
        })
    .build();
```

#### Creating an MCP Client

```java
try (McpClient client = McpClient.sync(clientTransport).build()) {
    // Initialize the client
    client.initialize();
    
    // Call a tool
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
        "echo", 
        Map.of("message", "Hello, MCP!")
    );
    
    McpSchema.CallToolResult result = client.callTool(request);
    System.out.println("Result: " + result.content());
}
```

### Complete Example

Here's a complete example showing how to set up and use the in-memory transport:

```java
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.inmemory.InMemoryTransport;
import io.modelcontextprotocol.transport.inmemory.InMemoryClientTransport;
import io.modelcontextprotocol.transport.inmemory.InMemoryServerTransportProvider;

import java.util.List;
import java.util.Map;

public class InMemoryTransportExample {
    public static void main(String[] args) {
        // Create the shared in-memory transport
        InMemoryTransport transport = new InMemoryTransport();
        
        // Create server transport provider
        InMemoryServerTransportProvider serverProvider = new InMemoryServerTransportProvider(transport);
        
        // Create client transport
        InMemoryClientTransport clientTransport = new InMemoryClientTransport(transport);
        
        // Set up the server
        McpServer server = McpServer.sync(serverProvider)
            .toolCall(McpSchema.Tool.builder()
                .name("calculate")
                .description("Performs a calculation")
                .inputSchema(new McpSchema.JsonSchema(
                    "object",
                    Map.of(
                        "operation", Map.of("type", "string", "enum", List.of("add", "subtract")),
                        "a", Map.of("type", "number"),
                        "b", Map.of("type", "number")
                    ),
                    List.of("operation", "a", "b"),
                    true,
                    null,
                    null))
                .build(), (exchange, request) -> {
                    String operation = (String) request.arguments().get("operation");
                    Number a = (Number) request.arguments().get("a");
                    Number b = (Number) request.arguments().get("b");
                    
                    double result;
                    switch (operation) {
                        case "add":
                            result = a.doubleValue() + b.doubleValue();
                            break;
                        case "subtract":
                            result = a.doubleValue() - b.doubleValue();
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown operation: " + operation);
                    }
                    
                    return new McpSchema.CallToolResult(String.valueOf(result), false);
                })
            .build();
        
        // Use the client
        try (McpClient client = McpClient.sync(clientTransport).build()) {
            // Initialize the client
            client.initialize();
            
            // Call the calculate tool
            McpSchema.CallToolRequest addRequest = new McpSchema.CallToolRequest(
                "calculate",
                Map.of("operation", "add", "a", 10, "b", 5)
            );
            
            McpSchema.CallToolResult addResult = client.callTool(addRequest);
            System.out.println("10 + 5 = " + addResult.content().get(0));
            
            McpSchema.CallToolRequest subtractRequest = new McpSchema.CallToolRequest(
                "calculate",
                Map.of("operation", "subtract", "a", 10, "b", 3)
            );
            
            McpSchema.CallToolResult subtractResult = client.callTool(subtractRequest);
            System.out.println("10 - 3 = " + subtractResult.content().get(0));
        }
    }
}
```

## Architecture

The in-memory transport consists of three main components:

1. **InMemoryTransport**: The core transport that manages the communication channels between client and server
2. **InMemoryClientTransport**: Implements the client-side transport interface
3. **InMemoryServerTransportProvider**: Provides the server-side transport implementation

The transport uses Reactor's `Sinks.Many` to create multicast channels for message passing between client and server.

## Testing

The module includes comprehensive unit tests. To run them:

```bash
mvn test
```

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](../CONTRIBUTING.md) for details on how to contribute to this project.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Related Projects

- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
- [Model Context Protocol Specification](https://github.com/modelcontextprotocol/specification)