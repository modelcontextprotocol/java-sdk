# mcp-migrate

OpenRewrite recipe module for automating the migration of user code from the **MCP Java SDK pre-1.1** API to the **1.1** release.

In 1.1 the large `McpSchema` class was decomposed: every domain type that was previously a nested class or record inside `McpSchema` is now a standalone top-level class in a feature-specific sub-package of `io.modelcontextprotocol.spec`. This module provides [OpenRewrite](https://docs.openrewrite.org/) recipes that rewrite all affected import statements and type references automatically.

For a full description of every breaking change see [MIGRATION-1.1.md](../MIGRATION-1.1.md) in the repository root.

---

## Recipes

| Recipe name | What it migrates |
|---|---|
| `io.modelcontextprotocol.sdk.migrations.McpSchemaMigration` | **Aggregate** – run this one to apply all migrations below |
| `io.modelcontextprotocol.sdk.migrations.MigrateJsonRpcTypes` | `JSONRPCMessage`, `JSONRPCRequest`, `JSONRPCNotification`, `JSONRPCResponse`, `JSONRPCError` → `spec.jsonrpc.*` |
| `io.modelcontextprotocol.sdk.migrations.MigrateResourceTypes` | `Resource`, `ResourceTemplate`, `ResourceContent`, `ResourceContents`, `TextResourceContents`, `BlobResourceContents`, `ListResourcesResult`, `ListResourceTemplatesResult`, `ReadResourceRequest`, `ReadResourceResult`, `SubscribeRequest`, `UnsubscribeRequest`, `Annotated`, `Identifier` → `spec.schema.resource.*` |
| `io.modelcontextprotocol.sdk.migrations.MigratePromptTypes` | `Prompt`, `PromptArgument`, `PromptMessage`, `ListPromptsResult`, `GetPromptRequest`, `GetPromptResult` → `spec.schema.prompt.*` |
| `io.modelcontextprotocol.sdk.migrations.MigrateToolTypes` | `Tool`, `JsonSchema`, `ToolAnnotations`, `ListToolsResult`, `CallToolRequest`, `CallToolResult` → `spec.schema.tool.*` |
| `io.modelcontextprotocol.sdk.migrations.MigrateSamplingTypes` | `SamplingMessage`, `ModelPreferences`, `ModelHint`, `CreateMessageRequest`, `CreateMessageRequest.ContextInclusionStrategy`, `CreateMessageResult` → `spec.schema.sample.*` |
| `io.modelcontextprotocol.sdk.migrations.MigrateElicitTypes` | `ElicitRequest`, `ElicitResult` → `spec.schema.elicit.*` |
| `io.modelcontextprotocol.sdk.migrations.MigrateStaticMembers` | `McpSchema.JSONRPC_VERSION` → `JSONRPC.JSONRPC_VERSION`; `McpSchema.deserializeJsonRpcMessage(…)` → `JSONRPC.deserializeJsonRpcMessage(…)` |

---

## Usage

### Prerequisites

- Java 17+
- Maven 3.6+ or Gradle 7+

### Maven

Add the OpenRewrite Maven plugin to your build (you do **not** need to add `mcp-migrate` as a regular dependency):

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.openrewrite.maven</groupId>
      <artifactId>rewrite-maven-plugin</artifactId>
      <version>5.47.0</version>
    </plugin>
  </plugins>
</build>
```

Then run the migration on the command line:

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.recipeArtifactCoordinates=io.modelcontextprotocol.sdk:mcp-migrate:1.1.0 \
    -Drewrite.activeRecipes=io.modelcontextprotocol.sdk.migrations.McpSchemaMigration
```

To preview what will change without writing files, use the `dryRun` goal instead:

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:dryRun \
    -Drewrite.recipeArtifactCoordinates=io.modelcontextprotocol.sdk:mcp-migrate:1.1.0 \
    -Drewrite.activeRecipes=io.modelcontextprotocol.sdk.migrations.McpSchemaMigration
```

### Gradle (Kotlin DSL)

```kotlin
plugins {
    id("org.openrewrite.rewrite") version "6.26.0"
}

rewrite {
    activeRecipe("io.modelcontextprotocol.sdk.migrations.McpSchemaMigration")
    setRecipeArtifactCoordinates("io.modelcontextprotocol.sdk:mcp-migrate:1.1.0")
}
```

Run the migration:

```bash
./gradlew rewriteRun
```

### Running a single sub-recipe

If you only need to migrate one domain area (for example, only tool types), pass the specific sub-recipe name:

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.recipeArtifactCoordinates=io.modelcontextprotocol.sdk:mcp-migrate:1.1.0 \
    -Drewrite.activeRecipes=io.modelcontextprotocol.sdk.migrations.MigrateToolTypes
```

---

## What the recipes do and do not change

**The recipes rewrite:**
- `import` statements — both single-type (`import io.modelcontextprotocol.spec.McpSchema.Tool`) and wildcard (`import io.modelcontextprotocol.spec.McpSchema.*`) imports
- Qualified type references in source code (e.g. `McpSchema.Tool.builder()`)
- Method parameter and return-type declarations
- Generic type arguments
- `instanceof` pattern expressions

**The recipes do not change:**
- Runtime behaviour — the migrated code is semantically identical
- Types that remain in `McpSchema` (see the full list in [MIGRATION-1.1.md](../MIGRATION-1.1.md))
- The removal of `sealed` from `Request`, `Result`, and `Notification` — this is a compile-time constraint change, not a source reference change; it does not require code edits in user code

---

## After running the recipes

Review the diff produced by OpenRewrite (`git diff`), compile, and run your tests. If any type references could not be resolved (typically because they appear inside generated sources or annotation processors), fix those manually using the mapping table in [MIGRATION-1.1.md](../MIGRATION-1.1.md).
