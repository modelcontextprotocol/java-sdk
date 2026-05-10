# Migration Guide — 2.0

This document covers breaking and behavioural changes introduced in the 2.0 release of the MCP Java SDK.

---

## Jackson / JSON serialization changes

### Sealed interfaces removed

The following interfaces were `sealed` in 1.x and are now plain interfaces in 2.0:

- `McpSchema.JSONRPCMessage`
- `McpSchema.Request`
- `McpSchema.Result`
- `McpSchema.Notification`
- `McpSchema.ResourceContents`
- `McpSchema.CompleteReference`
- `McpSchema.Content`

**Impact:** Exhaustive `switch` expressions or `switch` statements that relied on the sealed hierarchy for completeness checking must add a `default` branch. The compiler will no longer reject switches that omit one of the known subtypes.

### `CompleteReference` now carries `@JsonTypeInfo`

`CompleteReference` (and its implementations `PromptReference` and `ResourceReference`) is now annotated with `@JsonTypeInfo(use = NAME, include = EXISTING_PROPERTY, property = "type", visible = true)`. Jackson will automatically dispatch to the correct subtype based on the `"type"` field in the JSON without any hand-written map-walking code.

**Action:** Remove any custom code that manually inspected the `"type"` field of a completion reference map and instantiated `PromptReference` / `ResourceReference` by hand. A plain `mapper.readValue(json, CompleteRequest.class)` or `mapper.convertValue(paramsMap, CompleteRequest.class)` is sufficient.

### `Prompt` canonical constructor no longer coerces `null` arguments

In 1.x, `new Prompt(name, description, null)` silently stored an empty list for `arguments`. In 2.0 it stores `null`.

**Action:**

- Code that expected `prompt.arguments()` to return an empty list when not provided will now receive `null`. Add a null-check or use the new `Prompt.withDefaults(name, description, arguments)` factory, which preserves the old behaviour by coercing `null` to `[]`.
- On the wire, a prompt without an `arguments` field deserializes with `arguments == null` (it is not coerced to an empty list).

### `CompleteCompletion` optional fields omitted when null

`CompleteResult.CompleteCompletion.total` and `CompleteCompletion.hasMore` are now omitted from serialized JSON when they are `null` (previously they were always emitted). Deserializers that required these fields to be present in every response must be updated to treat their absence as `null`.

### `CompleteCompletion.values` is mandatory in the Java API

The compact constructor for `CompleteCompletion` asserts that `values` is not `null`. Code that constructed a completion result with a null `values` list will now fail at runtime.

**Action:** Always pass a non-null list (for example `List.of()` when there are no suggestions).

### `LoggingLevel` deserialization is lenient

`LoggingLevel` now uses a `@JsonCreator` factory (`fromValue`) so that JSON string values deserialize in a case-insensitive way. **Unrecognized level strings deserialize to `null`** instead of causing deserialization to fail.

**Impact:** `SetLevelRequest`, `LoggingMessageNotification`, and any other type that embeds `LoggingLevel` can observe a `null` level when the wire value is unknown or misspelled. Downstream code must null-check or validate before use.

### `Content.type()` is ignored for Jackson serialization

The `Content` interface still exposes `type()` as a convenience for Java callers, but the method is annotated with `@JsonIgnore` so Jackson does not treat it as a duplicate `"type"` property alongside `@JsonTypeInfo` on the interface.

**Impact:** Custom serializers or `ObjectMapper` configuration that relied on serializing `Content` through the default `type()` accessor alone should use the concrete content records (each of which carries a real `"type"` property) or the polymorphic setup on `Content`.

### `ServerParameters` no longer carries Jackson annotations

`ServerParameters` (in `client/transport`) has had its `@JsonProperty` and `@JsonInclude` annotations removed. It was never a wire type and is not serialized to JSON in normal SDK usage. If your code serialized or deserialized `ServerParameters` using Jackson, switch to a plain map or a dedicated DTO.

### Record annotation sweep

Wire-oriented `public record` types in `McpSchema` consistently use `@JsonInclude(JsonInclude.Include.NON_ABSENT)` (or equivalent per-type configuration) and `@JsonIgnoreProperties(ignoreUnknown = true)`. Nested capability objects under `ClientCapabilities` / `ServerCapabilities` (for example `Sampling`, `Elicitation`, `CompletionCapabilities`, `LoggingCapabilities`, prompt/resource/tool capability records) also ignore unknown JSON properties. This means:

- **Unknown fields** in incoming JSON are silently ignored, improving forward compatibility with newer server or client versions.
- **Absent optional properties** are omitted from outgoing JSON where `NON_ABSENT` applies, and optional Java components deserialize as `null` when missing on the wire.

### `Tool.inputSchema` is `Map<String, Object>`, not `JsonSchema`

The `Tool` record now models `inputSchema` (and `outputSchema`) as arbitrary JSON Schema objects as `Map<String, Object>`, so dialect-specific keywords (`$ref`, `unevaluatedProperties`, vendor extensions, and so on) round-trip without being trimmed by a narrow `JsonSchema` record.

**Impact:**

- Java code that used `Tool.inputSchema()` as a `JsonSchema` must switch to `Map<String, Object>` (or copy into your own schema wrapper).
- `Tool.Builder.inputSchema(JsonSchema)` remains as a **deprecated** helper that maps the old record into a map; prefer `inputSchema(Map)` or `inputSchema(McpJsonMapper, String)`.

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

**Wire deserialization is lenient.** Records expose a `@JsonCreator fromJson` factory that substitutes safe defaults (e.g. `[]`, `""`, `0`, `INFO`, `Action.CANCEL`) for any absent required field and logs a `WARN` naming the field and the substituted value. `JSONRPCResponse.JSONRPCError` is excluded — malformed JSON-RPC error envelopes still fail immediately.

**Note:** `LoggingMessageNotification`/`SetLevelRequest` default a *missing* `level` to `INFO`, but an *unrecognized* level string still deserializes to `null` (see the `LoggingLevel` section above) and will then fail the canonical constructor. Ensure clients and servers send only recognized level strings.

### `PromptReference` discriminator pinning and equality

`PromptReference` keeps its `(type, name, title)` record components, so positional construction from 1.x still compiles. Two behavioural changes:

- The compact constructor pins `type` to `ref/prompt`. Any non-null value other than `ref/prompt` is replaced with `ref/prompt` and a `WARN` is logged. The legacy two-arg `PromptReference(String type, String name)` constructor remains `@Deprecated` and routes through the canonical constructor, so it triggers the same WARN.
- `equals`/`hashCode` now consider `name` only (title and type are ignored). Two refs with the same name but different titles compare equal.

**Action:** Audit any code that used `PromptReference` as a map key or in a `Set` — equality semantics changed. If your code constructed instances with a custom `type` string for testing, switch to `PromptReference.builder(name)` (or `new PromptReference(name)`); the WARN tells you which call sites still pass the discriminator.

`CompleteReference.identifier()` is `@Deprecated` and now returns `null` via a default method on the interface.

### `ResourceReference` record component reduced

Components changed from `(type, uri)` to `(uri)`. Positional construction with two arguments breaks. The legacy `ResourceReference(String type, String uri)` constructor stays `@Deprecated`; it ignores `type` and logs a `WARN`. Use `new ResourceReference(uri)` or `ResourceReference.builder(uri)`. The `type()` accessor still returns `ref/resource` and Jackson serializes it via `@JsonProperty("type")` on the accessor.

### Builder API: required-first factories; old setters/no-arg builders deprecated

Most records that have a builder have gained a required-first factory method (`builder(req1, req2, …)`) and the corresponding setters for required fields are removed from the builder. The old no-arg `builder()` factory and public no-arg `Builder()` constructor are kept but `@Deprecated` where they would allow constructing a builder without required state.

Examples:

| Type | Old (deprecated) | New |
|------|-----------------|-----|
| `Resource` | `Resource.builder().uri(u).name(n)…` | `Resource.builder(uri, name)…` |
| `ResourceTemplate` | `ResourceTemplate.builder().uriTemplate(u).name(n)…` | `ResourceTemplate.builder(uriTemplate, name)…` |
| `Implementation` | `new Implementation(name, version)` | `Implementation.builder(name, version)…` |
| `InitializeRequest` / `InitializeResult` | `… .builder()…` | `… .builder(protocolVersion, capabilities, clientInfo/serverInfo)` |
| `Tool` | `Tool.builder().name(n)…` | `Tool.builder(name)…` |
| `Prompt` / `PromptArgument` / `GetPromptRequest` | `… .builder().name(n)…` | `… .builder(name)…` |
| `PromptMessage` / `SamplingMessage` | `… .builder().role(r).content(c)…` | `… .builder(role, content)…` |
| `CreateMessageRequest` | `… .builder().messages(m).maxTokens(n)…` | `… .builder(messages, maxTokens)…` |
| `ElicitRequest` | `… .builder().message(m).requestedSchema(s)…` | `… .builder(message, requestedSchema)…` |
| `LoggingMessageNotification` | `… .builder().level(l).data(d)…` | `… .builder(level, data)…` |
| `ListResourcesResult` / `ListResourceTemplatesResult` / `ListPromptsResult` / `ListToolsResult` / `ListRootsResult` | `… .builder()…` | `… .builder(items)…` |
| `ReadResourceRequest` / `SubscribeRequest` / `UnsubscribeRequest` / `ResourcesUpdatedNotification` / `Root` | n/a | `… .builder(uri)…` |
| `ReadResourceResult` | n/a | `ReadResourceResult.builder(contents)…` |
| `TextResourceContents` / `BlobResourceContents` | n/a | `… .builder(uri, text|blob)…` |
| `TextContent` / `ImageContent` / `AudioContent` / `EmbeddedResource` | n/a | `… .builder(text \| data, mimeType \| resource)…` |
| `CallToolResult` | unchanged | also: required-first content set via builder constructor remains optional |
| `ProgressNotification` | n/a | `ProgressNotification.builder(progressToken, progress)` |
| `JSONRPCResponse.JSONRPCError` | n/a | `JSONRPCError.builder(code, message)` |
| `CompleteRequest` | n/a | `CompleteRequest.builder(ref, argument)` |
| `Annotations` | n/a | `Annotations.builder()` |
| Capabilities (`Sampling`, `Elicitation`, `Roots`, `LoggingCapabilities`, `CompletionCapabilities`, prompt/resource/tool capabilities) | n/a | `… .builder()…` |

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

`JSONRPCResponse`'s compact constructor additionally enforces the JSON-RPC invariant that exactly one of `result` / `error` is set — previously the SDK could build envelopes that violated the protocol.

The 1.x canonical 4-arg constructors continue to compile.

### Optional JSON Schema validation on `tools/call` (server)

When a `JsonSchemaValidator` is available (including the default from `McpJsonDefaults.getSchemaValidator()` when you do not configure one explicitly) and `validateToolInputs` is left at its default of `true`, the server validates incoming tool arguments against `tool.inputSchema()` before invoking the tool. Failed validation produces a `CallToolResult` with `isError` set and a textual error in the content.

**Action:** Ensure `inputSchema` maps are valid for your validator, tighten client arguments, or disable validation with `validateToolInputs(false)` on the server builder if you must preserve pre-2.0 behaviour.
