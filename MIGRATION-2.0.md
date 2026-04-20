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

### Optional JSON Schema validation on `tools/call` (server)

When a `JsonSchemaValidator` is available (including the default from `McpJsonDefaults.getSchemaValidator()` when you do not configure one explicitly) and `validateToolInputs` is left at its default of `true`, the server validates incoming tool arguments against `tool.inputSchema()` before invoking the tool. Failed validation produces a `CallToolResult` with `isError` set and a textual error in the content.

**Action:** Ensure `inputSchema` maps are valid for your validator, tighten client arguments, or disable validation with `validateToolInputs(false)` on the server builder if you must preserve pre-2.0 behaviour.
