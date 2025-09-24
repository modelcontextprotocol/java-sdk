package io.modelcontextprotocol.spec.json.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Test-only Gson-based implementation of McpJsonMapper. This lives under src/test/java so
 * it doesn't affect production code or dependencies.
 */
public final class GsonMcpJsonMapper implements McpJsonMapper {

	private final Gson gson;

	public GsonMcpJsonMapper() {
		this(new GsonBuilder().serializeNulls()
			// Ensure numeric values in untyped (Object) fields preserve integral numbers
			// as Long
			.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.create());
	}

	public GsonMcpJsonMapper(Gson gson) {
		if (gson == null) {
			throw new IllegalArgumentException("Gson must not be null");
		}
		this.gson = gson;
	}

	public Gson getGson() {
		return gson;
	}

	@Override
	public <T> T readValue(String content, Class<T> type) throws IOException {
		try {
			return gson.fromJson(content, type);
		}
		catch (Exception e) {
			throw new IOException("Failed to deserialize JSON", e);
		}
	}

	@Override
	public <T> T readValue(byte[] content, Class<T> type) throws IOException {
		return readValue(new String(content, StandardCharsets.UTF_8), type);
	}

	@Override
	public <T> T readValue(String content, TypeRef<T> type) throws IOException {
		try {
			return gson.fromJson(content, type.getType());
		}
		catch (Exception e) {
			throw new IOException("Failed to deserialize JSON", e);
		}
	}

	@Override
	public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
		return readValue(new String(content, StandardCharsets.UTF_8), type);
	}

	@Override
	public <T> T convertValue(Object fromValue, Class<T> type) {
		String json = gson.toJson(fromValue);
		return gson.fromJson(json, type);
	}

	@Override
	public <T> T convertValue(Object fromValue, TypeRef<T> type) {
		String json = gson.toJson(fromValue);
		return gson.fromJson(json, type.getType());
	}

	@Override
	public String writeValueAsString(Object value) throws IOException {
		try {
			return gson.toJson(value);
		}
		catch (Exception e) {
			throw new IOException("Failed to serialize to JSON", e);
		}
	}

	@Override
	public byte[] writeValueAsBytes(Object value) throws IOException {
		return writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
	}

}
