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

class McpAsyncServerCapabilitiesTests {

	@Test
	void standardTransportPreservesCallerProvidedCapabilities() {
		McpServerTransportProvider transportProvider = mock(McpServerTransportProvider.class);
		McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder().tools(true).build();

		McpAsyncServer server = McpServer.async(transportProvider)
			.jsonMapper(mock(McpJsonMapper.class))
			.jsonSchemaValidator(mock(JsonSchemaValidator.class))
			.capabilities(capabilities)
			.build();

		assertThat(server.getServerCapabilities()).isEqualTo(capabilities);
		assertThat(server.getServerCapabilities().logging()).isNull();
	}

	@Test
	void streamableTransportPreservesCallerProvidedCapabilities() {
		McpStreamableServerTransportProvider transportProvider = mock(McpStreamableServerTransportProvider.class);
		McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder().tools(true).build();

		McpAsyncServer server = McpServer.async(transportProvider)
			.jsonMapper(mock(McpJsonMapper.class))
			.jsonSchemaValidator(mock(JsonSchemaValidator.class))
			.capabilities(capabilities)
			.build();

		assertThat(server.getServerCapabilities()).isEqualTo(capabilities);
		assertThat(server.getServerCapabilities().logging()).isNull();
	}

	@Test
	void preservesExplicitLoggingCapability() {
		McpServerTransportProvider transportProvider = mock(McpServerTransportProvider.class);
		McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder().logging().build();

		McpAsyncServer server = McpServer.async(transportProvider)
			.jsonMapper(mock(McpJsonMapper.class))
			.jsonSchemaValidator(mock(JsonSchemaValidator.class))
			.capabilities(capabilities)
			.build();

		assertThat(server.getServerCapabilities()).isEqualTo(capabilities);
		assertThat(server.getServerCapabilities().logging()).isNotNull();
	}

}
