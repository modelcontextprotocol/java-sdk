# Jackson Forward-Compat Refactor — Execution Plan

This document is the executable plan for refactoring JSON-RPC and domain-type serialization in the MCP Java SDK so that:

- Domain records evolve in a backwards/forwards compatible way.
- Sealed interfaces are removed (hard break in this release).
- Polymorphic types deserialize correctly without hand-rolled `Map` parsing where possible.
- JSON flows through the pipeline with the minimum number of passes.

Execute the stages in order. Each stage should compile and pass the existing test suite.

---

## Decision log

### Why `params`/`result` stay as `Object`

An earlier draft of this plan changed `JSONRPCRequest.params`, `JSONRPCNotification.params`, and `JSONRPCResponse.result` from `Object` to `@JsonRawValue String`, with per-module `RawJsonDeserializer` mixins that used `JsonGenerator.copyCurrentStructure` to capture the raw JSON substring during envelope deserialization.

**This was reverted.** The reason: the `RawJsonDeserializer` re-serializes the intermediate parsed tree (Map/List) back into a String, then the handler later calls `readValue(params, TargetType)` to deserialize a third time. That is three passes for what should be two. The mixin approach does not skip the intermediate Map — it just adds an extra serialization step on top.

The real cost of the existing `Object params` path is:

1. `readValue(jsonText, MAP_TYPE_REF)` → `HashMap` (full JSON parse)
2. `convertValue(map, JSONRPCRequest.class)` → envelope record (in-memory structural walk, `params` is a `LinkedHashMap`)
3. `convertValue(params, TargetType.class)` in handler → typed POJO (in-memory structural walk)

Step 2 is eliminated by the `@JsonTypeInfo(DEDUCTION)` annotation added to `JSONRPCMessage` (see Stage 1), which collapses steps 1+2 into a single `readValue`. Step 3 (`convertValue`) is an in-memory walk, not a JSON parse — it is acceptable.

### Why `@JsonTypeInfo` on `CompleteReference` is annotated but not yet functional

`@JsonTypeInfo(DEDUCTION)` + `@JsonSubTypes` has been added to `CompleteReference`. However, during test development it was confirmed that Jackson (both version 2 and 3) does **not** discover these annotations when deserializing `CompleteRequest.ref` (a field typed as the abstract `CompleteReference` interface) from a `Map` produced by `convertValue`. The annotation is present in bytecode but is not picked up by the deserializer introspector in either Jackson version for this specific pattern (static nested interface of a final class, target of a `convertValue` from Map).

The practical consequence is that `convertValue(paramsMap, CompleteRequest.class)` still fails on the `ref` field. The old `parseCompletionParams` hand-rolled Map parser has been replaced with `jsonMapper.convertValue(params, new TypeRef<CompleteRequest>() {})` — this works as long as the `ref` object in the `params` Map is deserialized correctly. **This needs investigation and a fix** (see Open issues below).

---

## Current state (as of last execution)

### Done — all existing tests pass (274 in `mcp-core`, 30 in each Jackson module)

**`McpSchema.java`**
- `JSONRPCMessage`: `sealed` removed; `@JsonTypeInfo(DEDUCTION)` + `@JsonSubTypes` added.
- `JSONRPCRequest`, `JSONRPCNotification`, `JSONRPCResponse`: stale `// @JsonFormat` and `// TODO: batching support` comments removed. `params`/`result` remain `Object`.
- `deserializeJsonRpcMessage`: still uses the two-step Map approach for compatibility with non-Jackson mappers (e.g. the Gson-based mapper tested in `GsonMcpJsonMapperTests`). The `@JsonTypeInfo` annotation on `JSONRPCMessage` enables direct `mapper.readValue(json, JSONRPCMessage.class)` for callers who use a Jackson mapper directly.
- `Request`, `Result`, `Notification`: `sealed`/`permits` removed — plain interfaces.
- `ResourceContents`: `sealed`/`permits` removed; existing `@JsonTypeInfo(DEDUCTION)` retained.
- `CompleteReference`: `sealed`/`permits` removed; `@JsonTypeInfo(DEDUCTION)` + `@JsonSubTypes` added. **Annotation not yet functional for `convertValue` path — see Open issues.**
- `Content`: `sealed`/`permits` removed; `@JsonIgnore` added to default `type()` method to prevent double emission of the `type` property.
- `LoggingLevel`: `@JsonCreator` + `static final Map BY_NAME` added (lenient deserialization, `null` for unknown values).
- `StopReason`: `Arrays.stream` lookup replaced with `static final Map BY_VALUE`.
- `Prompt`: constructors no longer coerce `null` arguments to `new ArrayList<>()`. `Prompt.withDefaults(...)` factory added for callers that want the empty-list behaviour.
- `CompleteCompletion`: `@JsonInclude` changed from `ALWAYS` to `NON_ABSENT`; `@JsonIgnoreProperties(ignoreUnknown = true)` added; non-null `values` validated in canonical constructor.
- Annotation sweep: all `public record` types inside `McpSchema` now have both `@JsonInclude(NON_ABSENT)` and `@JsonIgnoreProperties(ignoreUnknown = true)`. Records that were missing either annotation: `Sampling`, `Elicitation`, `Form`, `Url`, `CompletionCapabilities`, `LoggingCapabilities`, `PromptCapabilities`, `ResourceCapabilities`, `ToolCapabilities`, `CompleteArgument`, `CompleteContext`.
- `JsonIgnore` import added.

**`McpAsyncServer.java`**
- `parseCompletionParams` deleted.
- Completion handler uses `jsonMapper.convertValue(params, new TypeRef<>() {})`.

**`McpStatelessAsyncServer.java`**
- `parseCompletionParams` deleted.
- Completion handler uses `jsonMapper.convertValue(params, new TypeRef<>() {})`.

**`ServerParameters.java`**
- `@JsonInclude` and `@JsonProperty` annotations removed; javadoc states it is not a wire type.

### New tests (in `mcp-test`) — all passing ✅

Four new test classes written to `mcp-test/src/test/java/io/modelcontextprotocol/spec/`:

| Class | Status |
|---|---|
| `JsonRpcDispatchTests` | **All 5 pass** |
| `ContentJsonTests` | **All 5 pass** |
| `SchemaEvolutionTests` | **All 12 pass** |
| `CompleteReferenceJsonTests` | **All 6 pass** |

---

## Resolved issues

### 1. `CompleteReference` polymorphic dispatch

**Fix:** Changed `@JsonTypeInfo` on `CompleteReference` from `DEDUCTION` to `NAME + EXISTING_PROPERTY + visible=true`. DEDUCTION failed because `PromptReference` and `ResourceReference` share the `type` field, making their field fingerprints non-disjoint. `EXISTING_PROPERTY` uses the `"type"` field value as the explicit discriminator, working correctly with both `readValue` and `convertValue`.

### 2. `CompleteCompletion` null field omission

**Fix:** Changed `@JsonInclude` on `CompleteCompletion` from `NON_ABSENT` to `NON_NULL`. `NON_ABSENT` does not reliably suppress plain-null `Integer`/`Boolean` record components in Jackson 2.20.

### 3. `Prompt` null arguments omission

**Fix:** Changed `@JsonInclude` on `Prompt` from `NON_ABSENT` to `NON_NULL`. The root cause was the same as issue 2, compounded by the stale jar in `~/.m2` masking the constructor fix. Both issues resolved together.

### 4. `JSONRPCMessage` DEDUCTION removed

**Fix:** Removed `@JsonTypeInfo(DEDUCTION)` and `@JsonSubTypes` from `JSONRPCMessage`. JSON-RPC message types cannot be distinguished by unique field presence alone (Request and Notification both have `method`+`params`; Request and Response both have `id`). The `deserializeJsonRpcMessage` method continues to handle dispatch correctly via the Map-based approach.

---

## Completed stages

All planned work is done. See `CONTRIBUTING.md` (§ "Evolving wire-serialized records") and `MIGRATION-2.0.md` for the contributor recipe and migration notes.
