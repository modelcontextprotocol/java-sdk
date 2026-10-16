/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpAsyncServerTests {

	@Test
	void singleSessionServerPreservesExplicitCapabilities() {
		McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder().tools(true).build();

		McpAsyncServer server = McpServer.async(mock(McpServerTransportProvider.class))
			.capabilities(capabilities)
			.jsonMapper(mock(McpJsonMapper.class))
			.jsonSchemaValidator(mock(JsonSchemaValidator.class))
			.build();

		assertThat(server.getServerCapabilities()).isEqualTo(capabilities);
	}

	@Test
	void streamableServerPreservesExplicitCapabilities() {
		McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder().tools(true).build();

		McpAsyncServer server = McpServer.async(mock(McpStreamableServerTransportProvider.class))
			.capabilities(capabilities)
			.jsonMapper(mock(McpJsonMapper.class))
			.jsonSchemaValidator(mock(JsonSchemaValidator.class))
			.build();

		assertThat(server.getServerCapabilities()).isEqualTo(capabilities);
	}

}
