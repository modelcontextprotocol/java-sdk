/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link McpAsyncServer} using {@link StdioServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpAsyncServerTests extends AbstractMcpAsyncServerTests {

	@Override
	protected McpServerTransportProvider createMcpTransportProvider() {
		return new StdioServerTransportProvider();
	}

}
