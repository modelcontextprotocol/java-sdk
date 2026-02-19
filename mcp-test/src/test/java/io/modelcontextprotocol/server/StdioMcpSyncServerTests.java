/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Timeout;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;

/**
 * Tests for {@link McpSyncServer} using {@link StdioServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpSyncServerTests extends AbstractMcpSyncServerTests {

	protected McpServerTransportProvider createMcpTransportProvider() {
		return new StdioServerTransportProvider(JSON_MAPPER);
	}

	@Override
	protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(createMcpTransportProvider());
	}

}
