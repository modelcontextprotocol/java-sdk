# Roadmap

## Spec Implementation Tracking

The SDK tracks implementation of MCP spec components via GitHub Projects, with a dedicated project board for each spec revision. For example, see the [2025-11-25 spec revision board](https://github.com/orgs/modelcontextprotocol/projects/26/views/1).

## Current Focus Areas

### 2025-11-25 Spec Implementation

The Java SDK is actively implementing the [2025-11-25 MCP specification revision](https://github.com/orgs/modelcontextprotocol/projects/26/views/1).

Key features in this revision include:

- **Tasks**: Experimental support for tracking durable requests with polling and deferred result retrieval
- **Tool calling in sampling**: Support for `tools` and `toolChoice` parameters
- **URL mode elicitation**: Client-side URL elicitation requests
- **Icons metadata**: Servers can expose icons for tools, resources, resource templates, and prompts
- **Enhanced schemas**: JSON Schema 2020-12 as default, improved enum support, default values for elicitation
- **Security improvements**: Updated security best practices, enhanced authorization flows, enabling OAuth integrations

See the full [changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog) for details.

### Tier 1 SDK Support

Once we catch up on the most recent MCP specification revision we aim to fully support all the upcoming specification features on the day of its release.

### v1.x Development

The Java SDK is currently in active development as v1.x, following a recent stable 1.0.0 release. The SDK provides:

- MCP protocol implementation
- Synchronous and asynchronous programming models
- Multiple transport options (STDIO, HTTP/SSE, Servlet)
- Pluggable JSON serialization (Jackson 2 and Jackson 3)

Development is tracked via [GitHub Issues](https://github.com/modelcontextprotocol/java-sdk/issues) and [GitHub Projects](https://github.com/orgs/modelcontextprotocol/projects).

### Future Versions

Major version updates will align with MCP specification changes and breaking API changes as needed. The SDK is designed to evolve with the Java ecosystem, including:

- Virtual Threads and Structured Concurrency support
- Additional transport implementations
- Performance optimizations
