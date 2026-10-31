# Contributing to Model Context Protocol Java SDK

Thank you for your interest in contributing to the Model Context Protocol Java SDK!
This document outlines how to contribute to this project.

## Before you start

> [!IMPORTANT]
> **The most useful contribution is a good issue.** Pull requests from outside
> the maintainer team are closed automatically unless they link an issue
> assigned to their author.

### Why issues rather than pull requests

Coding agents can now turn any open issue into a plausible-looking pull request within
hours, and generate speculative bug reports from a scan of the code, so we
receive far more issues and PRs than we can read carefully. Reviewing a PR
properly still takes as long as it always did. When an issue is well described,
it is usually quicker for a maintainer to write a fix that fits the codebase
than to review and reshape someone else's.

What we can't produce ourselves is your context: what you were doing, what you
expected, a minimal reproduction, and the system where it broke.
That is what a good issue carries, so that is what we ask for first.

### How pull requests get in

A PR from someone outside the maintainer team stays open when both of these
hold:

1. Its description links an open issue in this repository with `Fixes #123`
   (or `Closes`, `Resolves`), or with `Updates #123` for a partial fix.
2. A maintainer has assigned that issue to you, or the issue carries the [`help
   wanted`](https://github.com/modelcontextprotocol/java-sdk/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22help%20wanted%22)
   label, which means we'd welcome a PR for it from anyone.

Otherwise a bot labels the PR `missing-issue-link`, leaves a comment explaining
this, and closes it. Drafts are treated the same way. If that happens to yours,
there's no need to open a new one: it reopens automatically as soon as a
maintainer assigns you the issue, or when you edit the description to link one
that qualifies. While it's closed, push updates as new commits rather than
force-pushing, since GitHub can't reopen a PR whose branch has been rewritten.
This applies to small fixes like typos too; for those, an issue pointing at the
problem is all we need.

Whether to assign an issue, and to whom, is a maintainer call, and it depends
on our capacity at the time as much as on the change itself. Comments that only
ask to be assigned don't factor into it, so please skip those (and don't have
an agent post them). What does help is engaging with the issue itself:
confirming the reproduction, asking about the intended behavior, or briefly
describing the approach you'd take. If you reported the issue and would like to
fix it yourself, say so in the issue.

Maintainers can override the check by reopening the PR, by removing its
`missing-issue-link` label, or by adding `bypass-issue-check`.

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

## AI agents

**We accept contributions created with the help of AI coding agents, but they must be carefully reviewed by a human who remains accountable for the quality of the contribution.**
These can be issues or pull requests. For issues, please ensure you describe your particular use-case, and not general considerations found by an AI agent.
Contributions submitted by GitHub accounts controlled by autonomous AI bots are forbidden.

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

Please don't submit pull requests:

- With GitHub accounts managed by autonomous AI bots
- For already assigned issues (as the assignee is or plans to work on it)

When submitting changes:

1. Every PR from outside the maintainer team must link an issue that a maintainer
   has assigned to you, or one labeled `help wanted`, as described in [How pull
   requests get in](#how-pull-requests-get-in). For changes that don't relate to an
   existing issue, file an issue first, clarify the desired scope of the change with
   the maintainers, and wait for it to be assigned before opening a PR.
2. Maintainers will triage issues and act on them. There is no such guarantee on pull requests.
3. Push your changes to your fork.
4. Submit a pull request to the main repository.
5. Follow the pull request template.
6. Wait for review.
7. For any follow-up work, please add new commits instead of force-pushing. This will
   allow the reviewer to focus on incremental changes instead of having to restart the 
   review process.

## Evolving wire-serialized records

Records in `McpSchema` are serialized directly to the MCP JSON wire format. The rules differ depending on whether the field you are adding (or maintaining) is *optional* — Java code may legitimately leave it `null` and the wire may omit it — or *spec-required* by MCP. Follow **Case A** for optional fields and **Case B** for spec-required fields.

### Case A — Optional fields

1. **Add new components only at the end** of the record's component list. Never reorder or rename existing components.
2. **Annotate every component** with `@JsonProperty("fieldName")` even when the Java name already matches. This survives local renames via refactoring tools.
3. **Use boxed types** (`Boolean`, `Integer`, `Long`, `Double`) so the field can be absent on the wire without a special sentinel.
4. **Default to `null`**, not an empty collection or neutral value, so the `@JsonInclude(NON_ABSENT)` rule omits the field for clients that don't know about it yet.
5. **Keep existing constructors as source-compatible overloads** that delegate to the new canonical constructor and pass `null` for the new component. Do not remove them in the same release that adds the field.
6. **Do not put `@JsonCreator` on the canonical constructor** unless strictly necessary. Jackson auto-detects record canonical constructors; adding `@JsonCreator` pins deserialization to that exact parameter order forever. *(For records that also have spec-required fields, the `@JsonCreator` belongs on a separate static `fromJson` factory — see Case B, Rule 2.)*
7. **Do not convert `null` to a default value in the canonical constructor.** Null carries "absent" semantics and must be preserved through the serialization round-trip. *(Spec-required fields are the exception — see Case B, Rule 1.)*
8. **Add three tests per new field** (put them in the relevant test class in `mcp-test`):
   - Deserialize JSON *without* the field → succeeds, field is `null`.
   - Serialize an instance with the field unset (`null`) → the key is absent from output.
   - Deserialize JSON with an extra *unknown* field → succeeds.
9. **An inner `Builder` subclass can be used.** This improves the developer experience since frequently not all fields are required.

### Case B — Spec-required fields

When the MCP specification marks a field as required, callers must not be able to construct a structurally invalid record, but the wire parser must still tolerate peers that fail to send it. Follow these rules in addition to the relevant Case A rules (annotation, naming, append-only).

1. **Reject `null` in the compact constructor** with `Assert.notNull`. This throws `IllegalArgumentException` at construction time instead of producing a record that fails later in serialization or protocol handling. Overrides Case A Rule 7 for this field. The compact constructor is the one path that both Java callers and `fromJson` (Rule 2) go through, so it may only enforce what a wire default can satisfy. Stricter Java-side checks, such as non-empty identifiers (`Assert.hasText` on `name`, `uri`, `uriTemplate`, `version`), belong in the required-first builder factory (Rule 3).
2. **Add a `@JsonCreator` static `fromJson` factory** alongside the canonical constructor. When a required field is absent from the wire, substitute a documented safe default (`""` for strings, `[]` for collections, `{}` for maps, `0` / `0.0` for numerics, `INFO` for `LoggingLevel`, etc.) and log at `WARN` naming the field and the value used. The SDK must not halt the conversation because of a missing field. Place `@JsonCreator` on this `fromJson` factory, never on the canonical constructor (Case A Rule 6 still applies to the canonical constructor itself).
   - Exception: do not substitute a default when the missing value would make the message unprocessable anyway. This covers `JSONRPCResponse.JSONRPCError` (`code` / `message`), since a malformed JSON-RPC error envelope is unrecoverable, and the identifiers a request is dispatched on (e.g. `name` of `tools/call` and `prompts/get`, `uri` of `resources/read`, `ref` of `completion/complete`). A missing dispatch identifier must fail fast with an `INVALID_PARAMS` error, not an internal error.
3. **Provide a required-first builder factory** `builder(req1, req2, …)` and remove the corresponding setters from the `Builder`. A no-arg `builder()` factory must not exist on a record that has required fields. If one already exists for source compatibility, mark it `@Deprecated`.
4. **Add tests per required field**:
   - Constructing the record with `null` for the field throws `IllegalArgumentException`.
   - Deserializing JSON *without* the field succeeds and yields the documented default, or, for a Rule 2 exception, fails.
   - Deserializing JSON with an extra *unknown* field still succeeds (Case A Rule 8 also applies).

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