/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.server.transport.StreamableHttpServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link McpSyncServer} using {@link StreamableHttpServerTransportProvider}.
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StreamableHttpMcpSyncServerTests extends AbstractMcpSyncServerTests {

	@Override
	protected McpServerTransportProvider createMcpTransportProvider() {
		return new StreamableHttpServerTransportProvider();
	}

}
