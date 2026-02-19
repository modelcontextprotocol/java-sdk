/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.json.jackson;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;

/**
 * A supplier of {@link McpJsonMapper} instances that uses the Jackson library for JSON
 * serialization and deserialization.
 * <p>
 * This implementation provides a {@link McpJsonMapper} backed by a Jackson
 * {@link com.fasterxml.jackson.databind.ObjectMapper}.
 *
 * @deprecated since 18.0.0, use
 * {@link io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier} instead.
 * Will be removed in 19.0.0.
 */
@Deprecated(forRemoval = true, since = "18.0.0")
public class JacksonMcpJsonMapperSupplier implements McpJsonMapperSupplier {

	/**
	 * Returns a new instance of {@link McpJsonMapper} that uses the Jackson library for
	 * JSON serialization and deserialization.
	 * <p>
	 * The returned {@link McpJsonMapper} is backed by a new instance of
	 * {@link com.fasterxml.jackson.databind.ObjectMapper}.
	 * @return a new {@link McpJsonMapper} instance
	 */
	@Override
	public McpJsonMapper get() {
		return new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper());
	}

}
