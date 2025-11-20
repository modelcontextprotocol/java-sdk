/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;

import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

/**
 * Tests for {@link McpAsyncServer} using {@link StdioServerTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpAsyncServerTests extends AbstractMcpAsyncServerTests {

	protected McpServerTransportProvider createMcpTransportProvider() {
		return new StdioServerTransportProvider(JSON_MAPPER);
	}

	@Override
	protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(createMcpTransportProvider());
	}

}
