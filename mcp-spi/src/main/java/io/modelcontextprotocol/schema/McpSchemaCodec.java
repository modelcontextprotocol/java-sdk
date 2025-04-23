/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.schema;

import java.io.IOException;

/**
 * Codec interface for encoding and decoding JSON-RPC messages.
 *
 * @author Aliaksei Darafeyeu
 */
public interface McpSchemaCodec {

	byte[] encode(McpSchema.JSONRPCMessage message);

	String encodeAsString(Object message) throws IOException;

	McpSchema.JSONRPCMessage decode(byte[] json);

	McpSchema.JSONRPCMessage decodeFromString(String jsonText) throws IOException;

	<T> T decodeResult(Object rawResult, McpType<T> type);

	<T> T decodeBytes(byte[] bytes, McpType<T> type);

}
