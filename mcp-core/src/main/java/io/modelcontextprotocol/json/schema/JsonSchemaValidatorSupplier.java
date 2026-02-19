/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.json.schema;

import java.util.function.Supplier;

/**
 * A supplier interface that provides a {@link JsonSchemaValidator} instance.
 * Implementations of this interface are expected to return a new or cached instance of
 * {@link JsonSchemaValidator} when {@link #get()} is invoked.
 *
 * @see JsonSchemaValidator
 * @see Supplier
 */
public interface JsonSchemaValidatorSupplier extends Supplier<JsonSchemaValidator> {

}
