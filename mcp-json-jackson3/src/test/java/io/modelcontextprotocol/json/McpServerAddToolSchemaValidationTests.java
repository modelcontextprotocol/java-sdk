/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.json;

import java.util.Map;

import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link McpAsyncServer#addTool} schema validation using the real
 * {@link DefaultJsonSchemaValidator}.
 */
class McpServerAddToolSchemaValidationTests {

	private McpServerTransportProvider transportProvider;

	private JacksonMcpJsonMapper jsonMapper;

	private DefaultJsonSchemaValidator validator;

	@BeforeEach
	void setUp() {
		transportProvider = mock(McpServerTransportProvider.class);
		jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
		validator = new DefaultJsonSchemaValidator();
	}

	private McpAsyncServer buildServer() {
		return McpServer.async(transportProvider)
			.serverInfo("test", "1.0")
			.jsonMapper(jsonMapper)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
			.jsonSchemaValidator(validator)
			.build();
	}

	@Test
	void addToolRejectsInvalidInputSchema() {
		// "type" value must be one of the allowed JSON Schema type strings
		Tool tool = Tool.builder("my-tool", Map.of("type", "not-a-valid-type")).build();
		McpServerFeatures.AsyncToolSpecification spec = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> Mono.empty())
			.build();

		assertThatThrownBy(() -> buildServer().addTool(spec).block()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SEP-1613")
			.hasMessageContaining("my-tool")
			.hasMessageContaining("inputSchema");
	}

	@Test
	void addToolRejectsInvalidOutputSchema() {
		// "required" must be an array of strings, not a plain string
		Tool tool = Tool.builder("output-tool", Map.of("type", "object"))
			.outputSchema(Map.of("required", "not-an-array"))
			.build();
		McpServerFeatures.AsyncToolSpecification spec = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> Mono.empty())
			.build();

		assertThatThrownBy(() -> buildServer().addTool(spec).block()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SEP-1613")
			.hasMessageContaining("output-tool")
			.hasMessageContaining("outputSchema");
	}

	@Test
	void addToolAcceptsValidSchemas() {
		Tool tool = Tool.builder("valid-tool", Map.of("type", "object")).build();
		McpServerFeatures.AsyncToolSpecification spec = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> Mono.empty())
			.build();

		assertThatCode(() -> buildServer().addTool(spec).block()).doesNotThrowAnyException();
	}

}
