/*
 * Copyright 2025 - 2025 the original author or authors.
 */
package io.modelcontextprotocol.json.schema.jackson;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;

/**
 * A concrete implementation of {@link JsonSchemaValidatorSupplier} that provides a
 * {@link JsonSchemaValidator} instance based on the Jackson library.
 *
 * @see JsonSchemaValidatorSupplier
 * @see JsonSchemaValidator
 */
public class JacksonJsonSchemaValidatorSupplier implements JsonSchemaValidatorSupplier {

	/**
	 * Returns a new instance of {@link JsonSchemaValidator} that uses the Jackson library
	 * for JSON schema validation.
	 * @return A {@link JsonSchemaValidator} instance.
	 */
	@Override
	public JsonSchemaValidator get() {
		return new DefaultJsonSchemaValidator();
	}

}
