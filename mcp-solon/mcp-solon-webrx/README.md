# Solon WebRx SSE Server Transport

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-solon-webrx</artifactId>
</dependency>
```


```java
String MESSAGE_ENDPOINT = "/mcp/message";

@Configuration
static class MyConfig {

    @Bean
    public WebRxSseServerTransportProvider webMvcSseServerTransport() {
        return WebRxSseServerTransportProvider.builder()
                .objectMapper(new ObjectMapper())
                .messageEndpoint(MESSAGE_ENDPOINT)
                .build();
    }

    @Bean
    public void routerFunction(WebRxSseServerTransportProvider transport, AppContext context) {
        transport.toHttpHandler(context.app());
    }
}
```
