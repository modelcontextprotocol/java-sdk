/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.util;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ToolInputValidator}.
 */
class ToolInputValidatorTests {

	private final McpJsonMapper jsonMapper = mock(McpJsonMapper.class);

	private final JsonSchemaValidator validator = mock(JsonSchemaValidator.class);

	private final McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema("object",
			Map.of("name", Map.of("type", "string")), List.of("name"), null, null, null);

	private final Tool toolWithSchema = Tool.builder()
		.name("test-tool")
		.description("Test tool")
		.inputSchema(inputSchema)
		.build();

	private final Tool toolWithoutSchema = Tool.builder().name("test-tool").description("Test tool").build();

	@Test
	void validate_whenDisabled_returnsNull() {
		CallToolResult result = ToolInputValidator.validate(toolWithSchema, Map.of("name", "test"), false, jsonMapper,
				validator);

		assertThat(result).isNull();
		verify(validator, never()).validate(any(), any());
	}

	@Test
	void validate_whenNoSchema_returnsNull() {
		CallToolResult result = ToolInputValidator.validate(toolWithoutSchema, Map.of("name", "test"), true, jsonMapper,
				validator);

		assertThat(result).isNull();
		verify(validator, never()).validate(any(), any());
	}

	@Test
	void validate_whenNoValidator_returnsNull() {
		CallToolResult result = ToolInputValidator.validate(toolWithSchema, Map.of("name", "test"), true, jsonMapper,
				null);

		assertThat(result).isNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void validate_withValidInput_returnsNull() {
		when(jsonMapper.convertValue(any(), any(TypeRef.class))).thenReturn(Map.of("type", "object"));
		when(validator.validate(any(), any())).thenReturn(ValidationResponse.asValid(null));

		CallToolResult result = ToolInputValidator.validate(toolWithSchema, Map.of("name", "test"), true, jsonMapper,
				validator);

		assertThat(result).isNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void validate_withInvalidInput_returnsErrorResult() {
		when(jsonMapper.convertValue(any(), any(TypeRef.class))).thenReturn(Map.of("type", "object"));
		when(validator.validate(any(), any())).thenReturn(ValidationResponse.asInvalid("missing required: 'name'"));

		CallToolResult result = ToolInputValidator.validate(toolWithSchema, Map.of(), true, jsonMapper, validator);

		assertThat(result).isNotNull();
		assertThat(result.isError()).isTrue();
		assertThat(((TextContent) result.content().get(0)).text()).contains("missing required: 'name'");
	}

	@Test
	@SuppressWarnings("unchecked")
	void validate_withNullArguments_usesEmptyMap() {
		when(jsonMapper.convertValue(any(), any(TypeRef.class))).thenReturn(Map.of("type", "object"));
		when(validator.validate(any(), any())).thenReturn(ValidationResponse.asValid(null));

		CallToolResult result = ToolInputValidator.validate(toolWithSchema, null, true, jsonMapper, validator);

		assertThat(result).isNull();
		verify(validator).validate(any(), any());
	}

}
