/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.json.schema;

import java.util.Map;

/**
 * Interface for validating structured content against a JSON schema. This interface
 * defines a method to validate structured content based on the provided output schema.
 *
 * @author Christian Tzolov
 */
public interface JsonSchemaValidator {

	/**
	 * Asserts that the given schema document is a structurally valid JSON Schema. Schemas
	 * without an explicit {@code $schema} declaration, or those that declare JSON Schema
	 * 2020-12, are validated against the 2020-12 meta-schema. Schemas that explicitly
	 * declare a different dialect are accepted without meta-schema validation. Throws
	 * {@link IllegalArgumentException} if validation fails. Silently returns on null
	 * schema. The default implementation delegates to {@link #validateSchema}.
	 * @param context human-readable description of the schema's location (used in error
	 * messages)
	 * @param schema the schema document to validate, or {@code null} (no-op)
	 * @throws IllegalArgumentException if the schema is structurally invalid
	 */
	default void assertConforms(String context, Map<String, Object> schema) {
		if (schema == null) {
			return;
		}
		var result = validateSchema(schema);
		if (!result.valid()) {
			throw new IllegalArgumentException(
					context + " is not a valid JSON Schema 2020-12 document (SEP-1613): " + result.errorMessage());
		}
	}

	/**
	 * Represents the result of a validation operation.
	 *
	 * @param valid Indicates whether the validation was successful.
	 * @param errorMessage An error message if the validation failed, otherwise null.
	 * @param jsonStructuredOutput The text structured content in JSON format if the
	 * validation was successful, otherwise null.
	 */
	record ValidationResponse(boolean valid, String errorMessage, String jsonStructuredOutput) {

		public static ValidationResponse asValid(String jsonStructuredOutput) {
			return new ValidationResponse(true, null, jsonStructuredOutput);
		}

		public static ValidationResponse asInvalid(String message) {
			return new ValidationResponse(false, message, null);
		}
	}

	/**
	 * Validates the structured content against the provided JSON schema.
	 * @param schema The JSON schema to validate against.
	 * @param structuredContent The structured content to validate.
	 * @return A ValidationResponse indicating whether the validation was successful or
	 * not.
	 */
	ValidationResponse validate(Map<String, Object> schema, Object structuredContent);

	/**
	 * Validates that the given schema document itself conforms to JSON Schema 2020-12
	 * (SEP-1613). Schemas that declare an explicit non-2020-12 {@code $schema} dialect
	 * are skipped and considered valid. The default implementation is a no-op.
	 * @param schema the schema document to check
	 * @return a ValidationResponse indicating conformance
	 */
	default ValidationResponse validateSchema(Map<String, Object> schema) {
		return ValidationResponse.asValid(null);
	}

}
