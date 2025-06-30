/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.server;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Default implementation of the {@link JsonSchemaValidator} interface. This class
 * provides methods to validate structured content against a JSON schema. It uses the
 * NetworkNT JSON Schema Validator library for validation.
 *
 * @author Christian Tzolov
 */
public class DefaultJsonSchemaValidator implements JsonSchemaValidator {

	private static final Logger logger = LoggerFactory.getLogger(DefaultJsonSchemaValidator.class);

	private ObjectMapper objectMapper = new ObjectMapper();

	public DefaultJsonSchemaValidator() {
		this.objectMapper = new ObjectMapper();
	}

	public DefaultJsonSchemaValidator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public ValidationResponse validate(Map<String, Object> schema, Map<String, Object> structuredContent) {

		try {
			// Create JsonSchema validator
			ObjectNode schemaNode = (ObjectNode) this.objectMapper
				.readTree(this.objectMapper.writeValueAsString(schema));

			// Set additional properties to false if not specified in the schema
			if (!schemaNode.has("additionalProperties")) {
				schemaNode.put("additionalProperties", false);
			}

			JsonSchema jsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
				.getSchema(schemaNode);

			// Convert structured content in reult to JsonNode
			JsonNode jsonStructuredOutput = this.objectMapper.valueToTree(structuredContent);

			// Validate outputSchema against structuredContent
			Set<ValidationMessage> validationResult = jsonSchema.validate(jsonStructuredOutput);

			// Check if validation passed
			if (!validationResult.isEmpty()) {
				logger.warn("Validation failed: structuredContent does not match tool outputSchema. "
						+ "Validation errors: {}", validationResult);
				return ValidationResponse
					.asInvalid("Validation failed: structuredContent does not match tool outputSchema. "
							+ "Validation errors: " + validationResult);
			}

			return ValidationResponse.asValid(jsonStructuredOutput.toString());

		}
		catch (JsonProcessingException e) {
			logger.warn("Failed to validate CallToolResult: Error parsing schema: {}", e);
			return ValidationResponse.asInvalid("Error parsing tool JSON Schema: " + e.getMessage());
		}
	}

}
