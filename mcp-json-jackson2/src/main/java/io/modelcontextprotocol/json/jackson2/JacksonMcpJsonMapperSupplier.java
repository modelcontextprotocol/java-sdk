/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.json.jackson2;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;

/**
 * A supplier of {@link McpJsonMapper} instances that uses the Jackson library for JSON
 * serialization and deserialization.
 * <p>
 * This implementation provides a {@link McpJsonMapper} backed by a Jackson
 * {@link ObjectMapper} configured for JPMS (Java Platform Module System) compatibility.
 */
public class JacksonMcpJsonMapperSupplier implements McpJsonMapperSupplier {

	/**
	 * Returns a new instance of {@link McpJsonMapper} that uses the Jackson library for
	 * JSON serialization and deserialization.
	 * <p>
	 * The returned {@link McpJsonMapper} is backed by a JPMS-compatible
	 * {@link ObjectMapper} that does not require {@code --add-opens} JVM flags.
	 * @return a new {@link McpJsonMapper} instance
	 */
	@Override
	public McpJsonMapper get() {
		return new JacksonMcpJsonMapper(createJpmsCompatibleMapper());
	}

	/**
	 * Creates an ObjectMapper configured for JPMS compatibility.
	 * <p>
	 * The mapper is configured to:
	 * <ul>
	 * <li>Not call {@code setAccessible()} on constructors/fields, avoiding the need for
	 * {@code --add-opens} flags</li>
	 * <li>Use the {@link ParameterNamesModule} to discover constructor parameter names
	 * from bytecode (requires {@code -parameters} compiler flag, which is already
	 * configured in the parent pom.xml)</li>
	 * </ul>
	 * @return a JPMS-compatible ObjectMapper
	 */
	private static ObjectMapper createJpmsCompatibleMapper() {
		return JsonMapper.builder()
			.disable(MapperFeature.CAN_OVERRIDE_ACCESS_MODIFIERS)
			.addModule(new ParameterNamesModule())
			.build();
	}

}
