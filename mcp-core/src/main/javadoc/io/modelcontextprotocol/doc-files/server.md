# Java MCP Server

## Server Features

The MCP Server is a foundational component in the Model Context Protocol (MCP) architecture that provides tools, resources, and capabilities to clients. It implements the server-side of the protocol, responsible for:

- Exposing tools that clients can discover and execute.
  Supports input and output schemas and returns structured and unstructured content types.
- Managing resources with URI-based access patterns
- Providing prompt templates and handling prompt requests
- Supporting capability negotiation with clients
- Implementing server-side protocol operations
- Managing concurrent client connections
- Providing structured logging, progress tracking, and notifications
- Provides `STDIO`, `Streamable-HTTP` and `SSE` server transport implementations without requiring external web frameworks.
- **Optional**, Spring-specific transport dependencies `io.modelcontextprotocol.sdk:mcp-spring-webflux`, `io.modelcontextprotocol.sdk:mcp-spring-webmvc` for [Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) users.

> **Tip:** This [quickstart demo](https://modelcontextprotocol.io/quickstart/server), based on Spring AI MCP, will show you how to build an MCP server.

The server supports both synchronous and asynchronous APIs, allowing for flexible integration in different application contexts.

### Sync API

```java
// Create a server with custom configuration
McpSyncServer syncServer = McpServer.sync(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .capabilities(ServerCapabilities.builder()
        .resources(false, true)  // Enable resource support
        .tools(true)             // Enable tool support
        .prompts(true)           // Enable prompt support
        .logging()               // Enable logging support
        .completions()           // Enable completions support
        .build())
    .build();

// Register tools, resources, and prompts
syncServer.addTool(syncToolSpecification);
syncServer.addResource(syncResourceSpecification);
syncServer.addPrompt(syncPromptSpecification);

// Close the server when done
syncServer.close();
```

#### Preserving thread-locals in handlers

`McpSyncServer` delegates execution to an underlying `McpAsyncServer`.
Execution of handlers for tools, resources, etc, may not happen on the thread calling the method.
In that case, thread-locals are lost, which may break certain features in frameworks relying on thread-bound work.
To ensure execution happens on the calling thread, and that thread-locals remain available, set `McpServer.sync(...).immediateExecution(true)`.

> **Note:** This is only relevant to Sync servers. Async servers use the reactive stack and should not rely on thread-locals.

### Async API

```java
// Create an async server with custom configuration
McpAsyncServer asyncServer = McpServer.async(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .capabilities(ServerCapabilities.builder()
        .resources(false, true)     // Enable resource support
        .tools(true)                // Enable tool support
        .prompts(true)              // Enable prompt support
        .logging()                  // Enable logging support
        .completions()              // Enable completions support
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

## Server Transport Providers

The transport layer in the MCP SDK is responsible for handling the communication between clients and servers.
It provides different implementations to support various communication protocols and patterns.
The SDK includes several built-in transport provider implementations:

### STDIO Transport

Create in-process based transport:

```java
StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(new ObjectMapper());
```

Provides bidirectional JSON-RPC message handling over standard input/output streams with non-blocking message processing, serialization/deserialization, and graceful shutdown support.

Key features:
- Bidirectional communication through stdin/stdout
- Process-based integration support
- Simple setup and configuration
- Lightweight implementation

### WebFlux Transport

WebFlux-based SSE and Streamable-HTTP server transport. Requires the `mcp-spring-webflux` dependency (see [Getting Started](getting-started.html)).

#### Streamable-HTTP (WebFlux)

```java
@Configuration
class McpConfig {
    @Bean
    WebFluxStreamableServerTransportProvider webFluxStreamableServerTransportProvider(ObjectMapper mapper) {
        return this.mcpStreamableServerTransportProvider = WebFluxStreamableServerTransportProvider.builder()
              .objectMapper(mapper)
              .messageEndpoint("/mcp")
              .build();
    }

    @Bean
    RouterFunction<?> mcpRouterFunction(WebFluxStreamableServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }
}
```

Implements the Streamable HTTP transport specification, providing:
- Bidirectional communication through Streamable-HTTP
- Reactive HTTP streaming with WebFlux
- Concurrent client connections through Streamable-HTTP endpoints
- Message routing and session management
- Graceful shutdown capabilities

> **Note:** Current implementation lacks resumability due to lack of session storage

> **Tip:** In distributed environments with multiple MCP Server instances, proper message routing is required.
> To achieve scalability without significant infrastructure overhead consider the `Stateless Streamable-HTTP` server transport implementation.

#### Stateless Streamable-HTTP (WebFlux)

Stateless MCP servers are designed for simplified deployments where session state is not maintained between requests.
They implement a subset of `Streamable-HTTP` specification that return `application/json` responses.
They are called stateless because unlike the standard Streamable-HTTP transport, they do not maintain session state between requests.
These servers are ideal for microservices architectures and cloud-native deployments.

```java
@Configuration
class McpConfig {
    @Bean
    WebFluxStatelessServerTransport webFluxStatelessServerTransport(ObjectMapper mapper) {
        return this.mcpStatelessServerTransport = WebFluxStatelessServerTransport.builder()
              .objectMapper(mapper)
              .messageEndpoint("/mcp")
              .build();
    }

    @Bean
    RouterFunction<?> mcpRouterFunction(WebFluxStatelessServerTransport transportProvider) {
        return transportProvider.getRouterFunction();
    }

    @Bean
    public McpStatelessSyncServer prepareSyncServerBuilder(WebFluxStatelessServerTransport statelessServerTransport) {
      return McpServer.sync(statelessServerTransport)
              //...
              .build();
    }

}
```

Implements the MCP Stateless Streamable-HTTP transport specification, providing:
- Unidirectional (client to server) communication through Streamable-HTTP
- Reactive HTTP streaming with WebFlux
- Concurrent client connections through Stateless endpoints
- Message routing and session management
- Graceful shutdown capabilities

> **Note:** Current implementation doesn't support sending Notifications back to the clients.

#### SSE (WebFlux)

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

Implements the MCP SSE transport specification, providing:
- Reactive HTTP streaming with WebFlux
- Concurrent client connections through SSE endpoints
- Message routing and session management
- Graceful shutdown capabilities

### WebMVC Transport

Creates WebMvc-based SSE server transport. Requires the `mcp-spring-webmvc` dependency.

#### Streamable-HTTP (WebMVC)

```java
@Configuration
@EnableWebMvc
class McpConfig {
    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableHttpServerTransportProvider(ObjectMapper mapper) {
        return new WebMvcStreamableServerTransportProvider(mapper, "/mcp/message");
    }

    @Bean
    RouterFunction<ServerResponse> mcpRouterFunction(WebMvcStreamableServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }
}
```

Implements the Streamable HTTP transport specification, providing:
- Bidirectional communication through Streamable-HTTP
- Reactive HTTP streaming with WebFlux
- Concurrent client connections through Streamable-HTTP endpoints
- Message routing and session management
- Graceful shutdown capabilities

> **Note:** Current implementation lacks resumability due to lack of session storage

> **Tip:** In distributed environments with multiple MCP Server instances, proper message routing is required.
> To achieve scalability without significant infrastructure overhead consider the `Stateless Streamable-HTTP` server transport implementation.

#### Stateless Streamable-HTTP (WebMVC)

Stateless MCP servers are designed for simplified deployments where session state is not maintained between requests.
They implement a subset of `Streamable-HTTP` specification that return `application/json` responses.
They are called stateless because unlike the standard Streamable-HTTP transport, they do not maintain session state between requests.
These servers are ideal for microservices architectures and cloud-native deployments.

```java
@Configuration
@EnableWebMvc
static class McpConfig {

  @Bean
  public WebMvcStatelessServerTransport webMvcStatelessServerTransport() {

    return WebMvcStatelessServerTransport.builder()
      .objectMapper(new ObjectMapper())
      .messageEndpoint(MESSAGE_ENDPOINT)
      .build();

  }

  @Bean
  public RouterFunction<ServerResponse> routerFunction(WebMvcStatelessServerTransport statelessServerTransport) {
    return statelessServerTransport.getRouterFunction();
  }

  @Bean
  public McpStatelessSyncServer prepareSyncServerBuilder(WebMvcStatelessServerTransport statelessServerTransport) {
    return McpServer.sync(statelessServerTransport)
            //...
            .build();
  }
}
```

Implements the MCP Streamable-HTTP, Stateless transport specification, providing:
- Unidirectional (client to server) communication through Streamable-HTTP
- Reactive HTTP streaming with WebFlux
- Concurrent client connections through Stateless endpoints
- Message routing and session management
- Graceful shutdown capabilities

> **Note:** Current implementation doesn't support sending Notifications back to the clients.

#### SSE (WebMVC)

```java
@Configuration
@EnableWebMvc
class McpConfig {
    @Bean
    WebMvcSseServerTransportProvider webMvcSseServerTransportProvider(ObjectMapper mapper) {
        return new WebMvcSseServerTransportProvider(mapper, "/mcp/message");
    }

    @Bean
    RouterFunction<ServerResponse> mcpRouterFunction(WebMvcSseServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }
}
```

Implements the MCP SSE transport specification, providing:
- Server-side event streaming
- Integration with Spring WebMVC
- Support for traditional web applications
- Synchronous operation handling

### Servlet Transport

Creates a Servlet-based `SSE`, `Streamable-HTTP`, and `Stateless` server transport.
It can be used with any Servlet container.

#### Streamable-HTTP (Servlet)

```java
@Configuration
@EnableWebMvc
public class McpServerConfig implements WebMvcConfigurer {

    @Bean
    public HttpServletStreamableServerTransportProvider servletStreamableMcpSessionTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
          .objectMapper(new ObjectMapper())
          .contextExtractor(TEST_CONTEXT_EXTRACTOR)
          .mcpEndpoint(MESSAGE_ENDPOINT)
          .keepAliveInterval(Duration.ofSeconds(1))
          .build();
    }

    //(Optionally) To use it with a Spring Web application, you can register it as a Servlet bean
    @Bean
    public ServletRegistrationBean customServletBean(HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean(transportProvider);
    }
}
```

Implements the Streamable HTTP transport specification using the traditional Servlet API, providing:
- Bidirectional communication through Streamable-HTTP
- Asynchronous message handling using Servlet 6.0 async support
- Session management for multiple client connections
- One endpoint:
  - Message endpoint (configurable) for client-to-server requests
- Error handling and response formatting
- Graceful shutdown support

> **Note:** Current implementation lacks resumability due to lack of session storage

> **Tip:** In distributed environments with multiple MCP Server instances, proper message routing is required.
> To achieve scalability without significant infrastructure overhead consider the `Stateless Streamable-HTTP` server transport implementation.

#### Stateless Streamable-HTTP (Servlet)

Stateless MCP servers are designed for simplified deployments where session state is not maintained between requests.
They implement a subset of `Streamable-HTTP` specification that return `application/json` responses.
They are called stateless because unlike the standard Streamable-HTTP transport, they do not maintain session state between requests.
These servers are ideal for microservices architectures and cloud-native deployments.

```java
@Configuration
@EnableWebMvc
public class McpServerConfig implements WebMvcConfigurer {

    @Bean
    public HttpServletStatelessServerTransport servletStatelessMcpSessionTransport() {
        return HttpServletStatelessServerTransport.builder()
          .objectMapper(new ObjectMapper())
          .mcpEndpoint(MESSAGE_ENDPOINT)
          .keepAliveInterval(Duration.ofSeconds(1))
          .build();
    }


    //(Optionally) To use it with a Spring Web application, you can register it as a Servlet bean
    @Bean
    public ServletRegistrationBean customServletBean(HttpServletStatelessServerTransport transportProvider) {
        return new ServletRegistrationBean(transportProvider);
    }

    @Bean
    public McpStatelessSyncServer prepareSyncServerBuilder(HttpServletStatelessServerTransport statelessServerTransport) {
        return McpServer.sync(statelessServerTransport)
                .build();
    }
  }
}
```

Implements the Streamable-HTTP Stateless transport specification using the traditional Servlet API, providing:
- Unidirectional (client to server) communication through Streamable-HTTP
- Asynchronous message handling using Servlet 6.0 async support
- Session management for multiple client connections
- One endpoint:
  - Message endpoint (configurable) for client-to-server requests
- Error handling and response formatting
- Graceful shutdown support

> **Note:** Current implementation doesn't support sending Notifications back to the clients.

#### SSE (Servlet)

```java
@Configuration
@EnableWebMvc
public class McpServerConfig implements WebMvcConfigurer {

    @Bean
    public HttpServletSseServerTransportProvider servletSseServerTransportProvider() {
        return HttpServletSseServerTransportProvider.builder()
            .objectMapper(new ObjectMapper())
            .messageEndpoint("/mcp/message")
            .keepAliveInterval(Duration.ofSeconds(1))
            .build();
    }

    //(Optionally) To use it with a Spring Web application, you can register it as a Servlet bean
    @Bean
    public ServletRegistrationBean customServletBean(HttpServletSseServerTransportProvider transportProvider) {
        return new ServletRegistrationBean(transportProvider);
    }
}
```

Implements the MCP SSE transport specification using the traditional Servlet API, providing:
- Asynchronous message handling using Servlet 6.0 async support
- Session management for multiple client connections
- Two types of endpoints:
  - SSE endpoint (`/sse`) for server-to-client events
  - Message endpoint (configurable) for client-to-server requests
- Error handling and response formatting
- Graceful shutdown support

## Server Capabilities

The server can be configured with various capabilities:

```java
var capabilities = ServerCapabilities.builder()
    .resources(false, true)  // Resource support with list changes notifications
    .tools(true)            // Tool support with list changes notifications
    .prompts(true)          // Prompt support with list changes notifications
    .logging()              // Enable logging support (enabled by default with logging level INFO)
    .build();
```

### Tool Specification

The Model Context Protocol allows servers to [expose tools](https://modelcontextprotocol.io/specification/latest/server/tools/) that can be invoked by language models.
The Java SDK allows implementing a Tool Specifications with their handler functions.
Tools enable AI models to perform calculations, access external APIs, query databases, and manipulate files:

#### Sync

```java
// Sync tool specification
var schema = """
            {
              "type" : "object",
              "id" : "urn:jsonschema:Operation",
              "properties" : {
                "operation" : {
                  "type" : "string"
                },
                "a" : {
                  "type" : "number"
                },
                "b" : {
                  "type" : "number"
                }
              }
            }
            """;
var syncToolSpecification = new McpServerFeatures.SyncToolSpecification(
    new Tool("calculator", "Basic calculator", schema),
    (exchange, arguments) -> {
        // Tool implementation
        return new CallToolResult(result, false);
    }
);
```

#### Async

```java
// Async tool specification
var schema = """
            {
              "type" : "object",
              "id" : "urn:jsonschema:Operation",
              "properties" : {
                "operation" : {
                  "type" : "string"
                },
                "a" : {
                  "type" : "number"
                },
                "b" : {
                  "type" : "number"
                }
              }
            }
            """;
var asyncToolSpecification = new McpServerFeatures.AsyncToolSpecification(
    new Tool("calculator", "Basic calculator", schema),
    (exchange, arguments) -> {
        // Tool implementation
        return Mono.just(new CallToolResult(result, false));
    }
);
```

The Tool specification includes a Tool definition with `name`, `description`, and `parameter schema` followed by a call handler that implements the tool's logic.
The function's first argument is `McpAsyncServerExchange` for client interaction, and the second is a map of tool arguments.

### Resource Specification

Specification of a resource with its handler function.
Resources provide context to AI models by exposing data such as: File contents, Database records, API responses, System information, Application state.
Example resource specification:

#### Sync

```java
// Sync resource specification
var syncResourceSpecification = new McpServerFeatures.SyncResourceSpecification(
    new Resource("custom://resource", "name", "description", "mime-type", null),
    (exchange, request) -> {
        // Resource read implementation
        return new ReadResourceResult(contents);
    }
);
```

#### Async

```java
// Async resource specification
var asyncResourceSpecification = new McpServerFeatures.AsyncResourceSpecification(
    new Resource("custom://resource", "name", "description", "mime-type", null),
    (exchange, request) -> {
        // Resource read implementation
        return Mono.just(new ReadResourceResult(contents));
    }
);
```

The resource specification comprised of resource definitions and resource read handler.
The resource definition including `name`, `description`, and `MIME type`.
The first argument of the function that handles resource read requests is an `McpAsyncServerExchange` upon which the server can
interact with the connected client.
The second arguments is an `McpSchema.ReadResourceRequest`.

### Prompt Specification

As part of the [Prompting capabilities](https://modelcontextprotocol.io/specification/latest/server/prompts/), MCP provides a standardized way for servers to expose prompt templates to clients.
The Prompt Specification is a structured template for AI model interactions that enables consistent message formatting, parameter substitution, context injection, response formatting, and instruction templating.

#### Sync

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

#### Async

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
The first argument is `McpAsyncServerExchange` for client interaction, and the second argument is a `GetPromptRequest` instance.

### Completion Specification

As part of the [Completion capabilities](https://modelcontextprotocol.io/specification/latest/server/utilities/completion), MCP provides a standardized way for servers to offer argument autocompletion suggestions for prompts and resource URIs.

#### Sync

```java
// Sync completion specification
var syncCompletionSpecification = new McpServerFeatures.SyncCompletionSpecification(
			new McpSchema.PromptReference("code_review"), (exchange, request) -> {

        // completion implementation ...

        return new McpSchema.CompleteResult(
            new CompleteResult.CompleteCompletion(
              List.of("python", "pytorch", "pyside"),
              10, // total
              false // hasMore
            ));
      }
);

// Create a sync server with completion capabilities
var mcpServer = McpServer.sync(mcpServerTransportProvider)
  .capabilities(ServerCapabilities.builder()
    .completions() // enable completions support
      // ...
    .build())
  // ...
  .completions(syncCompletionSpecification) // register completion specification
  .build();

```

#### Async

```java
// Async prompt specification
var asyncCompletionSpecification = new McpServerFeatures.AsyncCompletionSpecification(
			new McpSchema.PromptReference("code_review"), (exchange, request) -> {

        // completion implementation ...

        return Mono.just(new McpSchema.CompleteResult(
            new CompleteResult.CompleteCompletion(
              List.of("python", "pytorch", "pyside"),
              10, // total
              false // hasMore
            )));
      }
);

// Create a async server with completion capabilities
var mcpServer = McpServer.async(mcpServerTransportProvider)
  .capabilities(ServerCapabilities.builder()
    .completions() // enable completions support
      // ...
    .build())
  // ...
  .completions(asyncCompletionSpecification) // register completion specification
  .build();

```

The `McpSchema.CompletionReference` definition defines the type (`PromptReference` or `ResourceReference`) and the identifier for the completion specification (e.g handler).
The handler function processes requests and returns the completion response.
The first argument is `McpAsyncServerExchange` for client interaction, and the second argument is a `CompleteRequest` instance.

Check the [using completion](client.html#using-completion) to learn how to use the completion capabilities on the client side.

### Using Sampling from a Server

To use [Sampling capabilities](https://modelcontextprotocol.io/specification/latest/client/sampling/), you need a compatible client that supports sampling.
No special server configuration is needed, but verify client sampling support before making requests.
Learn about [client sampling support](client.html#sampling-support).

When a compatible client connects to a stateful server, the server can request language model generations:

#### Sync API

```java
// Create a server
McpSyncServer server = McpServer.sync(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .build();

// Define a tool that uses sampling
var calculatorTool = new McpServerFeatures.SyncToolSpecification(
    new Tool("ai-calculator", "Performs calculations using AI", schema),
    (exchange, arguments) -> {
        // Check if client supports sampling
        if (exchange.getClientCapabilities().sampling() == null) {
            return new CallToolResult("Client does not support AI capabilities", false);
        }

        // Create a sampling request
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
            .messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
                new McpSchema.TextContent("Calculate: " + arguments.get("expression")))
            .modelPreferences(McpSchema.ModelPreferences.builder()
                .hints(List.of(
                    McpSchema.ModelHint.of("claude-3-sonnet"),
                    McpSchema.ModelHint.of("claude")
                ))
                .intelligencePriority(0.8)  // Prioritize intelligence
                .speedPriority(0.5)         // Moderate speed importance
                .build())
            .systemPrompt("You are a helpful calculator assistant. Provide only the numerical answer.")
            .maxTokens(100)
            .build();

        // Request sampling from the client
        McpSchema.CreateMessageResult result = exchange.createMessage(request);

        // Process the result
        String answer = result.content().text();
        return new CallToolResult(answer, false);
    }
);

// Add the tool to the server
server.addTool(calculatorTool);
```

#### Async API

```java
// Create a server
McpAsyncServer server = McpServer.async(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .build();

// Define a tool that uses sampling
var calculatorTool = new McpServerFeatures.AsyncToolSpecification(
    new Tool("ai-calculator", "Performs calculations using AI", schema),
    (exchange, arguments) -> {
        // Check if client supports sampling
        if (exchange.getClientCapabilities().sampling() == null) {
            return Mono.just(new CallToolResult("Client does not support AI capabilities", false));
        }

        // Create a sampling request
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
            .content(new McpSchema.TextContent("Calculate: " + arguments.get("expression")))
            .modelPreferences(McpSchema.ModelPreferences.builder()
                .hints(List.of(
                    McpSchema.ModelHint.of("claude-3-sonnet"),
                    McpSchema.ModelHint.of("claude")
                ))
                .intelligencePriority(0.8)  // Prioritize intelligence
                .speedPriority(0.5)         // Moderate speed importance
                .build())
            .systemPrompt("You are a helpful calculator assistant. Provide only the numerical answer.")
            .maxTokens(100)
            .build();

        // Request sampling from the client
        return exchange.createMessage(request)
            .map(result -> {
                // Process the result
                String answer = result.content().text();
                return new CallToolResult(answer, false);
            });
    }
);

// Add the tool to the server
server.addTool(calculatorTool)
    .subscribe();
```

The `CreateMessageRequest` object allows you to specify: `Content` - the input text or image for the model,
`Model Preferences` - hints and priorities for model selection, `System Prompt` - instructions for the model's behavior and
`Max Tokens` - maximum length of the generated response.

### Using Elicitation from a Server

To use [Elicitation capabilities](https://modelcontextprotocol.io/specification/latest/client/elicitation), you need a compatible client that supports elicitation.
No special server configuration is needed, but verify client elicitation support before making requests.
Learn about [client elicitation support](client.html#elicitation-support).

When a compatible client connects to a stateful server, the server can request language model generations:

#### Sync API

```java
// Create a server
McpSyncServer server = McpServer.sync(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .build();

// Define a tool that uses sampling
var calculatorTool = new McpServerFeatures.SyncToolSpecification(
    new Tool("ai-calculator", "Performs calculations using AI", schema),
    (exchange, arguments) -> {
        // Check if client supports elicitation
        if (exchange.getClientCapabilities().elicitation() == null) {
            return new CallToolResult("Client does not support elicitation capabilities", false);
        }

        // Create a elicitation request
        McpSchema.ElicitRequest request = McpSchema.ElicitRequest.builder()
					.message("Test message")
					.requestedSchema(
							Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
					.build();

        // Request elicitation  from the client
        McpSchema.ElicitResult result = exchange.createElicitation(request);

        // Process the result
        Map<String, Object> answer = result.content();
        return new CallToolResult(answer, false);
    }
);

// Add the tool to the server
server.addTool(calculatorTool);
```

#### Async API

```java
// Create a server
McpAsyncServer server = McpServer.async(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .build();

// Define a tool that uses elicitation
var calculatorTool = new McpServerFeatures.AsyncToolSpecification(
    new Tool("ai-calculator", "Performs calculations using AI", schema),
    (exchange, arguments) -> {
        // Check if client supports elicitation
        if (exchange.getClientCapabilities().elicitation() == null) {
            return Mono.just(new CallToolResult("Client does not support elicitation capabilities", false));
        }

        // Create a elicitation request
        McpSchema.ElicitRequest request = McpSchema.ElicitRequest.builder()
            .message("Test message")
            .requestedSchema(
                    Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
            .build();

        // Request elicitation from the client
        return exchange.createElicitation(request)
            .map(result -> {
                // Process the result
                Map<String, Object> answer = result.content();
                return new CallToolResult(answer, false);
            });
    }
);

// Add the tool to the server
server.addTool(calculatorTool)
    .subscribe();
```

### Logging Support

The server provides structured logging capabilities that allow sending log messages to clients with different severity levels. The
log notifications can only be sent from within an existing client session, such as tools, resources, and prompts calls.

For example, we can send a log message from within a tool handler function.
On the client side, you can register a logging consumer to receive log messages from the server and set the minimum logging level to filter messages.

```java
var mcpClient = McpClient.sync(transport)
        .loggingConsumer(notification -> {
            System.out.println("Received log message: " + notification.data());
        })
        .build();

mcpClient.initialize();

mcpClient.setLoggingLevel(McpSchema.LoggingLevel.INFO);

// Call the tool that sends logging notifications
CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("logging-test", Map.of()));
```

The server can send log messages using the `McpAsyncServerExchange`/`McpSyncServerExchange` object in the tool/resource/prompt handler function:

```java
var tool = new McpServerFeatures.AsyncToolSpecification(
    new McpSchema.Tool("logging-test", "Test logging notifications", emptyJsonSchema),
    (exchange, request) -> {

      exchange.loggingNotification( // Use the exchange to send log messages
          McpSchema.LoggingMessageNotification.builder()
            .level(McpSchema.LoggingLevel.DEBUG)
            .logger("test-logger")
            .data("Debug message")
            .build())
        .block();

      return Mono.just(new CallToolResult("Logging test completed", false));
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

Clients can control the minimum logging level they receive through the `mcpClient.setLoggingLevel(level)` request. Messages below the set level will be filtered out.
Supported logging levels (in order of increasing severity): DEBUG (0), INFO (1), NOTICE (2), WARNING (3), ERROR (4), CRITICAL (5), ALERT (6), EMERGENCY (7)

## Error Handling

The SDK provides comprehensive error handling through the McpError class, covering protocol compatibility, transport communication, JSON-RPC messaging, tool execution, resource management, prompt handling, timeouts, and connection issues. This unified error handling approach ensures consistent and reliable error management across both synchronous and asynchronous operations.
