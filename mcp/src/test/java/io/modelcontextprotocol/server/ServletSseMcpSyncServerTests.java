/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link McpSyncServer} using {@link HttpServletSseServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class ServletSseMcpSyncServerTests extends AbstractMcpSyncServerTests {

	@Override
	protected McpServerTransportProvider createMcpTransportProvider() {
		return new HttpServletSseServerTransportProvider(new ObjectMapper(), "/mcp/message");
	}

}
