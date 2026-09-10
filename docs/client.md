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

    The Spring-specific WebFlux transport (`mcp-spring-webflux`) is now part of [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`) and is no longer shipped by this SDK.
    See the [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-client-boot-starter-docs.html) documentation for Spring-based client setup.

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
        CallToolRequest.builder("calculator")
            .arguments(Map.of("operation", "add", "a", 2, "b", 3))
            .build()
    );

    // List and read resources
    ListResourcesResult resources = client.listResources();
    ReadResourceResult resource = client.readResource(
        ReadResourceRequest.builder("resource://uri").build()
    );

    // List and use prompts
    ListPromptsResult prompts = client.listPrompts();
    GetPromptResult prompt = client.getPrompt(
        GetPromptRequest.builder("greeting").arguments(Map.of("name", "Spring")).build()
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
            return client.callTool(CallToolRequest.builder("calculator")
                .arguments(Map.of("operation", "add", "a", 2, "b", 3))
                .build());
        })
        .flatMap(result -> {
            return client.listResources()
                .flatMap(resources ->
                    client.readResource(ReadResourceRequest.builder("resource://uri").build())
                );
        })
        .flatMap(resource -> {
            return client.listPrompts()
                .flatMap(prompts ->
                    client.getPrompt(GetPromptRequest.builder("greeting")
                        .arguments(Map.of("name", "Spring"))
                        .build())
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

### STDIO

Creates transport for process-based communication using stdin/stdout:

```java
ServerParameters params = ServerParameters.builder("npx")
    .args("-y", "@modelcontextprotocol/server-everything", "dir")
    .build();
McpTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
```

### Streamable HTTP

=== "Streamable HttpClient"

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

=== "Streamable WebClient (external)"

    Creates Streamable HTTP WebClient-based client transport. Requires the `mcp-spring-webflux` dependency from [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`):

    ```java
    McpTransport transport = WebFluxSseClientTransport
        .builder(WebClient.builder().baseUrl("http://your-mcp-server"))
        .build();
    ```

### SSE HTTP (Legacy)

=== "SSE HttpClient"

    Creates a framework-agnostic (pure Java API) SSE client transport. Included in the core `mcp` module:

    ```java
    McpTransport transport = HttpClientSseClientTransport.builder("http://your-mcp-server").build();
    ```
=== "SSE WebClient (external)"

    Creates WebFlux-based SSE client transport. Requires the `mcp-spring-webflux` dependency from [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`):

    ```java
    WebClient.Builder webClientBuilder = WebClient.builder()
        .baseUrl("http://your-mcp-server");
    McpTransport transport = new WebFluxSseClientTransport(webClientBuilder);
    ```

## Protocol Version Negotiation

During `initialize()`, the client sends the list of protocol versions its transport supports (newest first) and the server picks one it also supports. The negotiated version is returned on the `InitializeResult`:

```java
McpSyncClient client = McpClient.sync(transport).build();

InitializeResult initResult = client.initialize();
String negotiatedVersion = initResult.protocolVersion();
```

Built-in transports (`StdioClientTransport`, `HttpClientStreamableHttpTransport`, `HttpClientSseClientTransport`) advertise every protocol version the SDK understands (`2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`) via their default `protocolVersions()` implementation, so negotiation normally settles on the newest version both sides support. To restrict a custom transport to a specific subset of versions, override `protocolVersions()` on your `McpClientTransport` implementation:

```java
public class RestrictedTransport extends StdioClientTransport {
    // ...
    @Override
    public List<String> protocolVersions() {
        return List.of("2025-06-18"); // only negotiate this version
    }
}
```

If the server responds with a version the transport didn't advertise, `initialize()` fails with an `McpError`.

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
// Configure form elicitation handler
Function<ElicitFormRequest, ElicitResult> formElicitationHandler = request -> {
    // Present the request to the user and collect their response
    // The request contains a message and a schema describing the expected input
    Map<String, Object> userResponse = collectUserInput(request.message(), request.requestedSchema());
    return new ElicitResult(ElicitResult.Action.ACCEPT, userResponse);
};

// Configure URL elicitation handler
Function<ElicitUrlRequest, ElicitResult> urlElicitationHandler = request -> {
    // Prompt the user to visit the URL
    // e.g. openBrowser(request.url());
    return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of());
};

// Create client with elicitation support
var client = McpClient.sync(transport)
    .capabilities(ClientCapabilities.builder()
        .elicitation(true, true) // enables both form and URL elicitation
        .build())
    .elicitation(formElicitationHandler)
    .urlElicitation(urlElicitationHandler)
    .build();
```

The `ElicitResult` supports three actions:

- `ACCEPT` - The user accepted and provided the requested information
- `DECLINE` - The user declined to provide the information
- `CANCEL` - The operation was cancelled

You can optionally have the client fill in missing values from the schema's `default` declarations before returning an accepted result to the server:

```java
var client = McpClient.sync(transport)
    .applyElicitationDefaults(true)   // default is false
    .elicitation(formElicitationHandler)
    .build();
```

When enabled, any keys absent from an accepted `ElicitResult.content` are populated with the `default` values declared in the request's `requestedSchema`.

#### URL Elicitation Required Handling

When a server requires out-of-band URL elicitation but the client has not negotiated support for it (or the server strictly requires out-of-band handling), the server may return a `URL_ELICITATION_REQUIRED` error during tool execution or prompt retrieval.

```java
try {
    mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
} catch (McpError e) {
    if (e.getJsonRpcError().code() == McpSchema.ErrorCodes.URL_ELICITATION_REQUIRED) {
        // Extract elicitation requests from the error data
        Map<String, Object> data = (Map<String, Object>) e.getJsonRpcError().data();
        TypeRef<List<McpSchema.ElicitUrlRequest>> typeRef = new TypeRef<>() {};
        var requests = McpJsonDefaults.getMapper()
                .convertValue(data.get("elicitations"), typeRef);

		for (var req : requests) {
            // handle elicitation requests
        }
    }
}
```

#### Elicitation Complete Notification (SEP-1036)

After a user finishes an out-of-band URL elicitation flow (for example, completing an OAuth authorization in a browser), the server sends a `notifications/elicitation/complete` message so the client knows it can stop waiting and re-check the outcome. Register a consumer to receive it:

```java
var client = McpClient.sync(transport)
    .capabilities(ClientCapabilities.builder()
        .elicitation(true, true)
        .build())
    .urlElicitation(urlElicitationHandler)
    .elicitationCompleteConsumer(notification -> {
        System.out.println("Elicitation " + notification.elicitationId() + " completed, re-checking outcome");
    })
    .build();
```

On the server side, send the notification once the out-of-band flow resolves (e.g. after the user completes the OAuth redirect), using the ID assigned to the original `ElicitUrlRequest` and the session that issued it:

```java
server.sendElicitationComplete(exchange.sessionId(),
    new McpSchema.ElicitationCompleteNotification("oauth-123"));
```

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
CallToolResult result = mcpClient.callTool(CallToolRequest.builder("logging-test").build());
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

### Pinging the Server

The client can send a `ping` request to check that the server is alive and responsive:

```java
McpSyncClient client = McpClient.sync(transport).build();
client.initialize();

Object result = client.ping(); // blocks until the server responds, or the request times out
```

The async equivalent, `McpAsyncClient.ping()`, returns a `Mono<Object>` that completes when the server responds.

### Request Timeouts and Cancellation

Every request the client sends (`callTool`, `readResource`, `ping`, etc.) is bounded by the `requestTimeout` configured on the client builder (default 20 seconds):

```java
McpSyncClient client = McpClient.sync(transport)
    .requestTimeout(Duration.ofSeconds(10))
    .build();
```

If a response doesn't arrive within that window, the pending call fails with a timeout error (an `McpError` on the sync API, or an error signal on the corresponding `Mono` for the async API) instead of blocking indefinitely. The server builder has an equivalent `requestTimeout(Duration)` option (default 10 hours) bounding requests the server sends to the client, such as sampling or elicitation.

The SDK does not currently send or process the MCP `notifications/cancelled` message, so timing out a request only stops the caller from waiting on it — it does not notify the other side that the in-flight operation should stop executing.

## Using MCP Clients

### Tool Execution

Tools are server-side functions that clients can discover and execute. The MCP client provides methods to list available tools and execute them with specific parameters. Each tool has a unique name and accepts a map of parameters.

=== "Sync API"

    ```java
    // List available tools
    ListToolsResult tools = client.listTools();

    // Call a tool with a CallToolRequest
    CallToolResult result = client.callTool(
        CallToolRequest.builder("calculator")
            .arguments(Map.of(
                "operation", "add",
                "a", 1,
                "b", 2
            ))
            .build()
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
    client.callTool(CallToolRequest.builder("calculator")
            .arguments(Map.of(
                "operation", "add",
                "a", 1,
                "b", 2
            ))
            .build())
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

### Result Caching

When a server marks a response cacheable, the client stores it and answers later identical calls
from that store instead of going back to the server. This covers `listTools`, `listPrompts`,
`listResources`, `listResourceTemplates`, and `readResource`.

Caching is on by default and does nothing until a server opts in, because only a response that
carries a time to live (TTL) is ever stored. An entry is dropped when its TTL lapses, when the
matching `*_changed` or `resources/updated` notification arrives, and when the client reconnects
or closes.

To read current server state without waiting for either the TTL or a notification, drop every
entry:

```java
client.invalidateCache();
ListToolsResult fresh = client.listTools();
```

To ignore server TTLs altogether, turn caching off:

```java
var client = McpClient.sync(transport)
    .enableResultCaching(false)
    .build();
```

**Choosing where entries are kept**

Entries live in a bounded in-memory store that holds 512 of them and evicts the oldest first. To
use a cache library or share one store across several clients, implement `McpClientCacheStore` and
pass it to the builder:

```java
// MyCacheStore is your own implementation, for example over Caffeine
var client = McpClient.sync(transport)
    .cacheStore(new MyCacheStore())
    .build();
```

A store receives an `McpClientCacheKey` identifying the request, the value, and a TTL in
milliseconds. It must be safe for concurrent use, and it must not return an entry whose TTL has
lapsed. The client decides what may be cached and when an entry has to go, so a store only has to
honor those decisions.

**What the client won't cache**

- A listing that spans several pages. Pairing a cached first page with a later page fetched fresh
  would mix two different views of the server's catalog.
- A response whose TTL is missing, zero, or negative.
- A response that arrives after the notification that invalidates it, which would otherwise pin a
  stale listing for the whole TTL.

A TTL longer than 24 hours is capped at 24 hours, so a client can't be left serving a response the
server has no way to invalidate.

### Pagination

`listTools`, `listResources`, `listResourceTemplates`, and `listPrompts` all accept an optional opaque `cursor` string, and their results carry a `nextCursor` that is non-null while more pages remain. Loop until `nextCursor` is `null` to collect every page:

```java
List<McpSchema.Tool> allTools = new ArrayList<>();
String cursor = null;
do {
    ListToolsResult page = client.listTools(cursor);
    allTools.addAll(page.tools());
    cursor = page.nextCursor();
} while (cursor != null);
```

Each paginated method also accepts an optional `_meta` map alongside the cursor, e.g. `client.listTools(cursor, Map.of("key", "value"))`, for passing request metadata through to the server.

### Resource Access

Resources represent server-side data sources that clients can access using URI templates. The MCP client provides methods to discover available resources and retrieve their contents through a standardized interface.

=== "Sync API"

    ```java
    // List available resources
    ListResourcesResult resources = client.listResources();

    // Read a resource
    ReadResourceResult resource = client.readResource(
        ReadResourceRequest.builder("resource://uri").build()
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
    client.readResource(ReadResourceRequest.builder("resource://uri").build())
        .subscribe();
    ```

### Resource Subscriptions

When the server advertises `resources.subscribe` support, clients can subscribe to individual resources and receive a callback whenever the server pushes a `notifications/resources/updated` notification for that URI. The SDK automatically re-reads the resource on notification and delivers the updated contents to the registered consumer.

Register a consumer on the client builder, then subscribe/unsubscribe at any time:

=== "Sync API"

    ```java
    McpSyncClient client = McpClient.sync(transport)
        .resourcesUpdateConsumer(contents -> {
            // called with the updated resource contents after each notification
            System.out.println("Resource updated: " + contents);
        })
        .build();

    client.initialize();

    // Subscribe to a specific resource URI
    client.subscribeResource(McpSchema.SubscribeRequest.builder("custom://resource").build());

    // ... later, stop receiving updates
    client.unsubscribeResource(McpSchema.UnsubscribeRequest.builder("custom://resource").build());
    ```

=== "Async API"

    ```java
    McpAsyncClient client = McpClient.async(transport)
        .resourcesUpdateConsumer(contents -> Mono.fromRunnable(() -> {
            System.out.println("Resource updated: " + contents);
        }))
        .build();

    client.initialize()
        .then(client.subscribeResource(McpSchema.SubscribeRequest.builder("custom://resource").build()))
        .subscribe();

    // ... later, stop receiving updates
    client.unsubscribeResource(McpSchema.UnsubscribeRequest.builder("custom://resource").build())
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
        GetPromptRequest.builder("greeting").arguments(Map.of("name", "World")).build()
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
    client.getPrompt(GetPromptRequest.builder("greeting").arguments(Map.of("name", "World")).build())
        .subscribe();
    ```
