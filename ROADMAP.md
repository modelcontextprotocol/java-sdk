# Roadmap

## Spec Implementation Tracking

The SDK tracks implementation of MCP spec components via GitHub Projects, with a dedicated project board for each spec revision. For example, see the [2025-11-25 spec revision board](https://github.com/orgs/modelcontextprotocol/projects/26/views/1).

## Current Focus Areas

### 2.x — Stable Line (2025-11-25 spec)

The current stable release line is **2.x** (latest: [2.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1), August 2026), implementing the [2025-11-25 MCP specification revision](https://modelcontextprotocol.io/specification/2025-11-25), including:

- **Spec-accurate schema**: enforced required fields with lenient wire deserialization, and a JSON compatibility foundation for forward/backward wire compatibility
- **Enhanced schemas**: JSON Schema 2020-12 validation of tool inputs and embedded schema documents (SEP-1613)
- **Richer elicitation**: client-side schema defaults (SEP-1034), URL mode elicitation (SEP-1036), form-based elicitation schemas
- **Icons metadata** (SEP-973): icons for tools, resources, resource templates, and prompts
- **Streamable HTTP first**: SSE transports deprecated in favor of Streamable HTTP
- **Pluggable JSON serialization**: Jackson 2 and Jackson 3 modules

2.x development continues with patch and minor releases for bug fixes, conformance improvements, and non-breaking features. See [CHANGELOG.md](CHANGELOG.md) for the release history.

The earlier **1.x and 0.x release lines receive security patches only** — no feature or bug-fix backports. Users on those lines are encouraged to upgrade via the [v2 migration guide](MIGRATION-2.0.md).

### 3.x — 2026-07-28 Spec Support

The next major version, **3.x**, will implement the [2026-07-28 MCP specification revision](https://modelcontextprotocol.io/specification/2026-07-28), including `server/discover` and the SEP-2575 stateless lifecycle. The first 3.0.0 milestone releases are planned for **September 2026**, tracked via a dedicated spec revision project board.

### SDK Tiering

The Java SDK is an official [Tier 2 SDK](https://modelcontextprotocol.io/community/sdk-tiers) committed to full protocol support: new spec revisions are implemented within the Tier 2 six-month window, with conformance continuously verified against the [MCP conformance suite](https://github.com/modelcontextprotocol/conformance) in CI. Once caught up on the most recent specification revision, we aim for Tier 1: fully supporting new specification features on the day of their release.

### Future Directions

Major version updates will align with MCP specification changes and breaking API changes as needed. The SDK is designed to evolve with the Java ecosystem, including:

- Virtual Threads and Structured Concurrency support
- Additional transport implementations
- Performance optimizations

Development is tracked via [GitHub Issues](https://github.com/modelcontextprotocol/java-sdk/issues) and [GitHub Projects](https://github.com/orgs/modelcontextprotocol/projects).
