---
title: MCP Client
description: Learn how to use the Model Context Protocol (MCP) client to interact with MCP servers
---

# MCP Client

The MCP Client is a key component in the Model Context Protocol (MCP) architecture, responsible for establishing and managing connections with MCP servers. It implements the client-side of the protocol, handling:

- Protocol version negotiation to ensure compatibility with servers
- Capability negotiation to determine available features
- Message transport and JSON-RPC communication
- Tool discovery and execution with optional schema validation
- Resource access and management
- Prompt system interactions
- Optional features like roots management, sampling, and elicitation support
- Progress tracking for long-running operations

!!! tip
    The core `io.modelcontextprotocol.sdk:mcp` module provides STDIO, SSE, and Streamable HTTP client transport implementations without requiring external web frameworks.

    Spring-specific transport implementations are available as an **optional** dependency `io.modelcontextprotocol.sdk:mcp-spring-webflux` for [Spring Framework](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) users.

The client provides both synchronous and asynchronous APIs for flexibility in different application contexts.

=== "Sync API"

    ```java
    // Create a sync client with custom configuration
    McpSyncClient client = McpClient.sync(transport)
        .requestTimeout(Duration.ofSeconds(10))
        .capabilities(ClientCapabilities.builder()
            .roots(true)       // Enable roots capability
            .sampling()        // Enable sampling capability
            .elicitation()     // Enable elicitation capability
            .build())
        .sampling(request -> new CreateMessageResult(response))
        .elicitation(request -> new ElicitResult(ElicitResult.Action.ACCEPT, content))
        .build();

    // Initialize connection
    client.initialize();

    // List available tools
    ListToolsResult tools = client.listTools();

    // Call a tool
    CallToolResult result = client.callTool(
        new CallToolRequest("calculator",
            Map.of("operation", "add", "a", 2, "b", 3))
    );

    // List and read resources
    ListResourcesResult resources = client.listResources();
    ReadResourceResult resource = client.readResource(
        new ReadResourceRequest("resource://uri")
    );

    // List and use prompts
    ListPromptsResult prompts = client.listPrompts();
    GetPromptResult prompt = client.getPrompt(
        new GetPromptRequest("greeting", Map.of("name", "Spring"))
    );

    // Add/remove roots
    client.addRoot(new Root("file:///path", "description"));
    client.removeRoot("file:///path");

    // Close client
    client.closeGracefully();
    ```

=== "Async API"

    ```java
    // Create an async client with custom configuration
    McpAsyncClient client = McpClient.async(transport)
        .requestTimeout(Duration.ofSeconds(10))
        .capabilities(ClientCapabilities.builder()
            .roots(true)       // Enable roots capability
            .sampling()        // Enable sampling capability
            .elicitation()     // Enable elicitation capability
            .build())
        .sampling(request -> Mono.just(new CreateMessageResult(response)))
        .elicitation(request -> Mono.just(new ElicitResult(ElicitResult.Action.ACCEPT, content)))
        .toolsChangeConsumer(tools -> Mono.fromRunnable(() -> {
            logger.info("Tools updated: {}", tools);
        }))
        .resourcesChangeConsumer(resources -> Mono.fromRunnable(() -> {
            logger.info("Resources updated: {}", resources);
        }))
        .promptsChangeConsumer(prompts -> Mono.fromRunnable(() -> {
            logger.info("Prompts updated: {}", prompts);
        }))
        .progressConsumer(progress -> Mono.fromRunnable(() -> {
            logger.info("Progress: {}", progress);
        }))
        .build();

    // Initialize connection and use features
    client.initialize()
        .flatMap(initResult -> client.listTools())
        .flatMap(tools -> {
            return client.callTool(new CallToolRequest(
                "calculator",
                Map.of("operation", "add", "a", 2, "b", 3)
            ));
        })
        .flatMap(result -> {
            return client.listResources()
                .flatMap(resources ->
                    client.readResource(new ReadResourceRequest("resource://uri"))
                );
        })
        .flatMap(resource -> {
            return client.listPrompts()
                .flatMap(prompts ->
                    client.getPrompt(new GetPromptRequest(
                        "greeting",
                        Map.of("name", "Spring")
                    ))
                );
        })
        .flatMap(prompt -> {
            return client.addRoot(new Root("file:///path", "description"))
                .then(client.removeRoot("file:///path"));
        })
        .doFinally(signalType -> {
            client.closeGracefully().subscribe();
        })
        .subscribe();
    ```

## Client Transport

The transport layer handles the communication between MCP clients and servers, providing different implementations for various use cases. The client transport manages message serialization, connection establishment, and protocol-specific communication patterns.

=== "STDIO"

    Creates transport for process-based communication using stdin/stdout:

    ```java
    ServerParameters params = ServerParameters.builder("npx")
        .args("-y", "@modelcontextprotocol/server-everything", "dir")
        .build();
    McpTransport transport = new StdioClientTransport(params);
    ```

=== "SSE (HttpClient)"

    Creates a framework-agnostic (pure Java API) SSE client transport. Included in the core `mcp` module:

    ```java
    McpTransport transport = new HttpClientSseClientTransport("http://your-mcp-server");
    ```

=== "Streamable HTTP"

    Creates a Streamable HTTP client transport for efficient bidirectional communication. Included in the core `mcp` module:

    ```java
    McpTransport transport = HttpClientStreamableHttpTransport
        .builder("http://your-mcp-server")
        .endpoint("/mcp")
        .build();
    ```

    The Streamable HTTP transport supports:

    - Resumable streams for connection recovery
    - Configurable connect timeout
    - Custom HTTP request customization
    - Multiple protocol version negotiation

=== "SSE (WebFlux)"

    Creates WebFlux-based SSE client transport. Requires the `mcp-spring-webflux` dependency:

    ```java
    WebClient.Builder webClientBuilder = WebClient.builder()
        .baseUrl("http://your-mcp-server");
    McpTransport transport = new WebFluxSseClientTransport(webClientBuilder);
    ```

## Client Capabilities

The client can be configured with various capabilities:

```java
var capabilities = ClientCapabilities.builder()
    .roots(true)       // Enable filesystem roots support with list changes notifications
    .sampling()        // Enable LLM sampling support
    .elicitation()     // Enable elicitation support (form and URL modes)
    .build();
```

You can also configure elicitation with specific mode support:

```java
var capabilities = ClientCapabilities.builder()
    .elicitation(true, false)  // Enable form-based elicitation, disable URL-based
    .build();
```

### Roots Support

Roots define the boundaries of where servers can operate within the filesystem:

```java
// Add a root dynamically
client.addRoot(new Root("file:///path", "description"));

// Remove a root
client.removeRoot("file:///path");

// Notify server of roots changes
client.rootsListChangedNotification();
```

The roots capability allows servers to:

- Request the list of accessible filesystem roots
- Receive notifications when the roots list changes
- Understand which directories and files they have access to

### Sampling Support

Sampling enables servers to request LLM interactions ("completions" or "generations") through the client:

```java
// Configure sampling handler
Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
    // Sampling implementation that interfaces with LLM
    return new CreateMessageResult(response);
};

// Create client with sampling support
var client = McpClient.sync(transport)
    .capabilities(ClientCapabilities.builder()
        .sampling()
        .build())
    .sampling(samplingHandler)
    .build();
```

This capability allows:

- Servers to leverage AI capabilities without requiring API keys
- Clients to maintain control over model access and permissions
- Support for both text and image-based interactions
- Optional inclusion of MCP server context in prompts

### Elicitation Support

Elicitation enables servers to request additional information or user input through the client. This is useful when a server needs clarification or confirmation during an operation:

```java
// Configure elicitation handler
Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
    // Present the request to the user and collect their response
    // The request contains a message and a schema describing the expected input
    Map<String, Object> userResponse = collectUserInput(request.message(), request.requestedSchema());
    return new ElicitResult(ElicitResult.Action.ACCEPT, userResponse);
};

// Create client with elicitation support
var client = McpClient.sync(transport)
    .capabilities(ClientCapabilities.builder()
        .elicitation()
        .build())
    .elicitation(elicitationHandler)
    .build();
```

The `ElicitResult` supports three actions:

- `ACCEPT` - The user accepted and provided the requested information
- `DECLINE` - The user declined to provide the information
- `CANCEL` - The operation was cancelled

### Logging Support

The client can register a logging consumer to receive log messages from the server and set the minimum logging level to filter messages:

```java
var mcpClient = McpClient.sync(transport)
        .loggingConsumer(notification -> {
            System.out.println("Received log message: " + notification.data());
        })
        .build();

mcpClient.initialize();

mcpClient.setLoggingLevel(McpSchema.LoggingLevel.INFO);

// Call the tool that sends logging notifications
CallToolResult result = mcpClient.callTool(new CallToolRequest("logging-test", Map.of()));
```

Clients can control the minimum logging level they receive through the `mcpClient.setLoggingLevel(level)` request. Messages below the set level will be filtered out.
Supported logging levels (in order of increasing severity): DEBUG (0), INFO (1), NOTICE (2), WARNING (3), ERROR (4), CRITICAL (5), ALERT (6), EMERGENCY (7)

### Progress Notifications

The client can register a progress consumer to track the progress of long-running operations:

```java
var mcpClient = McpClient.sync(transport)
    .progressConsumer(progress -> {
        System.out.println("Progress: " + progress.progress() + "/" + progress.total());
    })
    .build();
```

## Using MCP Clients

### Tool Execution

Tools are server-side functions that clients can discover and execute. The MCP client provides methods to list available tools and execute them with specific parameters. Each tool has a unique name and accepts a map of parameters.

=== "Sync API"

    ```java
    // List available tools
    ListToolsResult tools = client.listTools();

    // Call a tool with a CallToolRequest
    CallToolResult result = client.callTool(
        new CallToolRequest("calculator", Map.of(
            "operation", "add",
            "a", 1,
            "b", 2
        ))
    );
    ```

=== "Async API"

    ```java
    // List available tools asynchronously
    client.listTools()
        .doOnNext(tools -> tools.tools().forEach(tool ->
            System.out.println(tool.name())))
        .subscribe();

    // Call a tool asynchronously
    client.callTool(new CallToolRequest("calculator", Map.of(
            "operation", "add",
            "a", 1,
            "b", 2
        )))
        .subscribe();
    ```

### Tool Schema Validation and Caching

The client supports optional JSON schema validation for tool call results and automatic schema caching:

```java
var client = McpClient.sync(transport)
    .jsonSchemaValidator(myValidator)            // Enable schema validation
    .enableCallToolSchemaCaching(true)           // Cache tool schemas
    .build();
```

### Resource Access

Resources represent server-side data sources that clients can access using URI templates. The MCP client provides methods to discover available resources and retrieve their contents through a standardized interface.

=== "Sync API"

    ```java
    // List available resources
    ListResourcesResult resources = client.listResources();

    // Read a resource
    ReadResourceResult resource = client.readResource(
        new ReadResourceRequest("resource://uri")
    );
    ```

=== "Async API"

    ```java
    // List available resources asynchronously
    client.listResources()
        .doOnNext(resources -> resources.resources().forEach(resource ->
            System.out.println(resource.name())))
        .subscribe();

    // Read a resource asynchronously
    client.readResource(new ReadResourceRequest("resource://uri"))
        .subscribe();
    ```

### Prompt System

The prompt system enables interaction with server-side prompt templates. These templates can be discovered and executed with custom parameters, allowing for dynamic text generation based on predefined patterns.

=== "Sync API"

    ```java
    // List available prompt templates
    ListPromptsResult prompts = client.listPrompts();

    // Get a prompt with parameters
    GetPromptResult prompt = client.getPrompt(
        new GetPromptRequest("greeting", Map.of("name", "World"))
    );
    ```

=== "Async API"

    ```java
    // List available prompt templates asynchronously
    client.listPrompts()
        .doOnNext(prompts -> prompts.prompts().forEach(prompt ->
            System.out.println(prompt.name())))
        .subscribe();

    // Get a prompt asynchronously
    client.getPrompt(new GetPromptRequest("greeting", Map.of("name", "World")))
        .subscribe();
    ```
