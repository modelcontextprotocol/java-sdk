# WebFlux SSE Transport

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-webflux</artifactId>
</dependency>
```

```java
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;

@Configuration
public class MyConfig {
    // SSE transport
    @Bean
    public WebFluxSseServerTransportProvider sseServerTransportProvider() {
        return WebFluxSseServerTransportProvider.builder()
                .messageEndpoint("/mcp/message")
                .sseEndpoint("/mcp")
                .build();
    }

    // Router function for SSE transport used by Spring WebFlux to start an HTTP
    // server.
    @Bean
    public RouterFunction<?> mcpRouterFunction(WebFluxSseServerTransportProvider transport) {
        return transport.getRouterFunction();
    }

    @Bean
    public McpAsyncServer mcpServer(McpServerTransportProvider transport, OpenLibrary openLibrary) {
        // Configure server capabilities with resource support
        var capabilities = McpSchema.ServerCapabilities.builder()
                .resources(false, true) // No subscribe support, but list changes notifications
                .tools(true) // Tool support with list changes notifications
                .prompts(true) // Prompt support with list changes notifications
                .logging() // Logging support
                .build();

        // Create the server with both tool and resource capabilities
        return McpServer.async(transport)
                .serverInfo("MCP Demo Server", "1.0.0")
                .capabilities(capabilities)
                .resources(systemInfoResourceRegistration())
                .prompts(greetingPromptRegistration())
                .tools(openLibraryToolRegistrations(openLibrary))
                .build();
    }
}
```
