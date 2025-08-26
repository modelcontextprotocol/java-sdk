/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

/**
 * Tests for {@link McpSyncServer} using
 * {@link HttpServletStreamableServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class HttpServletStreamableSyncServerTests extends AbstractMcpSyncServerTests {

	protected McpStreamableServerTransportProvider createMcpTransportProvider() {
		return HttpServletStreamableServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.mcpEndpoint("/mcp/message")
			.build();
	}

	@Override
	protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(createMcpTransportProvider());
	}

}
