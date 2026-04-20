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

### `CompleteCompletion` optional fields omitted when null

`CompleteResult.CompleteCompletion.total` and `CompleteCompletion.hasMore` are now omitted from serialized JSON when they are `null` (previously they were always emitted). Deserializers that required these fields to be present in every response must be updated to treat their absence as `null`.

### `ServerParameters` no longer carries Jackson annotations

`ServerParameters` (in `client/transport`) has had its `@JsonProperty` and `@JsonInclude` annotations removed. It was never a wire type and is not serialized to JSON in normal SDK usage. If your code serialized or deserialized `ServerParameters` using Jackson, switch to a plain map or a dedicated DTO.

### Record annotation sweep

All `public record` types inside `McpSchema` now carry `@JsonInclude(JsonInclude.Include.NON_NULL)` and `@JsonIgnoreProperties(ignoreUnknown = true)`. This means:

- **Unknown fields** in incoming JSON are silently ignored, improving forward compatibility with newer server or client versions.
- **Null-valued optional fields** are omitted from outgoing JSON, reducing payload size and improving backward compatibility with older receivers.
