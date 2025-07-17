# WebFlux SSE Transport

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-webflux</artifactId>
</dependency>
```

```java
String MESSAGE_ENDPOINT = "/mcp/message";

@Configuration
static class MyConfig {

    // SSE transport
	@Bean
	public WebFluxSseServerTransport sseServerTransport() {
		return new WebFluxSseServerTransport(new ObjectMapper(), "/mcp/message");
	}

	// Router function for SSE transport used by Spring WebFlux to start an HTTP
	// server.
	@Bean
	public RouterFunction<?> mcpRouterFunction(WebFluxSseServerTransport transport) {
		return transport.getRouterFunction();
	}

	@Bean
	public McpAsyncServer mcpServer(ServerMcpTransport transport, OpenLibrary openLibrary) {

		// Configure server capabilities with resource support
		var capabilities = McpSchema.ServerCapabilities.builder()
			.resources(false, true) // No subscribe support, but list changes notifications
			.tools(true) // Tool support with list changes notifications
			.prompts(true) // Prompt support with list changes notifications
			.logging() // Logging support
			.build();

		// Create the server with both tool and resource capabilities
		var server = McpServer.using(transport)
			.serverInfo("MCP Demo Server", "1.0.0")
			.capabilities(capabilities)
			.resources(systemInfoResourceRegistration())
			.prompts(greetingPromptRegistration())
			.tools(openLibraryToolRegistrations(openLibrary))
			.async();
		
		return server;
	} 

    // ...

}
```
