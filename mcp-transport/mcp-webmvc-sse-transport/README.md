# WebMVC SSE Server Transport

```xml
<dependency>
    <groupId>org.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-webmvc-sse-transport</artifactId>
</dependency>
```



```java
String MESSAGE_ENDPOINT = "/mcp/message";

@Configuration
@EnableWebMvc
static class MyConfig {

    @Bean
    public WebMvcSseServerTransport webMvcSseServerTransport() {
        return new WebMvcSseServerTransport(new ObjectMapper(), MESSAGE_ENDPOINT);
    }

    @Bean
    public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransport transport) {
        return transport.getRouterFunction();
    }
}
```
