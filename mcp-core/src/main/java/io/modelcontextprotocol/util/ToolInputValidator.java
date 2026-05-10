/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.util;

import java.util.List;
import java.util.Map;

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

	private ToolInputValidator() {
	}

	/**
	 * Validates tool arguments against the tool's input schema.
	 * @param tool the tool definition containing the input schema
	 * @param arguments the arguments to validate
	 * @param validateToolInputs whether validation is enabled
	 * @param validator the JSON schema validator (may be null)
	 * @return CallToolResult with isError=true if validation fails, null if valid or
	 * validation skipped
	 */
	public static CallToolResult validate(McpSchema.Tool tool, Map<String, Object> arguments,
			boolean validateToolInputs, JsonSchemaValidator validator) {
		if (!validateToolInputs || tool.inputSchema() == null || tool.inputSchema().isEmpty() || validator == null) {
			return null;
		}
		Map<String, Object> args = arguments != null ? arguments : Map.of();
		var validation = validator.validate(tool.inputSchema(), args);
		if (!validation.valid()) {
			logger.warn("Tool '{}' input validation failed: {}", tool.name(), validation.errorMessage());
			return CallToolResult.builder()
				.content(List.of(McpSchema.TextContent.builder(validation.errorMessage()).build()))
				.isError(true)
				.build();
		}
		return null;
	}

}
