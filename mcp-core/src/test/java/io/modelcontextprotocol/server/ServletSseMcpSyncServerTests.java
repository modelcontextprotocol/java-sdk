/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

/**
 * Tests for {@link McpSyncServer} using {@link HttpServletSseServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class ServletSseMcpSyncServerTests extends AbstractMcpSyncServerTests {

	protected McpServerTransportProvider createMcpTransportProvider() {
		return HttpServletSseServerTransportProvider.builder().messageEndpoint("/mcp/message").build();
	}

	@Override
	protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(createMcpTransportProvider());
	}

}
