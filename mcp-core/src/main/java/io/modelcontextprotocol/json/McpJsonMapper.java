/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.json;

import java.io.IOException;

/**
 * Abstraction for JSON serialization/deserialization to decouple the SDK from any
 * specific JSON library. A default implementation backed by Jackson is provided in
 * io.modelcontextprotocol.spec.json.jackson.JacksonJsonMapper.
 */
public interface McpJsonMapper {

	/**
	 * Deserialize JSON string into a target type.
	 * @param content JSON as String
	 * @param type target class
	 * @return deserialized instance
	 * @param <T> generic type
	 * @throws IOException on parse errors
	 */
	<T> T readValue(String content, Class<T> type) throws IOException;

	/**
	 * Deserialize JSON bytes into a target type.
	 * @param content JSON as bytes
	 * @param type target class
	 * @return deserialized instance
	 * @param <T> generic type
	 * @throws IOException on parse errors
	 */
	<T> T readValue(byte[] content, Class<T> type) throws IOException;

	/**
	 * Deserialize JSON string into a parameterized target type.
	 * @param content JSON as String
	 * @param type parameterized type reference
	 * @return deserialized instance
	 * @param <T> generic type
	 * @throws IOException on parse errors
	 */
	<T> T readValue(String content, TypeRef<T> type) throws IOException;

	/**
	 * Deserialize JSON bytes into a parameterized target type.
	 * @param content JSON as bytes
	 * @param type parameterized type reference
	 * @return deserialized instance
	 * @param <T> generic type
	 * @throws IOException on parse errors
	 */
	<T> T readValue(byte[] content, TypeRef<T> type) throws IOException;

	/**
	 * Convert a value to a given type, useful for mapping nested JSON structures.
	 * @param fromValue source value
	 * @param type target class
	 * @return converted value
	 * @param <T> generic type
	 */
	<T> T convertValue(Object fromValue, Class<T> type);

	/**
	 * Convert a value to a given parameterized type.
	 * @param fromValue source value
	 * @param type target type reference
	 * @return converted value
	 * @param <T> generic type
	 */
	<T> T convertValue(Object fromValue, TypeRef<T> type);

	/**
	 * Serialize an object to JSON string.
	 * @param value object to serialize
	 * @return JSON as String
	 * @throws IOException on serialization errors
	 */
	String writeValueAsString(Object value) throws IOException;

	/**
	 * Serialize an object to JSON bytes.
	 * @param value object to serialize
	 * @return JSON as bytes
	 * @throws IOException on serialization errors
	 */
	byte[] writeValueAsBytes(Object value) throws IOException;

}
