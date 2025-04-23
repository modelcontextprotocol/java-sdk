package io.modelcontextprotocol.schema;

import java.io.IOException;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.util.Assert;

/**
 * @author Aliaksei Darafeyeu
 */
public class McpJacksonCodec implements McpSchemaCodec {

	private static final Logger logger = LoggerFactory.getLogger(McpJacksonCodec.class);

	private static final TypeReference<HashMap<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
	};

	private final ObjectMapper mapper;

	public McpJacksonCodec() {
		this(new ObjectMapper());
	}

	public McpJacksonCodec(final ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "The ObjectMapper can not be null");
		this.mapper = objectMapper;
		registerMixins();
	}

	private void registerMixins() {
		mapper.registerModule(new GenericEnumModule());
		McpJacksonSchema.MIXINS.forEach(mapper::addMixIn);
	}

	public ObjectMapper getMapper() {
		return mapper;
	}

	/**
	 * Deserializes a JSON string into a JSONRPCMessage object.
	 * @param jsonText The JSON string to deserialize
	 * @return A JSONRPCMessage instance using either the
	 * {@link McpSchema.JSONRPCRequest}, {@link McpSchema.JSONRPCNotification}, or
	 * {@link McpSchema.JSONRPCResponse} classes.
	 * @throws IOException If there's an error during deserialization
	 * @throws IllegalArgumentException If the JSON structure doesn't match any known
	 * message type
	 */
	public McpSchema.JSONRPCMessage decodeFromString(String jsonText) throws IOException {

		logger.debug("Received JSON message: {}", jsonText);

		var map = mapper.readValue(jsonText, MAP_TYPE_REF);

		// Determine message type based on specific JSON structure
		if (map.containsKey("method") && map.containsKey("id")) {
			return mapper.convertValue(map, McpSchema.JSONRPCRequest.class);
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			return mapper.convertValue(map, McpSchema.JSONRPCNotification.class);
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			return mapper.convertValue(map, McpSchema.JSONRPCResponse.class);
		}

		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

	public byte[] encode(McpSchema.JSONRPCMessage message) {
		try {
			return mapper.writeValueAsBytes(message);
		}
		catch (final Exception e) {
			throw new RuntimeException("Failed to serialize JSONRPCMessage", e);
		}
	}

	public String encodeAsString(Object message) throws IOException {
		return mapper.writeValueAsString(message);
	}

	public McpSchema.JSONRPCMessage decode(byte[] bytes) {
		try {
			return mapper.readValue(bytes, McpSchema.JSONRPCMessage.class);
		}
		catch (final Exception e) {
			throw new RuntimeException("Failed to deserialize JSONRPCMessage", e);
		}
	}

	public <T> T decodeResult(Object rawResult, McpType<T> type) {
		return mapper.convertValue(rawResult, mapper.constructType(type.getGenericType()));
	}

	public <T> T decodeBytes(byte[] bytes, McpType<T> type) {
		try {
			return mapper.readValue(bytes, mapper.constructType(type.getGenericType()));
		}
		catch (final Exception e) {
			throw new RuntimeException("Failed to deserialize JSON bytes", e);
		}
	}

}
