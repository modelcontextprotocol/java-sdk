# MCP Java SDK Migration Guide: 0.7.0 to 0.8.0

This document outlines the breaking changes and provides guidance on how to migrate your code from version 0.7.0 to 0.8.0.

The 0.8.0 refactoring introduces a session-based architecture for server-side MCP implementations.
It improves the SDK's ability to handle multiple concurrent client connections and provides an API better aligned with the MCP specification.
The main changes include:

1. Introduction of a session-based architecture
2. New transport provider abstraction
3. Exchange objects for client interaction
4. Renamed and reorganized interfaces
5. Updated handler signatures

## Breaking Changes

### 1. Interface Renaming

Several interfaces have been renamed to better reflect their roles:

| 0.7.0 (Old) | 0.8.0 (New) |
|-------------|-------------|
| `ClientMcpTransport` | `McpClientTransport` |
| `ServerMcpTransport` | `McpServerTransport` |
| `DefaultMcpSession` | `McpClientSession`, `McpServerSession` |

### 2. New Server Transport Architecture

The most significant change is the introduction of the `McpServerTransportProvider` interface, which replaces direct usage of `ServerMcpTransport` when creating servers. This new pattern separates the concerns of:

1. **Transport Provider**: Manages connections with clients and creates individual transports for each connection
2. **Server Transport**: Handles communication with a specific client connection

| 0.7.0 (Old) | 0.8.0 (New) |
|-------------|-------------|
| `ServerMcpTransport` | `McpServerTransportProvider` + `McpServerTransport` |
| Direct transport usage | Session-based transport usage |

#### Before (0.7.0):

```java
// Create a transport
ServerMcpTransport transport = new WebFluxSseServerTransport(objectMapper, "/mcp/message");

// Create a server with the transport
McpServer.sync(transport)
    .serverInfo("my-server", "1.0.0")
    .build();
```

#### After (0.8.0):

```java
// Create a transport provider
McpServerTransportProvider transportProvider = new WebFluxSseServerTransportProvider(objectMapper, "/mcp/message");

// Create a server with the transport provider
McpServer.sync(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .build();
```

### 3. Handler Method Signature Changes

Tool, resource, and prompt handlers now receive an additional `exchange` parameter that provides access to client capabilities and methods to interact with the client:

| 0.7.0 (Old) | 0.8.0 (New) |
|-------------|-------------|
| `(args) -> result` | `(exchange, args) -> result` |

The exchange objects (`McpAsyncServerExchange` and `McpSyncServerExchange`) provide context for the current session and access to session-specific operations.

#### Before (0.7.0):

```java
// Tool handler
.tool(calculatorTool, args -> new CallToolResult("Result: " + calculate(args)))

// Resource handler
.resource(fileResource, req -> new ReadResourceResult(readFile(req)))

// Prompt handler
.prompt(analysisPrompt, req -> new GetPromptResult("Analysis prompt"))
```

#### After (0.8.0):

```java
// Tool handler
.tool(calculatorTool, (exchange, args) -> new CallToolResult("Result: " + calculate(args)))

// Resource handler
.resource(fileResource, (exchange, req) -> new ReadResourceResult(readFile(req)))

// Prompt handler
.prompt(analysisPrompt, (exchange, req) -> new GetPromptResult("Analysis prompt"))
```

### 4. Registration vs. Specification

The naming convention for handlers has changed from "Registration" to "Specification":

| 0.7.0 (Old) | 0.8.0 (New) |
|-------------|-------------|
| `AsyncToolRegistration` | `AsyncToolSpecification` |
| `SyncToolRegistration` | `SyncToolSpecification` |
| `AsyncResourceRegistration` | `AsyncResourceSpecification` |
| `SyncResourceRegistration` | `SyncResourceSpecification` |
| `AsyncPromptRegistration` | `AsyncPromptSpecification` |
| `SyncPromptRegistration` | `SyncPromptSpecification` |

### 5. Roots Change Handler Updates

The roots change handlers now receive an exchange parameter:

#### Before (0.7.0):

```java
.rootsChangeConsumers(List.of(
    roots -> {
        // Process roots
    }
))
```

#### After (0.8.0):

```java
.rootsChangeHandlers(List.of(
    (exchange, roots) -> {
        // Process roots with access to exchange
    }
))
```

### 6. Server Creation Method Changes

The `McpServer` factory methods now accept `McpServerTransportProvider` instead of `ServerMcpTransport`:

| 0.7.0 (Old) | 0.8.0 (New) |
|-------------|-------------|
| `McpServer.async(ServerMcpTransport)` | `McpServer.async(McpServerTransportProvider)` |
| `McpServer.sync(ServerMcpTransport)` | `McpServer.sync(McpServerTransportProvider)` |

The method names for creating servers have been updated:

Root change handlers now receive an exchange object:

| 0.7.0 (Old) | 0.8.0 (New) |
|-------------|-------------|
| `rootsChangeConsumers(List<Consumer<List<Root>>>)` | `rootsChangeHandlers(List<BiConsumer<McpSyncServerExchange, List<Root>>>)` |
| `rootsChangeConsumer(Consumer<List<Root>>)` | `rootsChangeHandler(BiConsumer<McpSyncServerExchange, List<Root>>)` |

### 7. Direct Server Methods Moving to Exchange

Several methods that were previously available directly on the server are now accessed through the exchange object:

| 0.7.0 (Old) | 0.8.0 (New) |
|-------------|-------------|
| `server.listRoots()` | `exchange.listRoots()` |
| `server.createMessage()` | `exchange.createMessage()` |
| `server.getClientCapabilities()` | `exchange.getClientCapabilities()` |
| `server.getClientInfo()` | `exchange.getClientInfo()` |

The direct methods are deprecated and will be removed in 0.9.0:

- `McpSyncServer.listRoots()`
- `McpSyncServer.getClientCapabilities()`
- `McpSyncServer.getClientInfo()`
- `McpSyncServer.createMessage()`
- `McpAsyncServer.listRoots()`
- `McpAsyncServer.getClientCapabilities()`
- `McpAsyncServer.getClientInfo()`
- `McpAsyncServer.createMessage()`

## Deprecation Notices

The following components are deprecated in 0.8.0 and will be removed in 0.9.0:

- `ClientMcpTransport` interface (use `McpClientTransport` instead)
- `ServerMcpTransport` interface (use `McpServerTransport` instead)
- `DefaultMcpSession` class (use `McpClientSession` instead)
- `WebFluxSseServerTransport` class (use `WebFluxSseServerTransportProvider` instead)
- `WebMvcSseServerTransport` class (use `WebMvcSseServerTransportProvider` instead)
- `StdioServerTransport` class (use `StdioServerTransportProvider` instead)
- All `*Registration` classes (use corresponding `*Specification` classes instead)
- Direct server methods for client interaction (use exchange object instead)

## Migration Examples

### Example 1: Creating a Server

#### Before (0.7.0):

```java
// Create a transport
ServerMcpTransport transport = new WebFluxSseServerTransport(objectMapper, "/mcp/message");

// Create a server with the transport
var server = McpServer.sync(transport)
    .serverInfo("my-server", "1.0.0")
    .tool(calculatorTool, args -> new CallToolResult("Result: " + calculate(args)))
    .rootsChangeConsumers(List.of(
        roots -> System.out.println("Roots changed: " + roots)
    ))
    .build();

// Get client capabilities directly from server
ClientCapabilities capabilities = server.getClientCapabilities();
```

#### After (0.8.0):

```java
// Create a transport provider
McpServerTransportProvider transportProvider = new WebFluxSseServerTransportProvider(objectMapper, "/mcp/message");

// Create a server with the transport provider
var server = McpServer.sync(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .tool(calculatorTool, (exchange, args) -> {
        // Get client capabilities from exchange
        ClientCapabilities capabilities = exchange.getClientCapabilities();
        return new CallToolResult("Result: " + calculate(args));
    })
    .rootsChangeHandlers(List.of(
        (exchange, roots) -> System.out.println("Roots changed: " + roots)
    ))
    .build();
```

### Example 2: Implementing a Tool with Client Interaction

#### Before (0.7.0):

```java
McpServerFeatures.SyncToolRegistration tool = new McpServerFeatures.SyncToolRegistration(
    new Tool("weather", "Get weather information", schema),
    args -> {
        String location = (String) args.get("location");
        // Cannot interact with client from here
        return new CallToolResult("Weather for " + location + ": Sunny");
    }
);

var server = McpServer.sync(transport)
    .tools(tool)
    .build();

// Separate call to create a message
CreateMessageResult result = server.createMessage(new CreateMessageRequest(...));
```

#### After (0.8.0):

```java
McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
    new Tool("weather", "Get weather information", schema),
    (exchange, args) -> {
        String location = (String) args.get("location");
        
        // Can interact with client directly from the tool handler
        CreateMessageResult result = exchange.createMessage(new CreateMessageRequest(...));
        
        return new CallToolResult("Weather for " + location + ": " + result.content());
    }
);

var server = McpServer.sync(transportProvider)
    .tools(tool)
    .build();
```

### Example 3: Converting Existing Registration Classes

If you have custom implementations of the registration classes, you can convert them to the new specification classes:

#### Before (0.7.0):

```java
McpServerFeatures.AsyncToolRegistration toolReg = new McpServerFeatures.AsyncToolRegistration(
    tool,
    args -> Mono.just(new CallToolResult("Result"))
);

McpServerFeatures.AsyncResourceRegistration resourceReg = new McpServerFeatures.AsyncResourceRegistration(
    resource,
    req -> Mono.just(new ReadResourceResult(List.of()))
);
```

#### After (0.8.0):

```java
// Option 1: Create new specification directly
McpServerFeatures.AsyncToolSpecification toolSpec = new McpServerFeatures.AsyncToolSpecification(
    tool,
    (exchange, args) -> Mono.just(new CallToolResult("Result"))
);

// Option 2: Convert from existing registration (during transition)
McpServerFeatures.AsyncToolRegistration oldToolReg = /* existing registration */;
McpServerFeatures.AsyncToolSpecification toolSpec = oldToolReg.toSpecification();

// Similarly for resources
McpServerFeatures.AsyncResourceSpecification resourceSpec = new McpServerFeatures.AsyncResourceSpecification(
    resource,
    (exchange, req) -> Mono.just(new ReadResourceResult(List.of()))
);
```

## Architecture Changes

### Session-Based Architecture

In 0.8.0, the MCP Java SDK introduces a session-based architecture where each client connection has its own session. This allows for better isolation between clients and more efficient resource management.

The `McpServerTransportProvider` is responsible for creating `McpServerTransport` instances for each session, and the `McpServerSession` manages the communication with a specific client.

### Exchange Objects

The new exchange objects (`McpAsyncServerExchange` and `McpSyncServerExchange`) provide access to client-specific information and methods. They are passed to handler functions as the first parameter, allowing handlers to interact with the specific client that made the request.

## Conclusion

The changes in version 0.8.0 represent a significant architectural improvement to the MCP Java SDK. While they require some code changes, the new design provides a more flexible and maintainable foundation for building MCP applications.

For assistance with migration or to report issues, please open an issue on the GitHub repository.
