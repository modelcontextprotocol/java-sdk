/*
 * Copyright 2024-2024 the original author or authors.
 */

package org.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Timeout;
import org.modelcontextprotocol.server.transport.HttpServletSseServerTransport;
import org.modelcontextprotocol.spec.ServerMcpTransport;

/**
 * Tests for {@link McpSyncServer} using {@link HttpServletSseServerTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class ServletSseMcpSyncServerTests extends AbstractMcpSyncServerTests {

	@Override
	protected ServerMcpTransport createMcpTransport() {
		return new HttpServletSseServerTransport(new ObjectMapper(), "/mcp/message");
	}

}
