/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.json.schema;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Interface for validating structured content against a JSON schema. This interface
 * defines a method to validate structured content based on the provided output schema.
 *
 * @author Christian Tzolov
 */
public interface JsonSchemaValidator {

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
	ValidationResponse validate(Map<String, Object> schema, Map<String, Object> structuredContent);

	/**
	 * Resolves the default {@link JsonSchemaValidator}.
	 * @return The default {@link JsonSchemaValidator}
	 * @throws IllegalStateException If no {@link JsonSchemaValidator} implementation
	 * exists on the classpath.
	 */
	static JsonSchemaValidator createDefault() {
		AtomicReference<IllegalStateException> ex = new AtomicReference<>();
		return ServiceLoader.load(JsonSchemaValidatorSupplier.class).stream().flatMap(p -> {
			try {
				JsonSchemaValidatorSupplier supplier = p.get();
				return Stream.ofNullable(supplier);
			}
			catch (Exception e) {
				addException(ex, e);
				return Stream.empty();
			}
		}).flatMap(jsonMapperSupplier -> {
			try {
				return Stream.of(jsonMapperSupplier.get());
			}
			catch (Exception e) {
				addException(ex, e);
				return Stream.empty();
			}
		}).findFirst().orElseThrow(() -> {
			if (ex.get() != null) {
				return ex.get();
			}
			else {
				return new IllegalStateException("No default JsonSchemaValidatorSupplier implementation found");
			}
		});
	}

	private static void addException(AtomicReference<IllegalStateException> ref, Exception toAdd) {
		ref.updateAndGet(existing -> {
			if (existing == null) {
				return new IllegalStateException("Failed to initialize default JsonSchemaValidatorSupplier", toAdd);
			}
			else {
				existing.addSuppressed(toAdd);
				return existing;
			}
		});
	}

}
