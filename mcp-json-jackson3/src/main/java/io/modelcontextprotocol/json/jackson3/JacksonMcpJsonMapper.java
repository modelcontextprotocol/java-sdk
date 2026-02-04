/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.json.jackson3;

import java.io.IOException;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson-based implementation of JsonMapper. Wraps a Jackson JsonMapper but keeps the
 * SDK decoupled from Jackson at the API level.
 */
public final class JacksonMcpJsonMapper implements McpJsonMapper {

	private final JsonMapper jsonMapper;

	/**
	 * Constructs a new JacksonMcpJsonMapper instance with the given JsonMapper.
	 * @param jsonMapper the JsonMapper to be used for JSON serialization and
	 * deserialization. Must not be null.
	 * @throws IllegalArgumentException if the provided JsonMapper is null.
	 */
	public JacksonMcpJsonMapper(JsonMapper jsonMapper) {
		if (jsonMapper == null) {
			throw new IllegalArgumentException("JsonMapper must not be null");
		}
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Returns the underlying Jackson {@link JsonMapper} used for JSON serialization and
	 * deserialization.
	 * @return the JsonMapper instance
	 */
	public JsonMapper getJsonMapper() {
		return jsonMapper;
	}

	@Override
	public <T> T readValue(String content, Class<T> type) throws IOException {
		try {
			return jsonMapper.readValue(content, type);
		}
		catch (JacksonException ex) {
			throw new IOException("Failed to read value", ex);
		}
	}

	@Override
	public <T> T readValue(byte[] content, Class<T> type) throws IOException {
		try {
			return jsonMapper.readValue(content, type);
		}
		catch (JacksonException ex) {
			throw new IOException("Failed to read value", ex);
		}
	}

	@Override
	public <T> T readValue(String content, TypeRef<T> type) throws IOException {
		JavaType javaType = jsonMapper.getTypeFactory().constructType(type.getType());
		try {
			return jsonMapper.readValue(content, javaType);
		}
		catch (JacksonException ex) {
			throw new IOException("Failed to read value", ex);
		}
	}

	@Override
	public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
		JavaType javaType = jsonMapper.getTypeFactory().constructType(type.getType());
		try {
			return jsonMapper.readValue(content, javaType);
		}
		catch (JacksonException ex) {
			throw new IOException("Failed to read value", ex);
		}
	}

	@Override
	public <T> T convertValue(Object fromValue, Class<T> type) {
		return jsonMapper.convertValue(fromValue, type);
	}

	@Override
	public <T> T convertValue(Object fromValue, TypeRef<T> type) {
		JavaType javaType = jsonMapper.getTypeFactory().constructType(type.getType());
		return jsonMapper.convertValue(fromValue, javaType);
	}

	@Override
	public String writeValueAsString(Object value) throws IOException {
		try {
			return jsonMapper.writeValueAsString(value);
		}
		catch (JacksonException ex) {
			throw new IOException("Failed to write value as string", ex);
		}
	}

	@Override
	public byte[] writeValueAsBytes(Object value) throws IOException {
		try {
			return jsonMapper.writeValueAsBytes(value);
		}
		catch (JacksonException ex) {
			throw new IOException("Failed to write value as bytes", ex);
		}
	}

}
