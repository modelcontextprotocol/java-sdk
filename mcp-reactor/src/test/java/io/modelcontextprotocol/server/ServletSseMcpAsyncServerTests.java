/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link McpAsyncServer} using {@link HttpServletSseServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class ServletSseMcpAsyncServerTests extends AbstractMcpAsyncServerTests {

	@Override
	protected McpServerTransportProvider createMcpTransportProvider() {
		return HttpServletSseServerTransportProvider.builder().messageEndpoint("/mcp/message").build();
	}

}
