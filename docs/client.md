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

### STDIO

Creates transport for process-based communication using stdin/stdout:

```java
ServerParameters params = ServerParameters.builder("npx")
    .args("-y", "@modelcontextprotocol/server-everything", "dir")
    .build();
McpTransport transport = new StdioClientTransport(params);
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
    McpTransport transport = new HttpClientSseClientTransport("http://your-mcp-server");
    ```
=== "SSE WebClient (external)"

    Creates WebFlux-based SSE client transport. Requires the `mcp-spring-webflux` dependency from [Spring AI](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-overview.html) 2.0+ (group `org.springframework.ai`):

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

## MCP Security

The [MCP Security](https://github.com/spring-ai-community/mcp-security) community library provides OAuth 2.0 authorization support for Spring AI MCP clients. It supports both `HttpClient`-based and `WebClient`-based (WebFlux) MCP clients used with the [Spring AI MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-client-boot-starter-docs.html).

!!! note
    This is a community project (`org.springaicommunity`), not officially part of the MCP Java SDK. It requires Spring AI 2.0.x (`mcp-client-security` version 0.1.x). For Spring AI 1.1.x, use version 0.0.6.

### Add Dependency

=== "Maven"

    ```xml
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>mcp-client-security</artifactId>
        <version>0.1.1</version>
    </dependency>
    ```

=== "Gradle"

    ```groovy
    implementation("org.springaicommunity:mcp-client-security:0.1.1")
    ```

### Authorization Flows

Three OAuth 2.0 flows are available:

- **Authorization Code** — For user-present scenarios. The client sends requests with a bearer token on behalf of the user. Use `OAuth2AuthorizationCodeSyncHttpRequestCustomizer` (HttpClient) or `McpOAuth2AuthorizationCodeExchangeFilterFunction` (WebClient).
- **Client Credentials** — For machine-to-machine communication without a user in the loop. Use `OAuth2ClientCredentialsSyncHttpRequestCustomizer` (HttpClient) or `McpOAuth2ClientCredentialsExchangeFilterFunction` (WebClient).
- **Hybrid** — For mixed scenarios where some calls (e.g., `tools/list` on startup) use client credentials, while user-specific calls (e.g., `tools/call`) use authorization code tokens. Use `OAuth2HybridSyncHttpRequestCustomizer` (HttpClient) or `McpOAuth2HybridExchangeFilterFunction` (WebClient).

!!! tip
    Use the **Hybrid** flow when Spring AI's autoconfiguration initializes MCP clients on startup (listing tools before a user is present), but tool calls are user-authenticated.

### Setup

Add the following to `application.properties`:

```properties
# Ensure MCP clients are sync
spring.ai.mcp.client.type=SYNC
# Disable auto-initialization (most MCP servers require authentication on every request)
spring.ai.mcp.client.initialized=false

# Authorization Code client registration (for user-present flows)
spring.security.oauth2.client.registration.authserver.client-id=<CLIENT_ID>
spring.security.oauth2.client.registration.authserver.client-secret=<CLIENT_SECRET>
spring.security.oauth2.client.registration.authserver.authorization-grant-type=authorization_code
spring.security.oauth2.client.registration.authserver.provider=authserver

# Client Credentials registration (for machine-to-machine or hybrid flows)
spring.security.oauth2.client.registration.authserver-client-credentials.client-id=<CLIENT_ID>
spring.security.oauth2.client.registration.authserver-client-credentials.client-secret=<CLIENT_SECRET>
spring.security.oauth2.client.registration.authserver-client-credentials.authorization-grant-type=client_credentials
spring.security.oauth2.client.registration.authserver-client-credentials.provider=authserver

# Authorization server issuer URI
spring.security.oauth2.client.provider.authserver.issuer-uri=<ISSUER_URI>
```

Then activate OAuth2 client support in a security configuration class:

```java
@Configuration
@EnableWebSecurity
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Client(Customizer.withDefaults())
                .build();
    }
}
```

### HttpClient-Based Client

When using `spring-ai-starter-mcp-client`, the transport is backed by the JDK's `HttpClient`. Expose a `McpSyncHttpClientRequestCustomizer` bean and an `AuthenticationMcpTransportContextProvider` on the client:

```java
@Configuration
class McpConfiguration {

    @Bean
    McpSyncClientCustomizer syncClientCustomizer() {
        return (name, syncSpec) ->
                syncSpec.transportContextProvider(
                        new AuthenticationMcpTransportContextProvider()
                );
    }

    @Bean
    McpSyncHttpClientRequestCustomizer requestCustomizer(
            OAuth2AuthorizedClientManager clientManager
    ) {
        // "authserver" must match the registration name in application.properties
        return new OAuth2AuthorizationCodeSyncHttpRequestCustomizer(
                clientManager,
                "authserver"
        );
    }
}
```

Replace `OAuth2AuthorizationCodeSyncHttpRequestCustomizer` with `OAuth2ClientCredentialsSyncHttpRequestCustomizer` or `OAuth2HybridSyncHttpRequestCustomizer` for the corresponding flow.

### WebClient-Based Client

When using `spring-ai-starter-mcp-client-webflux`, the transport is backed by Spring's reactive `WebClient`. Expose a `WebClient.Builder` bean configured with an `ExchangeFilterFunction`:

```java
@Configuration
class McpConfiguration {

    @Bean
    McpSyncClientCustomizer syncClientCustomizer() {
        return (name, syncSpec) ->
                syncSpec.transportContextProvider(
                        new AuthenticationMcpTransportContextProvider()
                );
    }

    @Bean
    WebClient.Builder mcpWebClientBuilder(OAuth2AuthorizedClientManager clientManager) {
        // "authserver" must match the registration name in application.properties
        return WebClient.builder().filter(
                new McpOAuth2AuthorizationCodeExchangeFilterFunction(
                        clientManager,
                        "authserver"
                )
        );
    }
}
```

Replace `McpOAuth2AuthorizationCodeExchangeFilterFunction` with `McpOAuth2ClientCredentialsExchangeFilterFunction` or `McpOAuth2HybridExchangeFilterFunction` for the corresponding flow.

When using the chat client's `.stream()` method, Reactor does not preserve thread-locals. Inject the authentication into the Reactor context manually:

```java
chatClient
    .prompt("<your prompt>")
    .stream()
    .content()
    .contextWrite(AuthenticationMcpTransportContextProvider.writeToReactorContext());
```

### Known Limitations

- Only `McpSyncClient` is supported; async clients are not.
- Set `spring.ai.mcp.client.initialized=false` when servers require authentication on every request, as Spring AI autoconfiguration will otherwise attempt to list tools on startup without a token.
- The SSE transport is supported by the client module (unlike the server module).
