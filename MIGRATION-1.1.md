# MCP Java SDK Migration Guide: 1.0 → 1.1

This document covers the breaking changes in **1.1.0** of the MCP Java SDK.

Most changes are mechanical import updates caused by the decomposition of the `McpSchema` god-class. An [OpenRewrite](https://docs.openrewrite.org/) recipe module (`mcp-migrate`) is provided to automate these changes. See [mcp-migrate/README.md](mcp-migrate/README.md) for usage instructions.

---

## Table of contents

1. [McpSchema decomposition — nested types moved to dedicated packages](#1-mcpschema-decomposition)
2. [sealed interfaces opened](#2-sealed-interfaces-opened)
3. [JSONRPC_VERSION constant relocated](#3-jsonrpc_version-constant-relocated)
4. [deserializeJsonRpcMessage method relocated](#4-deserializejsonrpcmessage-method-relocated)
5. [Automated migration with OpenRewrite](#5-automated-migration-with-openrewrite)
6. [Manual migration reference table](#6-manual-migration-reference-table)

---

## 1. McpSchema decomposition

The `McpSchema` class previously held every domain type in the MCP specification as a nested class or record. In 1.1 those types are top-level classes organised in feature-specific sub-packages.

**Before (pre-1.1):**
```java
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

Tool tool = Tool.builder().name("my-tool").description("...").build();
```

**After (1.1):**
```java
import io.modelcontextprotocol.spec.schema.tool.Tool;
import io.modelcontextprotocol.spec.schema.tool.CallToolResult;

Tool tool = Tool.builder().name("my-tool").description("...").build();
```

The new package hierarchy is:

```
io.modelcontextprotocol.spec
├── jsonrpc/          JSON-RPC 2.0 protocol types
└── schema/
    ├── resource/     Resource, template, read/subscribe requests & results
    ├── prompt/       Prompt definitions and get/list requests & results
    ├── tool/         Tool definition, call requests & results
    ├── sample/       LLM sampling messages, preferences, and create request/result
    └── elicit/       Elicitation request and result
```

### Types that remain in McpSchema

The following types were **not** moved and are still accessed as `McpSchema.*`:

| Type | Purpose |
|---|---|
| `Meta` | Base interface for `_meta` fields |
| `Request` | Marker interface for request objects |
| `Result` | Marker interface for result objects |
| `Notification` | Marker interface for notification objects |
| `ErrorCodes` | Standard JSON-RPC error code constants |
| `InitializeRequest` / `InitializeResult` | Lifecycle handshake |
| `ClientCapabilities` / `ServerCapabilities` | Capability negotiation |
| `Implementation` | Server/client implementation descriptor |
| `Role` | Enum: `USER` / `ASSISTANT` |
| `Annotations` | Optional client annotations |
| `Content` / `TextContent` / `ImageContent` / `AudioContent` | Content union type and variants |
| `EmbeddedResource` / `ResourceLink` | Embedded resource in content |
| `PaginatedRequest` / `PaginatedResult` | Cursor-based pagination |
| `ProgressNotification` / `ResourcesUpdatedNotification` / `LoggingMessageNotification` | Server-sent notifications |
| `LoggingLevel` / `SetLevelRequest` | Logging control |
| `CompleteReference` / `PromptReference` / `ResourceReference` / `CompleteRequest` / `CompleteResult` | Argument completion |
| `Root` / `ListRootsResult` | Client roots |

---

## 2. sealed interfaces opened

`Request`, `Result`, `Notification`, and `CompleteReference` were previously `sealed` with an explicit `permits` list. They are now plain interfaces.

**Before:**
```java
public sealed interface Request extends Meta
    permits InitializeRequest, CallToolRequest, CreateMessageRequest, ...  { }
```

**After:**
```java
public interface Request extends Meta { }
```

**Impact on user code:** Code that used exhaustive `switch` or `instanceof` chains relying on the sealed hierarchy will no longer receive a compile-time guarantee that all cases are covered. Review any such patterns and add a default/fallback case if needed.

---

## 3. JSONRPC_VERSION constant relocated

The version string constant `"2.0"` moved from `McpSchema` to the new `JSONRPC` utility class.

**Before:**
```java
import io.modelcontextprotocol.spec.McpSchema;

String version = McpSchema.JSONRPC_VERSION;
```

**After:**
```java
import io.modelcontextprotocol.spec.jsonrpc.JSONRPC;

String version = JSONRPC.JSONRPC_VERSION;
```

---

## 4. deserializeJsonRpcMessage method relocated

The static helper for deserializing raw JSON into a `JSONRPCMessage` moved from `McpSchema` to the `JSONRPC` utility class.

**Before:**
```java
JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(jsonMapper, rawJson);
```

**After:**
```java
import io.modelcontextprotocol.spec.jsonrpc.JSONRPC;

JSONRPCMessage msg = JSONRPC.deserializeJsonRpcMessage(jsonMapper, rawJson);
```

---

## 5. Automated migration with OpenRewrite

The `mcp-migrate` artifact ships OpenRewrite recipes that automate all of the import and type-reference changes described in this document.

**Maven:**
```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.recipeArtifactCoordinates=io.modelcontextprotocol.sdk:mcp-migrate:1.1.0 \
    -Drewrite.activeRecipes=io.modelcontextprotocol.sdk.migrations.McpSchemaMigration
```

**Gradle:**
```kotlin
rewrite {
    activeRecipe("io.modelcontextprotocol.sdk.migrations.McpSchemaMigration")
    setRecipeArtifactCoordinates("io.modelcontextprotocol.sdk:mcp-migrate:1.1.0")
}
```

See [mcp-migrate/README.md](mcp-migrate/README.md) for full setup instructions, dry-run mode, and per-domain sub-recipes.

---

## 6. Manual migration reference table

Use this table if you prefer to update imports by hand or if OpenRewrite cannot resolve a reference (e.g. inside generated sources).

### JSON-RPC types → `io.modelcontextprotocol.spec.jsonrpc`

| Old (`McpSchema.*`) | New fully-qualified name |
|---|---|
| `McpSchema.JSONRPCMessage` | `io.modelcontextprotocol.spec.jsonrpc.JSONRPCMessage` |
| `McpSchema.JSONRPCRequest` | `io.modelcontextprotocol.spec.jsonrpc.JSONRPCRequest` |
| `McpSchema.JSONRPCNotification` | `io.modelcontextprotocol.spec.jsonrpc.JSONRPCNotification` |
| `McpSchema.JSONRPCResponse` | `io.modelcontextprotocol.spec.jsonrpc.JSONRPCResponse` |
| `McpSchema.JSONRPCResponse.JSONRPCError` | `io.modelcontextprotocol.spec.jsonrpc.JSONRPCResponse.JSONRPCError` |

### Resource types → `io.modelcontextprotocol.spec.schema.resource`

| Old (`McpSchema.*`) | New fully-qualified name |
|---|---|
| `McpSchema.Annotated` | `io.modelcontextprotocol.spec.schema.resource.Annotated` |
| `McpSchema.Identifier` | `io.modelcontextprotocol.spec.schema.resource.Identifier` |
| `McpSchema.ResourceContent` | `io.modelcontextprotocol.spec.schema.resource.ResourceContent` |
| `McpSchema.ResourceContents` | `io.modelcontextprotocol.spec.schema.resource.ResourceContents` |
| `McpSchema.TextResourceContents` | `io.modelcontextprotocol.spec.schema.resource.TextResourceContents` |
| `McpSchema.BlobResourceContents` | `io.modelcontextprotocol.spec.schema.resource.BlobResourceContents` |
| `McpSchema.Resource` | `io.modelcontextprotocol.spec.schema.resource.Resource` |
| `McpSchema.ResourceTemplate` | `io.modelcontextprotocol.spec.schema.resource.ResourceTemplate` |
| `McpSchema.ListResourcesResult` | `io.modelcontextprotocol.spec.schema.resource.ListResourcesResult` |
| `McpSchema.ListResourceTemplatesResult` | `io.modelcontextprotocol.spec.schema.resource.ListResourceTemplatesResult` |
| `McpSchema.ReadResourceRequest` | `io.modelcontextprotocol.spec.schema.resource.ReadResourceRequest` |
| `McpSchema.ReadResourceResult` | `io.modelcontextprotocol.spec.schema.resource.ReadResourceResult` |
| `McpSchema.SubscribeRequest` | `io.modelcontextprotocol.spec.schema.resource.SubscribeRequest` |
| `McpSchema.UnsubscribeRequest` | `io.modelcontextprotocol.spec.schema.resource.UnsubscribeRequest` |

### Prompt types → `io.modelcontextprotocol.spec.schema.prompt`

| Old (`McpSchema.*`) | New fully-qualified name |
|---|---|
| `McpSchema.Prompt` | `io.modelcontextprotocol.spec.schema.prompt.Prompt` |
| `McpSchema.PromptArgument` | `io.modelcontextprotocol.spec.schema.prompt.PromptArgument` |
| `McpSchema.PromptMessage` | `io.modelcontextprotocol.spec.schema.prompt.PromptMessage` |
| `McpSchema.ListPromptsResult` | `io.modelcontextprotocol.spec.schema.prompt.ListPromptsResult` |
| `McpSchema.GetPromptRequest` | `io.modelcontextprotocol.spec.schema.prompt.GetPromptRequest` |
| `McpSchema.GetPromptResult` | `io.modelcontextprotocol.spec.schema.prompt.GetPromptResult` |

### Tool types → `io.modelcontextprotocol.spec.schema.tool`

| Old (`McpSchema.*`) | New fully-qualified name |
|---|---|
| `McpSchema.JsonSchema` | `io.modelcontextprotocol.spec.schema.tool.JsonSchema` |
| `McpSchema.ToolAnnotations` | `io.modelcontextprotocol.spec.schema.tool.ToolAnnotations` |
| `McpSchema.Tool` | `io.modelcontextprotocol.spec.schema.tool.Tool` |
| `McpSchema.ListToolsResult` | `io.modelcontextprotocol.spec.schema.tool.ListToolsResult` |
| `McpSchema.CallToolRequest` | `io.modelcontextprotocol.spec.schema.tool.CallToolRequest` |
| `McpSchema.CallToolResult` | `io.modelcontextprotocol.spec.schema.tool.CallToolResult` |

### Sampling types → `io.modelcontextprotocol.spec.schema.sample`

| Old (`McpSchema.*`) | New fully-qualified name |
|---|---|
| `McpSchema.SamplingMessage` | `io.modelcontextprotocol.spec.schema.sample.SamplingMessage` |
| `McpSchema.ModelPreferences` | `io.modelcontextprotocol.spec.schema.sample.ModelPreferences` |
| `McpSchema.ModelHint` | `io.modelcontextprotocol.spec.schema.sample.ModelHint` |
| `McpSchema.CreateMessageRequest` | `io.modelcontextprotocol.spec.schema.sample.CreateMessageRequest` |
| `McpSchema.CreateMessageRequest.ContextInclusionStrategy` | `io.modelcontextprotocol.spec.schema.sample.CreateMessageRequest.ContextInclusionStrategy` |
| `McpSchema.CreateMessageResult` | `io.modelcontextprotocol.spec.schema.sample.CreateMessageResult` |

### Elicitation types → `io.modelcontextprotocol.spec.schema.elicit`

| Old (`McpSchema.*`) | New fully-qualified name |
|---|---|
| `McpSchema.ElicitRequest` | `io.modelcontextprotocol.spec.schema.elicit.ElicitRequest` |
| `McpSchema.ElicitResult` | `io.modelcontextprotocol.spec.schema.elicit.ElicitResult` |

### Static members → `io.modelcontextprotocol.spec.jsonrpc.JSONRPC`

| Old | New |
|---|---|
| `McpSchema.JSONRPC_VERSION` | `JSONRPC.JSONRPC_VERSION` |
| `McpSchema.deserializeJsonRpcMessage(mapper, json)` | `JSONRPC.deserializeJsonRpcMessage(mapper, json)` |
