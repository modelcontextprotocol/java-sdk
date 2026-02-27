# Versioning Policy

The MCP Java SDK (`io.modelcontextprotocol.sdk`) follows [Semantic Versioning 2.0.0](https://semver.org/).

## Version Format

`MAJOR.MINOR.PATCH`

- **MAJOR**: Incremented for breaking changes (see below).
- **MINOR**: Incremented for new features that are backward-compatible.
- **PATCH**: Incremented for backward-compatible bug fixes.

## What Constitutes a Breaking Change

The following changes are considered breaking and require a major version bump:

- Removing or renaming a public API (class, interface, method, or constant).
- Changing the signature of a public method in a way that breaks existing callers (removing parameters, changing required/optional status, changing types).
- Removing or renaming a public interface method or field.
- Changing the behavior of an existing API in a way that breaks documented contracts.
- Dropping support for a Java LTS version.
- Removing support for a transport type.
- Changes to the MCP protocol version that require client/server code changes.
- Removing a module from the SDK.

The following are **not** considered breaking:

- Adding new methods with default implementations to interfaces.
- Adding new public APIs, classes, interfaces, or methods.
- Adding new optional parameters to existing methods (through method overloading).
- Bug fixes that correct behavior to match documented intent.
- Internal refactoring that does not affect the public API.
- Adding support for new MCP spec features.
- Changes to test dependencies or build tooling.
- Adding new modules to the SDK.

## How Breaking Changes Are Communicated

1. **Changelog**: All breaking changes are documented in the GitHub release notes with migration instructions.
2. **Deprecation**: When feasible, APIs are deprecated for at least one minor release before removal using `@Deprecated` annotations, which surface warnings through Java tooling and IDEs.
3. **Migration guide**: Major version releases include a migration guide describing what changed and how to update.
4. **PR labels**: Pull requests containing breaking changes are labeled with `breaking change`.

## Maven Coordinates

All SDK modules share the same version number and are released together. The BOM (`mcp-bom`) provides dependency management for all SDK modules to ensure version consistency.
