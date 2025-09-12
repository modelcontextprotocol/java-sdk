package io.modelcontextprotocol.json;

import java.io.IOException;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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

	/**
	 * Resolves the default {@link McpJsonMapper}.
	 * @return The default {@link McpJsonMapper}
	 * @throws IllegalStateException If no {@link McpJsonMapper} implementation exists on
	 * the classpath.
	 */
	static McpJsonMapper createDefault() {
		AtomicReference<IllegalStateException> ex = new AtomicReference<>();
		return ServiceLoader.load(McpJsonMapperSupplier.class).stream().flatMap(p -> {
			try {
				McpJsonMapperSupplier supplier = p.get();
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
				return new IllegalStateException("No default McpJsonMapper implementation found");
			}
		});
	}

	private static void addException(AtomicReference<IllegalStateException> ref, Exception toAdd) {
		ref.updateAndGet(existing -> {
			if (existing == null) {
				return new IllegalStateException("Failed to initialize default McpJsonMapper", toAdd);
			}
			else {
				existing.addSuppressed(toAdd);
				return existing;
			}
		});
	}

}
