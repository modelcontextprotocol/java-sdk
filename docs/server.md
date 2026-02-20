---
title: MCP Server
description: Learn how to implement and configure a Model Context Protocol (MCP) server
---

# MCP Server

## Overview

The MCP Server is a foundational component in the Model Context Protocol (MCP) architecture that provides tools, resources, and capabilities to clients. It implements the server-side of the protocol, responsible for:

- Exposing tools that clients can discover and execute
- Managing resources with URI-based access patterns and resource templates
- Providing prompt templates and handling prompt requests
- Supporting capability negotiation with clients
- Providing argument autocompletion suggestions (completions)
- Implementing server-side protocol operations
- Managing concurrent client connections
- Providing structured logging and notifications

!!! tip
    The core `io.modelcontextprotocol.sdk:mcp` module provides STDIO, SSE, and Streamable HTTP server transport implementations without requiring external web frameworks.

    Spring-specific transport implementations (`mcp-spring-webflux`, `mcp-spring-webmvc`) are now part of [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`) and are no longer shipped by this SDK.
    See the [MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-server-boot-starter-docs.html) documentation for Spring-based server setup.

The server supports both synchronous and asynchronous APIs, allowing for flexible integration in different application contexts.

=== "Sync API"

    ```java
    // Create a server with custom configuration
    McpSyncServer syncServer = McpServer.sync(transportProvider)
        .serverInfo("my-server", "1.0.0")
        .capabilities(ServerCapabilities.builder()
            .resources(false, true)  // Enable resource support with list changes
            .tools(true)             // Enable tool support with list changes
            .prompts(true)           // Enable prompt support with list changes
            .completions()           // Enable completions support
            .logging()               // Enable logging support
            .build())
        .build();

    // Register tools, resources, and prompts
    syncServer.addTool(syncToolSpecification);
    syncServer.addResource(syncResourceSpecification);
    syncServer.addPrompt(syncPromptSpecification);

    // Close the server when done
    syncServer.close();
    ```

=== "Async API"

    ```java
    // Create an async server with custom configuration
    McpAsyncServer asyncServer = McpServer.async(transportProvider)
        .serverInfo("my-server", "1.0.0")
        .capabilities(ServerCapabilities.builder()
            .resources(false, true)  // Enable resource support with list changes
            .tools(true)             // Enable tool support with list changes
            .prompts(true)           // Enable prompt support with list changes
            .completions()           // Enable completions support
            .logging()               // Enable logging support
            .build())
        .build();

    // Register tools, resources, and prompts
    asyncServer.addTool(asyncToolSpecification)
        .doOnSuccess(v -> logger.info("Tool registered"))
        .subscribe();

    asyncServer.addResource(asyncResourceSpecification)
        .doOnSuccess(v -> logger.info("Resource registered"))
        .subscribe();

    asyncServer.addPrompt(asyncPromptSpecification)
        .doOnSuccess(v -> logger.info("Prompt registered"))
        .subscribe();

    // Close the server when done
    asyncServer.close()
        .doOnSuccess(v -> logger.info("Server closed"))
        .subscribe();
    ```

### Server Types

The SDK supports multiple server creation patterns depending on your transport requirements:

```java
// Single-session server with SSE transport provider
McpSyncServer server = McpServer.sync(sseTransportProvider).build();

// Streamable HTTP server
McpSyncServer server = McpServer.sync(streamableTransportProvider).build();

// Stateless server (no session management)
McpSyncServer server = McpServer.sync(statelessTransport).build();
```

## Server Transport Providers

The transport layer in the MCP SDK is responsible for handling the communication between clients and servers.
It provides different implementations to support various communication protocols and patterns.
The SDK includes several built-in transport provider implementations:

### STDIO

Create process-based transport using stdin/stdout:

```java
StdioServerTransportProvider transportProvider =
    new StdioServerTransportProvider(new ObjectMapper());
```

Provides bidirectional JSON-RPC message handling over standard input/output streams with non-blocking message processing, serialization/deserialization, and graceful shutdown support.

Key features:

- Bidirectional communication through stdin/stdout
- Process-based integration support
- Simple setup and configuration
- Lightweight implementation

### Streamable HTTP

=== "Streamable HTTP Servlet"

    Creates a Servlet-based Streamable HTTP server transport. Included in the core `mcp` module:

    ```java
    HttpServletStreamableServerTransportProvider transportProvider =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(jsonMapper)
            .mcpEndpoint("/mcp")
            .build();
    ```

    To use with a Spring Web application, register it as a Servlet bean:

    ```java
    @Configuration
    @EnableWebMvc
    public class McpServerConfig implements WebMvcConfigurer {

        @Bean
        public HttpServletStreamableServerTransportProvider transportProvider(McpJsonMapper jsonMapper) {
            return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .build();
        }

        @Bean
        public ServletRegistrationBean<?> mcpServlet(
                HttpServletStreamableServerTransportProvider transportProvider) {
            return new ServletRegistrationBean<>(transportProvider);
        }
    }
    ```

    Key features:

    - Efficient bidirectional HTTP communication
    - Session management for multiple client connections
    - Configurable keep-alive intervals
    - Security validation support
    - Graceful shutdown support

=== "Streamable HTTP WebFlux (external)"

    Creates WebFlux-based Streamable HTTP server transport. Requires the `mcp-spring-webflux` dependency from [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`):

    ```java
    @Configuration
    class McpConfig {
        @Bean
        WebFluxStreamableServerTransportProvider transportProvider(McpJsonMapper jsonMapper) {
            return WebFluxStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .messageEndpoint("/mcp")
                .build();
        }

        @Bean
        RouterFunction<?> mcpRouterFunction(
                WebFluxStreamableServerTransportProvider transportProvider) {
            return transportProvider.getRouterFunction();
        }
    }
    ```

    Key features:

    - Reactive HTTP streaming with WebFlux
    - Concurrent client connections
    - Configurable keep-alive intervals
    - Security validation support

=== "Streamable HTTP WebMvc (external)"

    Creates WebMvc-based Streamable HTTP server transport. Requires the `mcp-spring-webmvc` dependency from [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`):

    ```java
    @Configuration
    @EnableWebMvc
    class McpConfig {
        @Bean
        WebMvcStreamableServerTransportProvider transportProvider(McpJsonMapper jsonMapper) {
            return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .build();
        }

        @Bean
        RouterFunction<ServerResponse> mcpRouterFunction(
                WebMvcStreamableServerTransportProvider transportProvider) {
            return transportProvider.getRouterFunction();
        }
    }
    ```

### SSE HTTP (Legacy)

=== "SSE Servlet"

    Creates a Servlet-based SSE server transport. Included in the core `mcp` module.
    The `HttpServletSseServerTransportProvider` can be used with any Servlet container.
    To use it with a Spring Web application, you can register it as a Servlet bean:

    ```java
    @Configuration
    @EnableWebMvc
    public class McpServerConfig implements WebMvcConfigurer {

        @Bean
        public HttpServletSseServerTransportProvider servletSseServerTransportProvider() {
            return new HttpServletSseServerTransportProvider(new ObjectMapper(), "/mcp/message");
        }

        @Bean
        public ServletRegistrationBean<?> customServletBean(
                HttpServletSseServerTransportProvider transportProvider) {
            return new ServletRegistrationBean<>(transportProvider);
        }
    }
    ```

    Implements the MCP HTTP with SSE transport specification using the traditional Servlet API, providing:

    - Asynchronous message handling using Servlet 6.0 async support
    - Session management for multiple client connections
    - Two types of endpoints:
        - SSE endpoint (`/sse`) for server-to-client events
        - Message endpoint (configurable) for client-to-server requests
    - Error handling and response formatting
    - Graceful shutdown support

=== "SSE WebFlux (external)"

    Creates WebFlux-based SSE server transport. Requires the `mcp-spring-webflux` dependency from [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`):

    ```java
    @Configuration
    class McpConfig {
        @Bean
        WebFluxSseServerTransportProvider webFluxSseServerTransportProvider(ObjectMapper mapper) {
            return new WebFluxSseServerTransportProvider(mapper, "/mcp/message");
        }

        @Bean
        RouterFunction<?> mcpRouterFunction(WebFluxSseServerTransportProvider transportProvider) {
            return transportProvider.getRouterFunction();
        }
    }
    ```

    Implements the MCP HTTP with SSE transport specification, providing:

    - Reactive HTTP streaming with WebFlux
    - Concurrent client connections through SSE endpoints
    - Message routing and session management
    - Graceful shutdown capabilities

=== "SSE WebMvc (external)"

    Creates WebMvc-based SSE server transport. Requires the `mcp-spring-webmvc` dependency from [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`):

    ```java
    @Configuration
    @EnableWebMvc
    class McpConfig {
        @Bean
        WebMvcSseServerTransportProvider webMvcSseServerTransportProvider(ObjectMapper mapper) {
            return new WebMvcSseServerTransportProvider(mapper, "/mcp/message");
        }

        @Bean
        RouterFunction<ServerResponse> mcpRouterFunction(
                WebMvcSseServerTransportProvider transportProvider) {
            return transportProvider.getRouterFunction();
        }
    }
    ```

    Implements the MCP HTTP with SSE transport specification, providing:

    - Server-side event streaming
    - Integration with Spring WebMVC
    - Support for traditional web applications
    - Synchronous operation handling


## Server Capabilities

The server can be configured with various capabilities:

```java
var capabilities = ServerCapabilities.builder()
    .resources(false, true)  // Resource support (subscribe, listChanged)
    .tools(true)             // Tool support with list changes notifications
    .prompts(true)           // Prompt support with list changes notifications
    .completions()           // Enable completions support
    .logging()               // Enable logging support
    .build();
```

### Tool Specification

The Model Context Protocol allows servers to [expose tools](https://spec.modelcontextprotocol.io/specification/2024-11-05/server/tools/) that can be invoked by language models.
The Java SDK allows implementing Tool Specifications with their handler functions.
Tools enable AI models to perform calculations, access external APIs, query databases, and manipulate files.

The recommended approach is to use the builder pattern and `CallToolRequest` as the handler parameter:

=== "Sync"

    ```java
    // Sync tool specification using builder
    var syncToolSpecification = SyncToolSpecification.builder()
        .tool(Tool.builder()
            .name("calculator")
            .description("Basic calculator")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
            // Access arguments via request.arguments()
            String operation = (String) request.arguments().get("operation");
            int a = (int) request.arguments().get("a");
            int b = (int) request.arguments().get("b");
            // Tool implementation
            return CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Result: " + result)))
                .build();
        })
        .build();
    ```

=== "Async"

    ```java
    // Async tool specification using builder
    var asyncToolSpecification = AsyncToolSpecification.builder()
        .tool(Tool.builder()
            .name("calculator")
            .description("Basic calculator")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
            // Access arguments via request.arguments()
            String operation = (String) request.arguments().get("operation");
            int a = (int) request.arguments().get("a");
            int b = (int) request.arguments().get("b");
            // Tool implementation
            return Mono.just(CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Result: " + result)))
                .build());
        })
        .build();
    ```

The Tool specification includes a Tool definition with `name`, `description`, and `inputSchema` followed by a call handler that implements the tool's logic.
The handler receives `McpSyncServerExchange`/`McpAsyncServerExchange` for client interaction and a `CallToolRequest` containing the tool arguments.

You can also register tools directly on the server builder using the `toolCall` convenience method:

```java
var server = McpServer.sync(transportProvider)
    .toolCall(
        Tool.builder().name("echo").description("Echoes input").inputSchema(schema).build(),
        (exchange, request) -> CallToolResult.builder()
            .content(List.of(new McpSchema.TextContent(request.arguments().get("text").toString())))
            .build()
    )
    .build();
```

### Resource Specification

Specification of a resource with its handler function.
Resources provide context to AI models by exposing data such as: File contents, Database records, API responses, System information, Application state.

=== "Sync"

    ```java
    // Sync resource specification
    var syncResourceSpecification = new McpServerFeatures.SyncResourceSpecification(
        Resource.builder()
            .uri("custom://resource")
            .name("name")
            .description("description")
            .mimeType("text/plain")
            .build(),
        (exchange, request) -> {
            // Resource read implementation
            return new ReadResourceResult(contents);
        }
    );
    ```

=== "Async"

    ```java
    // Async resource specification
    var asyncResourceSpecification = new McpServerFeatures.AsyncResourceSpecification(
        Resource.builder()
            .uri("custom://resource")
            .name("name")
            .description("description")
            .mimeType("text/plain")
            .build(),
        (exchange, request) -> {
            // Resource read implementation
            return Mono.just(new ReadResourceResult(contents));
        }
    );
    ```

### Resource Template Specification

Resource templates allow servers to expose parameterized resources using URI templates:

```java
// Resource template specification
var resourceTemplateSpec = new McpServerFeatures.SyncResourceTemplateSpecification(
    ResourceTemplate.builder()
        .uriTemplate("file://{path}")
        .name("File Resource")
        .description("Access files by path")
        .mimeType("application/octet-stream")
        .build(),
    (exchange, request) -> {
        // Read the file at the requested URI
        return new ReadResourceResult(contents);
    }
);
```

### Prompt Specification

As part of the [Prompting capabilities](https://spec.modelcontextprotocol.io/specification/2024-11-05/server/prompts/), MCP provides a standardized way for servers to expose prompt templates to clients.
The Prompt Specification is a structured template for AI model interactions that enables consistent message formatting, parameter substitution, context injection, response formatting, and instruction templating.

=== "Sync"

    ```java
    // Sync prompt specification
    var syncPromptSpecification = new McpServerFeatures.SyncPromptSpecification(
        new Prompt("greeting", "description", List.of(
            new PromptArgument("name", "description", true)
        )),
        (exchange, request) -> {
            // Prompt implementation
            return new GetPromptResult(description, messages);
        }
    );
    ```

=== "Async"

    ```java
    // Async prompt specification
    var asyncPromptSpecification = new McpServerFeatures.AsyncPromptSpecification(
        new Prompt("greeting", "description", List.of(
            new PromptArgument("name", "description", true)
        )),
        (exchange, request) -> {
            // Prompt implementation
            return Mono.just(new GetPromptResult(description, messages));
        }
    );
    ```

The prompt definition includes name (identifier for the prompt), description (purpose of the prompt), and list of arguments (parameters for templating).
The handler function processes requests and returns formatted templates.
The first argument is `McpSyncServerExchange`/`McpAsyncServerExchange` for client interaction, and the second argument is a `GetPromptRequest` instance.

### Completion Specification

Completions allow servers to provide argument autocompletion suggestions for prompts and resources:

=== "Sync"

    ```java
    // Sync completion specification
    var syncCompletionSpec = new McpServerFeatures.SyncCompletionSpecification(
        new McpSchema.PromptReference("greeting"),  // Reference to a prompt
        (exchange, request) -> {
            String argName = request.argument().name();
            String partial = request.argument().value();
            // Return matching suggestions
            List<String> suggestions = findMatches(partial);
            return new McpSchema.CompleteResult(
                new McpSchema.CompleteResult.CompleteCompletion(suggestions, suggestions.size(), false)
            );
        }
    );
    ```

=== "Async"

    ```java
    // Async completion specification
    var asyncCompletionSpec = new McpServerFeatures.AsyncCompletionSpecification(
        new McpSchema.PromptReference("greeting"),
        (exchange, request) -> {
            String argName = request.argument().name();
            String partial = request.argument().value();
            List<String> suggestions = findMatches(partial);
            return Mono.just(new McpSchema.CompleteResult(
                new McpSchema.CompleteResult.CompleteCompletion(suggestions, suggestions.size(), false)
            ));
        }
    );
    ```

Completions can be registered for both `PromptReference` and `ResourceReference` types.

### Using Sampling from a Server

To use [Sampling capabilities](https://spec.modelcontextprotocol.io/specification/2024-11-05/client/sampling/), connect to a client that supports sampling.
No special server configuration is needed, but verify client sampling support before making requests.
Learn about [client sampling support](client.md#sampling-support).

Once connected to a compatible client, the server can request language model generations:

=== "Sync API"

    ```java
    // Create a server
    McpSyncServer server = McpServer.sync(transportProvider)
        .serverInfo("my-server", "1.0.0")
        .build();

    // Define a tool that uses sampling
    var calculatorTool = SyncToolSpecification.builder()
        .tool(Tool.builder()
            .name("ai-calculator")
            .description("Performs calculations using AI")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
            // Check if client supports sampling
            if (exchange.getClientCapabilities().sampling() == null) {
                return CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent("Client does not support AI capabilities")))
                    .build();
            }

            // Create a sampling request
            CreateMessageRequest samplingRequest = CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
                    new McpSchema.TextContent("Calculate: " + request.arguments().get("expression")))))
                .modelPreferences(McpSchema.ModelPreferences.builder()
                    .hints(List.of(
                        McpSchema.ModelHint.of("claude-3-sonnet"),
                        McpSchema.ModelHint.of("claude")
                    ))
                    .intelligencePriority(0.8)
                    .speedPriority(0.5)
                    .build())
                .systemPrompt("You are a helpful calculator assistant. Provide only the numerical answer.")
                .maxTokens(100)
                .build();

            // Request sampling from the client
            CreateMessageResult result = exchange.createMessage(samplingRequest);

            // Process the result
            String answer = ((McpSchema.TextContent) result.content()).text();
            return CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(answer)))
                .build();
        })
        .build();

    // Add the tool to the server
    server.addTool(calculatorTool);
    ```

=== "Async API"

    ```java
    // Create a server
    McpAsyncServer server = McpServer.async(transportProvider)
        .serverInfo("my-server", "1.0.0")
        .build();

    // Define a tool that uses sampling
    var calculatorTool = AsyncToolSpecification.builder()
        .tool(Tool.builder()
            .name("ai-calculator")
            .description("Performs calculations using AI")
            .inputSchema(schema)
            .build())
        .callHandler((exchange, request) -> {
            // Check if client supports sampling
            if (exchange.getClientCapabilities().sampling() == null) {
                return Mono.just(CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent("Client does not support AI capabilities")))
                    .build());
            }

            // Create a sampling request
            CreateMessageRequest samplingRequest = CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
                    new McpSchema.TextContent("Calculate: " + request.arguments().get("expression")))))
                .modelPreferences(McpSchema.ModelPreferences.builder()
                    .hints(List.of(
                        McpSchema.ModelHint.of("claude-3-sonnet"),
                        McpSchema.ModelHint.of("claude")
                    ))
                    .intelligencePriority(0.8)
                    .speedPriority(0.5)
                    .build())
                .systemPrompt("You are a helpful calculator assistant. Provide only the numerical answer.")
                .maxTokens(100)
                .build();

            // Request sampling from the client
            return exchange.createMessage(samplingRequest)
                .map(result -> {
                    String answer = ((McpSchema.TextContent) result.content()).text();
                    return CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(answer)))
                        .build();
                });
        })
        .build();

    // Add the tool to the server
    server.addTool(calculatorTool)
        .subscribe();
    ```

The `CreateMessageRequest` object allows you to specify: `Content` - the input text or image for the model,
`Model Preferences` - hints and priorities for model selection, `System Prompt` - instructions for the model's behavior and
`Max Tokens` - maximum length of the generated response.

### Using Elicitation from a Server

Servers can request user input from connected clients that support elicitation:

```java
var tool = SyncToolSpecification.builder()
    .tool(Tool.builder()
        .name("confirm-action")
        .description("Confirms an action with the user")
        .inputSchema(schema)
        .build())
    .callHandler((exchange, request) -> {
        // Check if client supports elicitation
        if (exchange.getClientCapabilities().elicitation() == null) {
            return CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Client does not support elicitation")))
                .build();
        }

        // Request user confirmation
        ElicitRequest elicitRequest = ElicitRequest.builder()
            .message("Do you want to proceed with this action?")
            .requestedSchema(Map.of(
                "type", "object",
                "properties", Map.of("confirmed", Map.of("type", "boolean"))
            ))
            .build();

        ElicitResult result = exchange.elicit(elicitRequest);

        if (result.action() == ElicitResult.Action.ACCEPT) {
            // User accepted
            return CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Action confirmed")))
                .build();
        } else {
            return CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("Action declined")))
                .build();
        }
    })
    .build();
```

### Logging Support

The server provides structured logging capabilities that allow sending log messages to clients with different severity levels.
Log notifications can only be sent from within an existing client session, such as tools, resources, and prompts calls.

The server can send log messages using the `McpAsyncServerExchange`/`McpSyncServerExchange` object in the tool/resource/prompt handler function:

```java
var tool = new McpServerFeatures.AsyncToolSpecification(
    Tool.builder().name("logging-test").description("Test logging notifications").inputSchema(emptyJsonSchema).build(),
    null,
    (exchange, request) -> {

      exchange.loggingNotification( // Use the exchange to send log messages
          McpSchema.LoggingMessageNotification.builder()
            .level(McpSchema.LoggingLevel.DEBUG)
            .logger("test-logger")
            .data("Debug message")
            .build())
        .block();

      return Mono.just(CallToolResult.builder()
          .content(List.of(new McpSchema.TextContent("Logging test completed")))
          .build());
    });

var mcpServer = McpServer.async(mcpServerTransportProvider)
  .serverInfo("test-server", "1.0.0")
  .capabilities(
    ServerCapabilities.builder()
      .logging() // Enable logging support
      .tools(true)
      .build())
  .tools(tool)
  .build();
```

On the client side, you can register a logging consumer to receive log messages from the server:

```java
var mcpClient = McpClient.sync(transport)
        .loggingConsumer(notification -> {
            System.out.println("Received log message: " + notification.data());
        })
        .build();

mcpClient.initialize();
mcpClient.setLoggingLevel(McpSchema.LoggingLevel.INFO);
```

Clients can control the minimum logging level they receive through the `mcpClient.setLoggingLevel(level)` request. Messages below the set level will be filtered out.
Supported logging levels (in order of increasing severity): DEBUG (0), INFO (1), NOTICE (2), WARNING (3), ERROR (4), CRITICAL (5), ALERT (6), EMERGENCY (7)

## Error Handling

The SDK provides comprehensive error handling through the McpError class, covering protocol compatibility, transport communication, JSON-RPC messaging, tool execution, resource management, prompt handling, timeouts, and connection issues. This unified error handling approach ensures consistent and reliable error management across both synchronous and asynchronous operations.
