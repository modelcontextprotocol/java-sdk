/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link McpSyncServer} using {@link StdioServerTransport}.
 *
 * @author Christian Tzolov
 */
@Deprecated
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpSyncServerDeprecatedTests extends AbstractMcpSyncServerDeprecatedTests {

	@Override
	protected ServerMcpTransport createMcpTransport() {
		return new StdioServerTransport();
	}

}
