/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.util;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates tool input arguments against JSON schema.
 *
 * @author Andrei Shakirin
 */
public final class ToolInputValidator {

	private static final Logger logger = LoggerFactory.getLogger(ToolInputValidator.class);

	/**
	 * System property to disable tool input validation. Set to "false" to disable.
	 * Default is true (validation enabled).
	 */
	public static final String VALIDATE_TOOL_INPUTS_PROPERTY = "io.modelcontextprotocol.validateToolInputs";

	private ToolInputValidator() {
	}

	/**
	 * Returns whether validation is enabled by default based on system property.
	 * @return true if validation is enabled (default), false if disabled
	 */
	public static boolean isEnabledByDefault() {
		return !"false".equalsIgnoreCase(System.getProperty(VALIDATE_TOOL_INPUTS_PROPERTY));
	}

	/**
	 * Validates tool arguments against the tool's input schema.
	 * @param tool the tool definition containing the input schema
	 * @param arguments the arguments to validate
	 * @param validateToolInputs whether validation is enabled
	 * @param jsonMapper the JSON mapper for schema conversion
	 * @param validator the JSON schema validator (may be null)
	 * @return CallToolResult with isError=true if validation fails, null if valid or
	 * validation skipped
	 */
	public static CallToolResult validate(McpSchema.Tool tool, Map<String, Object> arguments,
			boolean validateToolInputs, McpJsonMapper jsonMapper, JsonSchemaValidator validator) {
		if (!validateToolInputs || tool.inputSchema() == null || validator == null) {
			return null;
		}
		Map<String, Object> inputSchema = jsonMapper.convertValue(tool.inputSchema(),
				new TypeRef<Map<String, Object>>() {
				});
		Map<String, Object> args = arguments != null ? arguments : Map.of();
		var validation = validator.validate(inputSchema, args);
		if (!validation.valid()) {
			logger.warn("Tool '{}' input validation failed: {}", tool.name(), validation.errorMessage());
			return CallToolResult.builder()
				.content(List.of(new McpSchema.TextContent(validation.errorMessage())))
				.isError(true)
				.build();
		}
		return null;
	}

}
