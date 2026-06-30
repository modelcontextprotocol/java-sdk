# Migration Guide — 2.0

This guide covers the breaking and behavioural changes introduced in the 2.0 release of the MCP Java SDK, relative to 1.x, and how to update existing code.

The changes fall into these areas:

- [Schema construction and required fields](#schema-construction-and-required-fields) — non-null enforcement and the builder API.
- [Schema type and shape changes](#schema-type-and-shape-changes) — record component and type changes in `McpSchema`.
- [JSON serialization behaviour](#json-serialization-behaviour) — wire-format changes.
- [Server-side validation](#server-side-validation) — runtime validation of tool arguments and embedded schemas.
- [Transport changes](#transport-changes) — removed methods and the SSE deprecation.
- [Server API changes](#server-api-changes) — sync server method signature corrections.
- [New features](#new-features) — additive, backward-compatible capabilities.

---

## Schema construction and required fields

### Required MCP spec fields are enforced at construction time

Every wire record in `McpSchema` whose fields are marked required by the MCP spec now asserts non-null (and non-empty for `String` identifiers like `name`, `uri`, `uriTemplate`, `version`) in its compact constructor. Passing `null` throws `IllegalArgumentException` immediately, instead of producing a structurally invalid object that fails later in serialization or protocol handling.

This applies to (non-exhaustive):

- JSON-RPC envelopes: `JSONRPCRequest`, `JSONRPCNotification`, `JSONRPCResponse`, `JSONRPCResponse.JSONRPCError`
- Lifecycle: `InitializeRequest`, `InitializeResult`, `Implementation`
- Resources: `Resource`, `ResourceTemplate`, `ListResourcesResult`, `ListResourceTemplatesResult`, `ReadResourceRequest`, `ReadResourceResult`, `SubscribeRequest`, `UnsubscribeRequest`, `ResourcesUpdatedNotification`, `TextResourceContents`, `BlobResourceContents`
- Prompts: `Prompt`, `PromptArgument`, `PromptMessage`, `ListPromptsResult`, `GetPromptRequest`, `GetPromptResult`
- Tools: `Tool`, `ListToolsResult`, `CallToolRequest`, `CallToolResult`
- Sampling / elicitation: `SamplingMessage`, `CreateMessageRequest`, `CreateMessageResult`, `ElicitRequest`, `ElicitResult`
- Misc: `ProgressNotification`, `SetLevelRequest`, `LoggingMessageNotification`, `CompleteRequest`, `CompleteResult`, `CompleteRequest.CompleteArgument`, content records (`TextContent`, `ImageContent`, `AudioContent`, `EmbeddedResource`), `Root`, `ListRootsResult`, `PromptReference`, `ResourceReference`

**Action:** Audit any code that constructs these records with potentially-null values and provide valid, non-null arguments.

**Wire deserialization stays lenient.** Records expose a `@JsonCreator fromJson` factory that substitutes safe defaults (e.g. `[]`, `""`, `0`, `INFO`, `Action.CANCEL`) for any absent required field and logs a `WARN` naming the field and the substituted value. `JSONRPCResponse.JSONRPCError` is excluded — malformed JSON-RPC error envelopes still fail immediately.

**Note:** `LoggingMessageNotification` / `SetLevelRequest` default a *missing* `level` to `INFO`, but an *unrecognized* level string still deserializes to `null` (see [`LoggingLevel` deserialization is lenient](#logginglevel-deserialization-is-lenient)) and will then fail the canonical constructor. Ensure clients and servers send only recognized level strings.

### `Prompt` no longer coerces `null` arguments

In 1.x, `new Prompt(name, description, null)` silently stored an empty list for `arguments`. In 2.0 it stores `null`.

**Action:**

- Code that expected `prompt.arguments()` to return an empty list when not provided will now receive `null`. Add a null-check.
- On the wire, a prompt without an `arguments` field deserializes with `arguments == null` (it is not coerced to an empty list).

### Builder API: required-first factories; old setters/no-arg builders deprecated

Most records that have a builder gained a required-first factory method (`builder(req1, req2, …)`). The old no-arg `builder()` factory, the public no-arg `Builder()` constructor, and the setters for the now-required fields are kept but `@Deprecated`. They still compile, so 1.x code keeps working with deprecation warnings; migrate to the required-first factories to clear them.

| Type | Old (deprecated) | New |
|------|-----------------|-----|
| `Resource` | `Resource.builder().uri(u).name(n)…` | `Resource.builder(uri, name)…` |
| `ResourceTemplate` | `ResourceTemplate.builder().uriTemplate(u).name(n)…` | `ResourceTemplate.builder(uriTemplate, name)…` |
| `Implementation` | `new Implementation(name, version)` | `Implementation.builder(name, version)…` |
| `InitializeRequest` / `InitializeResult` | `… .builder()…` | `… .builder(protocolVersion, capabilities, clientInfo/serverInfo)` |
| `Tool` | `Tool.builder().name(n).inputSchema(s)…` | `Tool.builder(name, inputSchemaMap)…` or `Tool.builder(name, jsonMapper, inputSchemaJson)…` |
| `Prompt` / `PromptArgument` / `GetPromptRequest` | `… .builder().name(n)…` | `… .builder(name)…` |
| `PromptMessage` / `SamplingMessage` | `… .builder().role(r).content(c)…` | `… .builder(role, content)…` |
| `CreateMessageRequest` | `… .builder().messages(m).maxTokens(n)…` | `… .builder(messages, maxTokens)…` |
| `ElicitRequest` | `… .builder().message(m).requestedSchema(s)…` | `… .builder(message, requestedSchema)…` |
| `LoggingMessageNotification` | `… .builder().level(l).data(d)…` | `… .builder(level, data)…` |
| `ListResourcesResult` / `ListResourceTemplatesResult` / `ListPromptsResult` / `ListToolsResult` / `ListRootsResult` | `… .builder()…` | `… .builder(items)…` |
| `ReadResourceRequest` / `SubscribeRequest` / `UnsubscribeRequest` / `ResourcesUpdatedNotification` / `Root` | n/a | `… .builder(uri)…` |
| `ReadResourceResult` | n/a | `ReadResourceResult.builder(contents)…` |
| `GetPromptResult` | `new GetPromptResult(description, messages)` | `GetPromptResult.builder(messages).description(d)…` |
| `TextResourceContents` / `BlobResourceContents` | n/a | `… .builder(uri, text\|blob)…` |
| `TextContent` / `ImageContent` / `AudioContent` / `EmbeddedResource` | n/a | `… .builder(text \| data, mimeType \| resource)…` |
| `ProgressNotification` | n/a | `ProgressNotification.builder(progressToken, progress)` |
| `JSONRPCResponse.JSONRPCError` | n/a | `JSONRPCError.builder(code, message)` |
| `CompleteRequest` | n/a | `CompleteRequest.builder(ref, argument)` |
| `Annotations` | n/a | `Annotations.builder()` |
| Capabilities (`Sampling`, `Elicitation`, `Roots`, `LoggingCapabilities`, `CompletionCapabilities`, prompt/resource/tool capabilities) | n/a | `… .builder()…` |

---

## Schema type and shape changes

### `Tool.inputSchema` is `Map<String, Object>`, not `JsonSchema`

The `Tool` record now models `inputSchema` (and `outputSchema`) as arbitrary JSON Schema objects of type `Map<String, Object>`, so dialect-specific keywords (`$ref`, `unevaluatedProperties`, vendor extensions, and so on) round-trip without being trimmed by a narrow `JsonSchema` record.

**Action:**

- Java code that used `Tool.inputSchema()` as a `JsonSchema` must switch to `Map<String, Object>` (or copy into your own schema wrapper).
- `Tool.Builder.inputSchema(JsonSchema)` remains as a **deprecated** helper that maps the old record into a map; prefer `inputSchema(Map)` or `inputSchema(McpJsonMapper, String)`.

### Sealed interfaces removed

The following interfaces were `sealed` in 1.x and are now plain interfaces in 2.0:

- `McpSchema.JSONRPCMessage`
- `McpSchema.Request`
- `McpSchema.Result`
- `McpSchema.Notification`
- `McpSchema.ResourceContents`
- `McpSchema.CompleteReference`
- `McpSchema.Content`

**Impact:** Exhaustive `switch` expressions or statements that relied on the sealed hierarchy for completeness checking must add a `default` branch. The compiler will no longer reject switches that omit one of the known subtypes.

### `CompleteReference` polymorphic deserialization

`CompleteReference` (and its implementations `PromptReference` and `ResourceReference`) is now annotated with `@JsonTypeInfo(use = NAME, include = EXISTING_PROPERTY, property = "type", visible = true)`. Jackson dispatches to the correct subtype based on the `"type"` field automatically.

**Action:** Remove any custom code that manually inspected the `"type"` field of a completion reference map and instantiated `PromptReference` / `ResourceReference` by hand. A plain `mapper.readValue(json, CompleteRequest.class)` or `mapper.convertValue(paramsMap, CompleteRequest.class)` is sufficient.

`CompleteReference.identifier()` is `@Deprecated` and now returns `null` via a default method on the interface.

### `PromptReference` discriminator pinning and equality

`PromptReference` keeps its `(type, name, title)` record components, so positional construction from 1.x still compiles, with two behavioural changes:

- The compact constructor pins `type` to `ref/prompt`. Any non-null value other than `ref/prompt` is replaced and a `WARN` is logged. The legacy two-arg `PromptReference(String type, String name)` constructor remains `@Deprecated` and routes through the canonical constructor, so it triggers the same WARN.
- `equals`/`hashCode` now consider `name` only (title and type are ignored). Two refs with the same name but different titles compare equal.

**Action:** Audit any code that used `PromptReference` as a map key or in a `Set` — equality semantics changed. If you constructed instances with a custom `type` string, switch to `PromptReference.builder(name)` (or `new PromptReference(name)`); the WARN identifies the call sites still passing a discriminator.

### `ResourceReference` record component reduced

Components changed from `(type, uri)` to `(uri)`. Positional construction with two arguments breaks. The legacy `ResourceReference(String type, String uri)` constructor stays `@Deprecated`; it ignores `type` and logs a `WARN`. Use `new ResourceReference(uri)` or `ResourceReference.builder(uri)`. The `type()` accessor still returns `ref/resource` and Jackson serializes it via `@JsonProperty("type")` on the accessor.

### `ElicitRequest` is now an interface

To support URL-mode elicitation (see [New features](#new-features)), the elicitation request type was split:

- `ElicitRequest` changed from a `record` to an `interface`.
- The original form-based request record is now `ElicitFormRequest`.
- The `McpClient` builder `elicitation(...)` methods now accept a handler over `ElicitFormRequest` instead of `ElicitRequest`.

**Action:** Replace references to the old `ElicitRequest` record (construction, `instanceof`, handler signatures) with `ElicitFormRequest`. Code that only referred to `ElicitRequest` as a type continues to compile against the new interface.

### `ServerParameters` no longer carries Jackson annotations

`ServerParameters` (in `client/transport`) has had its `@JsonProperty` and `@JsonInclude` annotations removed. It was never a wire type and is not serialized to JSON in normal SDK usage. If your code serialized or deserialized `ServerParameters` using Jackson, switch to a plain map or a dedicated DTO.

---

## JSON serialization behaviour

### Unknown JSON fields are ignored

Wire-oriented `public record` types in `McpSchema` consistently use `@JsonInclude(JsonInclude.Include.NON_ABSENT)` and `@JsonIgnoreProperties(ignoreUnknown = true)`. Nested capability objects under `ClientCapabilities` / `ServerCapabilities` (for example `Sampling`, `Elicitation`, `CompletionCapabilities`, `LoggingCapabilities`, and the prompt/resource/tool capability records) also ignore unknown JSON properties. As a result:

- **Unknown fields** in incoming JSON are silently ignored, improving forward compatibility with newer server or client versions.
- **Absent optional properties** are omitted from outgoing JSON where `NON_ABSENT` applies, and optional Java components deserialize as `null` when missing on the wire.

### `CompleteCompletion` field handling

- `CompleteResult.CompleteCompletion.total` and `CompleteCompletion.hasMore` are now omitted from serialized JSON when `null` (previously they were always emitted). Deserializers that required these fields to be present must treat their absence as `null`.
- The compact constructor asserts that `values` is not `null`. **Action:** always pass a non-null list (for example `List.of()` when there are no suggestions).

### `LoggingLevel` deserialization is lenient

`LoggingLevel` now uses a `@JsonCreator` factory (`fromValue`) so JSON string values deserialize case-insensitively. **Unrecognized level strings deserialize to `null`** instead of failing.

**Impact:** `SetLevelRequest`, `LoggingMessageNotification`, and any other type embedding `LoggingLevel` can observe a `null` level when the wire value is unknown or misspelled. Downstream code must null-check or validate before use.

### `Content.type()` is ignored for Jackson serialization

The `Content` interface still exposes `type()` as a convenience for Java callers, but the method is annotated with `@JsonIgnore` so Jackson does not treat it as a duplicate `"type"` property alongside `@JsonTypeInfo` on the interface.

**Impact:** Custom serializers or `ObjectMapper` configuration that relied on serializing `Content` through the `type()` accessor alone should use the concrete content records (each of which carries a real `"type"` property) or the polymorphic setup on `Content`.

### JSON-RPC envelope ergonomics

In 1.x, every envelope was constructed via the canonical record constructor and the literal `"2.0"` `jsonrpc` string had to be threaded through every call site:

```java
new JSONRPCRequest("2.0", "tools/call", id, params);
new JSONRPCNotification("2.0", "notifications/initialized", null);
new JSONRPCResponse("2.0", id, result, null);
new JSONRPCResponse("2.0", id, null, new JSONRPCError(code, message, null));
```

2.0 adds defaulting constructors and static factories so the `"2.0"` constant and the unused `result`/`error` slot disappear from caller code:

```java
new JSONRPCRequest("tools/call", id);                       // params optional
new JSONRPCRequest("tools/call", id, params);
new JSONRPCNotification("notifications/initialized");        // params optional
new JSONRPCNotification("notifications/initialized", params);
JSONRPCResponse.result(id, result);
JSONRPCResponse.error(id, new JSONRPCError(code, message));  // 2-arg error
```

`JSONRPCResponse`'s compact constructor additionally enforces the JSON-RPC invariant that exactly one of `result` / `error` is set — previously the SDK could build envelopes that violated the protocol. The 1.x canonical 4-arg constructors continue to compile.

---

## Server-side validation

### Optional JSON Schema validation on `tools/call`

When a `JsonSchemaValidator` is available (including the default from `McpJsonDefaults.getSchemaValidator()` when you do not configure one explicitly) and `validateToolInputs` is left at its default of `true`, the server validates incoming tool arguments against `tool.inputSchema()` before invoking the tool. Failed validation produces a `CallToolResult` with `isError` set and a textual error in the content.

**Action:** Ensure `inputSchema` maps are valid for your validator, tighten client arguments, or disable validation with `validateToolInputs(false)` on the server builder if you must preserve pre-2.0 behaviour.

### Embedded JSON Schemas are validated against 2020-12 (SEP-1613)

The JSON Schema documents that MCP embeds — `Tool.inputSchema`, `Tool.outputSchema`, and `ElicitRequest.requestedSchema` — are now validated against the JSON Schema 2020-12 meta-schema by default. Servers reject malformed schemas at **build time** (`McpServer.build()`) and at **runtime** (`addTool()`) with an `IllegalArgumentException` that names the offending field and references SEP-1613. Elicitation requests whose `requestedSchema` violates the meta-schema are rejected before being sent to the client.

Schemas that explicitly declare a different dialect via `$schema` are accepted without meta-schema validation — 2020-12 is the default, not the only permitted dialect.

**Action:** Make embedded schemas valid 2020-12 documents, or set an explicit `$schema` to opt into a different dialect.

---

## Transport changes

### `customizeRequest()` removed from the HttpClient transport builders

The deprecated `Builder.customizeRequest(Consumer<HttpRequest.Builder>)` method on `HttpClientSseClientTransport` and `HttpClientStreamableHttpTransport` has been removed.

**Action:** Use `requestBuilder(HttpRequest.Builder)` for static request setup, or `httpRequestCustomizer(McpSyncHttpClientRequestCustomizer)` for per-request customization.

### `protocolVersions()` default now advertises all known versions

The default implementation of `protocolVersions()` on `McpTransport` and `McpServerTransportProviderBase` previously returned only `["2024-11-05"]`. It now returns all four versions the SDK understands:

```
["2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"]
```

**Impact for transport implementors:** If your custom `McpClientTransport` or `McpServerTransportProvider` did not override `protocolVersions()`, it will now advertise all four versions during protocol negotiation instead of just `2024-11-05`. This is the intended upgrade path for most transports, but if you need to restrict your transport to a specific set of versions, override `protocolVersions()` explicitly and return the desired list.

**Impact for users of built-in transports:** No action is required. `StdioClientTransport`, `StdioServerTransportProvider`, and `HttpServletStreamableServerTransportProvider` all advertise the full version list.

### SSE transports are deprecated

The HTTP+SSE client and server transports (and their supporting validator/exception types) are deprecated in favour of Streamable HTTP — `HttpClientStreamableHttpTransport` on the client, and `HttpServletStreamableServerTransportProvider` on the server. They still work; plan a move to Streamable HTTP.

---

## Server API changes

### `McpStatelessSyncServer#closeGracefully` returns `void`

In 1.x, `McpStatelessSyncServer.closeGracefully()` accidentally leaked the reactive signature from the underlying async server and returned `Mono<Void>`. The sync API is intentionally blocking, so returning a `Mono` was an oversight — callers had to call `.block()` themselves to get any actual shutdown behaviour.

In 2.0 the return type is corrected to `void`; the blocking call is performed internally.

**Action:** Remove any `.block()` (or `.subscribe()`) call you had appended to `closeGracefully()`. The method now blocks until the server has shut down and returns normally.

---

## New features

These are additive and backward-compatible.

### URL elicitation (SEP-1036)

Servers can request out-of-band URL input from users (for example payment or API-key flows) during tool execution. Adds `ElicitUrlRequest`, `urlElicitation()` / `elicitationCompleteConsumer(s)()` builder methods on `McpClient`, `sendElicitationComplete()` on `McpAsyncServer`/`McpSyncServer`, the `ElicitationCompleteNotification` record, and the `URL_ELICITATION_REQUIRED` error code. See the [`ElicitRequest` interface change](#elicitrequest-is-now-an-interface) for the related breaking change.

### Client-side elicitation defaults (SEP-1034)

A new opt-in `McpClient` builder option `applyElicitationDefaults(boolean)` fills missing keys of an accepted `ElicitResult.content` with the `default` values declared in the request's `requestedSchema` before returning the result to the server. It is a local client config, not a wire capability.

### Icons and metadata (SEP-973)

A new `Icon` record and an optional `icons` field were added to `Implementation`, `Resource`, `ResourceTemplate`, `Prompt`, and `Tool`. `Implementation` also gains optional `description` and `websiteUrl` fields. All fields are optional; existing constructors and builders are unchanged.

### `_meta` on paginated list queries

The client list operations accept an optional `_meta` map alongside the pagination cursor: `listResources(String cursor, Map<String, Object> meta)`, `listResourceTemplates(...)`, `listPrompts(...)`, and `listTools(...)`.
