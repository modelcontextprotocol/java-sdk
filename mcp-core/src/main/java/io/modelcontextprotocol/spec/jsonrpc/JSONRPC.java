/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.jsonrpc;

import java.io.IOException;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * @author Christian Tzolov
 */

public class JSONRPC {

	public static final String JSONRPC_VERSION = "2.0";

	/**
	 * Deserializes a JSON string into a JSONRPCMessage object.
	 * @param jsonMapper The JsonMapper instance to use for deserialization
	 * @param jsonText The JSON string to deserialize
	 * @return A JSONRPCMessage instance using either the {@link JSONRPCRequest},
	 * {@link JSONRPCNotification}, or {@link JSONRPCResponse} classes.
	 * @throws IOException If there's an error during deserialization
	 * @throws IllegalArgumentException If the JSON structure doesn't match any known
	 * message type
	 */
	public static JSONRPCMessage deserializeJsonRpcMessage(McpJsonMapper jsonMapper, String jsonText)
			throws IOException {

		McpSchema.logger.debug("Received JSON message: {}", jsonText);

		var map = jsonMapper.readValue(jsonText, McpSchema.MAP_TYPE_REF);

		// Determine message type based on specific JSON structure
		if (map.containsKey("method") && map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCRequest.class);
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCNotification.class);
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			return jsonMapper.convertValue(map, JSONRPCResponse.class);
		}

		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

}
