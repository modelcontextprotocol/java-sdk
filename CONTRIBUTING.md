# Contributing to Model Context Protocol Java SDK

Thank you for your interest in contributing to the Model Context Protocol Java SDK!
This document outlines how to contribute to this project.

## Prerequisites

The following software is required to work on the codebase:

- `Java 17` or above
- `Docker`
- `npx`

## Getting Started

1. Fork the repository
2. Clone your fork:

```bash
git clone https://github.com/YOUR-USERNAME/java-sdk.git
cd java-sdk
```

3. Build from source:

```bash
./mvnw clean install -DskipTests # skip the tests
./mvnw test # run tests
```

## Reporting Issues

Please create an issue in the repository if you discover a bug or would like to 
propose an enhancement. Bug reports should have a reproducer in the form of a code 
sample or a repository attached that the maintainers or contributors can work with to 
address the problem.

## Making Changes

1. Create a new branch:

```bash
git checkout -b feature/your-feature-name
```

2. Make your changes
3. Validate your changes:

```bash
./mvnw clean test
```

### Change Proposal Guidelines

#### Principles of MCP

1. **Simple + Minimal**: It is much easier to add things to the codebase than it is to
   remove them. To maintain simplicity, we keep a high bar for adding new concepts and
   primitives as each addition requires maintenance and compatibility consideration.
2. **Concrete**: Code changes need to be based on specific usage and implementation
   challenges and not on speculative ideas. Most importantly, the SDK is meant to 
   implement the MCP specification.

## Submitting Changes

1. For non-trivial changes, please clarify with the maintainers in an issue whether 
   you can contribute the change and the desired scope of the change.
2. For trivial changes (for example a couple of lines or documentation changes) there 
   is no need to open an issue first.
3. Push your changes to your fork.
4. Submit a pull request to the main repository.
5. Follow the pull request template.
6. Wait for review.
7. For any follow-up work, please add new commits instead of force-pushing. This will 
   allow the reviewer to focus on incremental changes instead of having to restart the 
   review process.

## Evolving wire-serialized records

Records in `McpSchema` are serialized directly to the MCP JSON wire format. Follow these rules whenever you add a field to an existing record to keep the protocol forward- and backward-compatible.

### Rules

1. **Add new components only at the end** of the record's component list. Never reorder or rename existing components.
2. **Annotate every component** with `@JsonProperty("fieldName")` even when the Java name already matches. This survives local renames via refactoring tools.
3. **Use boxed types** (`Boolean`, `Integer`, `Long`, `Double`) so the field can be absent on the wire without a special sentinel.
4. **Default to `null`**, not an empty collection or neutral value, so the `@JsonInclude(NON_NULL)` rule omits the field for clients that don't know about it yet.
5. **Keep existing constructors as source-compatible overloads** that delegate to the new canonical constructor and pass `null` for the new component. Do not remove them in the same release that adds the field.
6. **Do not put `@JsonCreator` on the canonical constructor** unless strictly necessary. Jackson auto-detects record canonical constructors; adding `@JsonCreator` pins deserialization to that exact parameter order forever.
7. **Do not convert `null` to a default value in the canonical constructor.** Null carries "absent" semantics and must be preserved through the serialization round-trip.
8. **Add three tests per new field** (put them in the relevant test class in `mcp-test`):
   - Deserialize JSON *without* the field → succeeds, field is `null`.
   - Serialize an instance with the field unset (`null`) → the key is absent from output.
   - Deserialize JSON with an extra *unknown* field → succeeds.
9. **An inner `Builder` subclass can be used.** This improves the developer experience since frequently not all fields are required. 

### Example

Suppose `ToolAnnotations` gains an optional `audience` field:

```java
// Before
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolAnnotations(
    @JsonProperty("title") String title,
    @JsonProperty("readOnlyHint") Boolean readOnlyHint,
    @JsonProperty("destructiveHint") Boolean destructiveHint,
    @JsonProperty("idempotentHint") Boolean idempotentHint,
    @JsonProperty("openWorldHint") Boolean openWorldHint) { ... }

// After — new component appended at the end
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolAnnotations(
    @JsonProperty("title") String title,
    @JsonProperty("readOnlyHint") Boolean readOnlyHint,
    @JsonProperty("destructiveHint") Boolean destructiveHint,
    @JsonProperty("idempotentHint") Boolean idempotentHint,
    @JsonProperty("openWorldHint") Boolean openWorldHint,
    @JsonProperty("audience") List<String> audience) {   // new — added at end

    // Keep the old constructor so existing callers still compile
    public ToolAnnotations(String title, Boolean readOnlyHint,
            Boolean destructiveHint, Boolean idempotentHint, Boolean openWorldHint) {
        this(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint, null);
    }
}
```

Tests to add:

```java
@Test
void toolAnnotationsDeserializesWithoutAudience() throws IOException {
    ToolAnnotations a = mapper.readValue("""
            {"title":"My tool","readOnlyHint":true}""", ToolAnnotations.class);
    assertThat(a.audience()).isNull();
}

@Test
void toolAnnotationsOmitsNullAudience() throws IOException {
    String json = mapper.writeValueAsString(new ToolAnnotations("t", null, null, null, null));
    assertThat(json).doesNotContain("audience");
}

@Test
void toolAnnotationsToleratesUnknownFields() throws IOException {
    ToolAnnotations a = mapper.readValue("""
            {"title":"t","futureField":42}""", ToolAnnotations.class);
    assertThat(a.title()).isEqualTo("t");
}
```

## Code of Conduct

This project follows a Code of Conduct. Please review it in
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Questions

If you have questions, please create a discussion in the repository.

## License

By contributing, you agree that your contributions will be licensed under the MIT
License.

## Security

Please review our [Security Policy](SECURITY.md) for reporting security issues.