## Reject listRoots when client lacks roots capability, without sending a request

Fixes #1067

### What changed

`McpAsyncServerExchange#createMessage` and `#createElicitation` both fail fast
with an `IllegalStateException` when the client hasn't declared the relevant
capability, avoiding an unnecessary round trip to the client.

`#listRoots(String)` didn't follow the same pattern: it would send a
`roots/list` request to the client even when the client never advertised
`roots` support, only to fail later (or behave unexpectedly) depending on the
client's own handling of an unsupported method.

This PR aligns `#listRoots(String)` with the existing fail-fast convention
already used by `createMessage`/`createElicitation`:

- If `clientCapabilities` is `null` (client not yet initialized), fail with
  `"Client must be initialized. Call the initialize method first!"`
- If `clientCapabilities.roots()` is `null` (client didn't declare roots
  support), fail with `"Client must be configured with roots capabilities"`
- Otherwise, proceed with the request as before.

`listRoots()` (no-arg, paginated variant) and `McpSyncServerExchange#listRoots`
both delegate to `listRoots(String)`, so they're covered automatically.

### Testing

Added three unit tests to `McpAsyncServerExchangeTests` mirroring the existing
capability-check tests for `createMessage`:

- `testListRootsWithNullCapabilities`
- `testListRootsWithoutRootsCapabilities`
- `testListRootsWithSpecificCursorAndNullCapabilities`

Each verifies the correct `IllegalStateException` is raised and that
`session.sendRequest(...)` is never invoked in these cases.

I audited all existing callers of `listRoots` in the repo (integration tests
in `AbstractMcpClientServerIntegrationTests`, and the roots-changed handler in
`McpAsyncServer`) — all already configure/assume roots capability, so this
change shouldn't affect existing behavior anywhere else in the codebase.
