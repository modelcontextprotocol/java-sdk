# MCP Java SDK Migration Guide: 0.18.1 → 1.0.0

This document covers the breaking changes between **0.18.1** and **1.0.0** of the MCP Java SDK. All items listed here were already deprecated (with `@Deprecated` or `@Deprecated(forRemoval = true)`) in 0.18.1 and are now removed.

> **If you are on a version earlier than 0.18.1**, upgrade progressively to **0.18.1** first. That release already provides the replacement APIs described below alongside the deprecated ones, so you can resolve all deprecation warnings before moving to 1.0.0. Many types and APIs that existed in older 0.x versions (e.g., `ClientMcpTransport`, `ServerMcpTransport`, `DefaultMcpSession`, `StdioServerTransport`, `HttpServletSseServerTransport`, `FlowSseClient`) were already removed well before 0.18.1 and are not covered here.

---

## 1. The `mcp` aggregator module now defaults to Jackson 3

The module structure (`mcp-core`, `mcp-json-jackson2`, `mcp-json-jackson3`, `mcp`) is unchanged. What changes is the default JSON binding in the `mcp` convenience artifact:

| Version | `mcp` artifact includes |
|---|---|
| 0.18.1 | `mcp-core` + `mcp-json-jackson2` |
| 1.0.0 | `mcp-core` + `mcp-json-jackson3` |

If your project uses **Jackson 2** (the `com.fasterxml.jackson` 2.x line), stop depending on the `mcp` aggregator and depend on the individual modules instead:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-core</artifactId>
    <version>1.0.0-RC3</version>
</dependency>
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-json-jackson2</artifactId>
    <version>1.0.0-RC3</version>
</dependency>
```

If you are ready to adopt **Jackson 3**, you can simply continue using the `mcp` aggregator:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
    <version>1.0.0-RC3</version>
</dependency>
```

### Deprecated `io.modelcontextprotocol.json.jackson` package removed

In `mcp-json-jackson2`, the classes under the old `io.modelcontextprotocol.json.jackson` package (deprecated in 0.18.1) have been removed. Use the equivalent classes under `io.modelcontextprotocol.json.jackson2`:

| Removed (old package) | Replacement (already available in 0.18.1) |
|---|---|
| `io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper` | `io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper` |
| `io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier` | `io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier` |
| `io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator` | `io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator` |
| `io.modelcontextprotocol.json.schema.jackson.JacksonJsonSchemaValidatorSupplier` | `io.modelcontextprotocol.json.schema.jackson2.JacksonJsonSchemaValidatorSupplier` |

---

## 2. Spring transport modules (`mcp-spring-webflux`, `mcp-spring-webmvc`)

These modules have been moved to the **Spring AI** project starting with Spring AI 2.0. The artifact names remain the same but the **Maven group has changed**:

| 0.18.1 (MCP Java SDK) | 1.0.0+ (Spring AI 2.0) |
|---|---|
| `io.modelcontextprotocol.sdk:mcp-spring-webflux` | `org.springframework.ai:mcp-spring-webflux` |
| `io.modelcontextprotocol.sdk:mcp-spring-webmvc` | `org.springframework.ai:mcp-spring-webmvc` |

Update your dependency coordinates:

```xml
<!-- Before (0.18.1) -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-webflux</artifactId>
    <version>0.18.1</version>
</dependency>

<!-- After (Spring AI 2.0) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>mcp-spring-webflux</artifactId>
    <version>${spring-ai.version}</version>
</dependency>
```

The Java package names and class names within these artifacts are unchanged — no source code modifications are needed beyond updating the dependency coordinates.

---

## 3. Tool handler signature — `tool()` removed, use `toolCall()`

The `tool()` method on the `McpServer` builder (both sync and async variants) has been removed. It was deprecated in 0.18.1 in favor of `toolCall()`, which accepts a handler that receives the full `CallToolRequest` instead of a raw `Map<String, Object>`.

#### Before (deprecated, removed in 1.0.0):

```java
McpServer.sync(transportProvider)
    .tool(
        myTool,
        (exchange, args) -> new CallToolResult(List.of(new TextContent("Result: " + calculate(args))), false)
    )
    .build();
```

#### After (already available in 0.18.1):

```java
McpServer.sync(transportProvider)
    .toolCall(
        myTool,
        (exchange, request) -> CallToolResult.builder()
            .content(List.of(new TextContent("Result: " + calculate(request.arguments()))))
            .isError(false)
            .build()
    )
    .build();
```

---

## 4. `AsyncToolSpecification` / `SyncToolSpecification` — `call` field removed

The deprecated `call` record component (which accepted `Map<String, Object>`) has been removed from both `AsyncToolSpecification` and `SyncToolSpecification`. Only `callHandler` (which accepts `CallToolRequest`) remains.

The deprecated constructors that accepted a `call` function have also been removed. Use the builder:

```java
McpServerFeatures.AsyncToolSpecification.builder()
    .tool(tool)
    .callHandler((exchange, request) -> Mono.just(
        CallToolResult.builder()
            .content(List.of(new TextContent("Done")))
            .build()))
    .build();
```

---

## 5. Content types — deprecated `audience`/`priority` constructors and accessors removed

`TextContent`, `ImageContent`, and `EmbeddedResource` previously had constructors and accessors that took inline `List<Role> audience` and `Double priority` parameters. These were deprecated in favor of the `Annotations` record. The deprecated forms are now removed.

#### Before (deprecated, removed in 1.0.0):

```java
new TextContent(List.of(Role.USER), 0.8, "Hello world")
textContent.audience()   // deprecated accessor
textContent.priority()   // deprecated accessor
```

#### After (already available in 0.18.1):

```java
new TextContent(new Annotations(List.of(Role.USER), 0.8), "Hello world")
textContent.annotations().audience()
textContent.annotations().priority()
```

The simple `new TextContent("text")` constructor continues to work.

---

## 6. `CallToolResult` and `Resource` — deprecated constructors removed

The constructors on `CallToolResult` and `Resource` that were deprecated in 0.18.1 have been removed. Use the builders instead.

#### `CallToolResult`

```java
// Removed:
new CallToolResult(List.of(new TextContent("result")), false);
new CallToolResult("result text", false);
new CallToolResult(content, isError, structuredContent);

// Use instead:
CallToolResult.builder()
    .content(List.of(new TextContent("result")))
    .isError(false)
    .build();
```

#### `Resource`

```java
// Removed:
new Resource(uri, name, description, mimeType, annotations);
new Resource(uri, name, title, description, mimeType, size, annotations);

// Use instead:
Resource.builder()
    .uri(uri)
    .name(name)
    .title(title)
    .description(description)
    .mimeType(mimeType)
    .size(size)
    .annotations(annotations)
    .build();
```

---

## 7. `McpError(Object)` constructor removed

The deprecated `McpError(Object error)` constructor, which was commonly used as `new McpError("message string")`, has been removed. Construct `McpError` instances using the builder with a JSON-RPC error code:

```java
// Removed:
throw new McpError("Something went wrong");

// Use instead:
throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
    .message("Something went wrong")
    .build();
```

Additionally, several places in the SDK that previously threw `McpError` for validation or state-checking purposes now throw standard Java exceptions (`IllegalStateException`, `IllegalArgumentException`). If you were catching `McpError` in those scenarios, update your catch blocks accordingly.

---

## 8. `McpSchema.LATEST_PROTOCOL_VERSION` constant removed

The deprecated `McpSchema.LATEST_PROTOCOL_VERSION` constant has been removed. Use the `ProtocolVersions` interface directly:

```java
// Removed:
McpSchema.LATEST_PROTOCOL_VERSION

// Use instead:
ProtocolVersions.MCP_2025_11_25
```

---

## 9. Deprecated session constructors and inner interfaces removed

The following deprecated constructors and inner interfaces, all of which already had replacements available in 0.18.1, have been removed:

### `McpServerSession`

| Removed | Replacement (available since 0.18.1) |
|---|---|
| Constructor with `InitNotificationHandler` parameter | Constructor without `InitNotificationHandler` — use `McpInitRequestHandler` in the map |
| `McpServerSession.InitRequestHandler` (inner interface) | `McpInitRequestHandler` (top-level interface) |
| `McpServerSession.RequestHandler<T>` (inner interface) | `McpRequestHandler<T>` (top-level interface) |
| `McpServerSession.NotificationHandler` (inner interface) | `McpNotificationHandler` (top-level interface) |

### `McpClientSession`

| Removed | Replacement (available since 0.18.1) |
|---|---|
| Constructor without `connectHook` parameter | Constructor that accepts a `Function<? super Mono<Void>, ? extends Publisher<Void>> connectHook` |

### `McpAsyncServerExchange`

| Removed | Replacement (available since 0.18.1) |
|---|---|
| Constructor `McpAsyncServerExchange(McpSession, ClientCapabilities, Implementation)` | Constructor `McpAsyncServerExchange(String, McpLoggableSession, ClientCapabilities, Implementation, McpTransportContext)` |

---

## 10. `McpAsyncServer.loggingNotification()` / `McpSyncServer.loggingNotification()` removed

The `loggingNotification(LoggingMessageNotification)` methods on `McpAsyncServer` and `McpSyncServer` were deprecated because they incorrectly broadcast to all connected clients. They have been removed. Use the per-session exchange method instead:

```java
// Removed:
server.loggingNotification(notification);

// Use instead (inside a handler with access to the exchange):
exchange.loggingNotification(notification);
```

---

## 11. `HttpClientSseClientTransport.Builder` — deprecated constructor removed

The deprecated `new HttpClientSseClientTransport.Builder(String baseUri)` constructor has been removed. Use the static factory method:

```java
// Removed:
new HttpClientSseClientTransport.Builder("http://localhost:8080")

// Use instead:
HttpClientSseClientTransport.builder("http://localhost:8080")
```

---

## Summary checklist

Before upgrading to 1.0.0, verify that your 0.18.1 build has **zero deprecation warnings** related to the MCP SDK. Every removal in 1.0.0 was preceded by a deprecation in 0.18.1 with a pointer to the replacement. Once you are clean on 0.18.1:

1. Update your dependency versions — either bump the `mcp-bom` version, or bump the specific module dependencies you use (e.g., `mcp-core`, `mcp-json-jackson2`). If you were relying on the `mcp` aggregator, note it now pulls in Jackson 3 — switch to `mcp-core` + `mcp-json-jackson2` if you need to stay on Jackson 2.
2. Replace `io.modelcontextprotocol.sdk:mcp-spring-webflux` / `mcp-spring-webmvc` with `org.springframework.ai:mcp-spring-webflux` / `mcp-spring-webmvc`.
3. If you use the `mcp-json-jackson2` module, update imports from `io.modelcontextprotocol.json.jackson` to `io.modelcontextprotocol.json.jackson2` (and similarly for the schema validator package).
4. Compile and verify — no further source changes should be needed.

---

## Need help?

If you run into issues during migration or have questions, please open an issue or start a discussion in the [MCP Java SDK GitHub repository](https://github.com/modelcontextprotocol/java-sdk).
