# Changelog

All notable changes to the MCP Java SDK are documented in the
[GitHub Releases](https://github.com/modelcontextprotocol/java-sdk/releases),
which serve as the canonical, detailed changelog for every version. This file
summarizes the release history and the currently maintained release lines.

Versioning follows [Semantic Versioning](https://semver.org/); see
[VERSIONING.md](VERSIONING.md) for the breaking-change policy and
[SECURITY.md](SECURITY.md) for the supported-versions security policy.

## Release lines

| Line | Latest | Spec revision | Status |
|------|--------|---------------|--------|
| 2.x  | [2.0.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0) (2026-06-11) | 2025-11-25 | Active development |
| 1.1.x | [1.1.3](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.1.3) (2026-05-21) | 2025-06-18 | Security patches only |
| 1.0.x | [1.0.2](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.0.2) (2026-05-21) | 2025-06-18 | Security patches only |
| 0.18.x | [0.18.3](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v0.18.3) (2026-06-09) | 2025-06-18 | Security patches only |

## 2.0.0 — 2026-06-11

First major release since 1.x, tracking the **2025-11-25** MCP specification.
Upgrading from 1.x? See the [v2 migration guide](MIGRATION-2.0.md).

- New JSON compatibility foundation for forward/backward wire compatibility,
  with pluggable Jackson 2 / Jackson 3 serialization modules
- Spec-accurate schema: enforced required fields, lenient wire deserialization
- End-to-end validation of tool inputs and embedded JSON Schema documents
  (JSON Schema 2020-12, SEP-1613)
- Richer elicitation: client-side schema defaults (SEP-1034), URL elicitation
  (SEP-1036), form-based elicitation schemas
- Icons and metadata support (SEP-973)
- Streamable HTTP first: SSE transports deprecated in favor of Streamable HTTP
- Module restructuring: `mcp-core`, `mcp-json-jackson2`, `mcp-json-jackson3`,
  `mcp-bom`

Full notes: [v2.0.0 release](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0)
(preceded by milestones [M1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0-M1),
[M2](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0-M2),
[M3](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0-M3),
[RC1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0-RC1)).

## 1.x

- [1.1.3](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.1.3) /
  [1.0.2](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.0.2) — 2026-05-21:
  SSE client transport message-endpoint validation (backports)
- [1.1.2](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.1.2) — 2026-04-25
- [1.1.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.1.1) /
  [1.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.0.1) — 2026-03-27
- [1.1.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.1.0) — 2026-03-13
- [1.0.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.0.0) — 2026-02-23:
  first stable release; see the [1.0 migration guide](MIGRATION-1.0.md)

## 0.x

- [0.18.3](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v0.18.3) — 2026-06-09:
  security fix for GHSA-hv2w-8mjj-jw22
- [0.18.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v0.18.0) –
  [0.18.2](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v0.18.2) — 2026-02 to 2026-05
- [0.8.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v0.8.0) –
  [0.17.2](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v0.17.2) — 2025-03 to 2026-01:
  see the [full release list](https://github.com/modelcontextprotocol/java-sdk/releases)
