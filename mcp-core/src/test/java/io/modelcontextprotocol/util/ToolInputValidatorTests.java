/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.util;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ToolInputValidator}.
 *
 * @author Andrei Shakirin
 */
class ToolInputValidatorTests {

	private final JsonSchemaValidator validator = mock(JsonSchemaValidator.class);

	private final Map<String, Object> inputSchema = Map.of("type", "object", "properties",
			Map.of("name", Map.of("type", "string")), "required", List.of("name"));

	private final Tool toolWithSchema = Tool.builder("test-tool", inputSchema).description("Test tool").build();

	private final Tool toolWithoutSchema = Tool.builder("test-tool").description("Test tool").build();

	@Test
	void validate_whenDisabled_returnsNull() {
		CallToolResult result = ToolInputValidator.validate(toolWithSchema, Map.of("name", "test"), false, validator);

		assertThat(result).isNull();
		verify(validator, never()).validate(any(), any());
	}

	@Test
	void validate_whenNoSchema_returnsNull() {
		when(validator.validate(any(), any())).thenReturn(ValidationResponse.asValid(null));

		CallToolResult result = ToolInputValidator.validate(toolWithoutSchema, Map.of("name", "test"), true, validator);

		assertThat(result).isNull();
		verify(validator).validate(any(), any());
	}

	@Test
	void validate_whenNoValidator_returnsNull() {
		CallToolResult result = ToolInputValidator.validate(toolWithSchema, Map.of("name", "test"), true, null);

		assertThat(result).isNull();
	}

	@Test
	void validate_withValidInput_returnsNull() {
		when(validator.validate(any(), any())).thenReturn(ValidationResponse.asValid(null));

		CallToolResult result = ToolInputValidator.validate(toolWithSchema, Map.of("name", "test"), true, validator);

		assertThat(result).isNull();
	}

	@Test
	void validate_withInvalidInput_returnsErrorResult() {
		when(validator.validate(any(), any())).thenReturn(ValidationResponse.asInvalid("missing required: 'name'"));

		CallToolResult result = ToolInputValidator.validate(toolWithSchema, Map.of(), true, validator);

		assertThat(result).isNotNull();
		assertThat(result.isError()).isTrue();
		assertThat(((TextContent) result.content().get(0)).text()).contains("missing required: 'name'");
		verify(validator).validate(any(), any());
	}

	@Test
	void validate_withNullArguments_usesEmptyMap() {
		when(validator.validate(any(), any())).thenReturn(ValidationResponse.asValid(null));

		CallToolResult result = ToolInputValidator.validate(toolWithSchema, null, true, validator);

		assertThat(result).isNull();
		verify(validator).validate(any(), any());
	}

}
