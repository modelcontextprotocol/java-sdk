/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.MissingRequiredClientCapabilityData;
import io.modelcontextprotocol.util.Assert;

/**
 * A request failure answered with its JSON-RPC {@link #error() error}, sent to the client
 * as given. Any other exception thrown by a handler is answered with an internal error
 * that reveals nothing.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpException extends RuntimeException {

	private final JSONRPCError error;

	public McpException(JSONRPCError error) {
		super(requireError(error).message());
		this.error = error;
	}

	public McpException(int code, String message) {
		this(new JSONRPCError(code, message));
	}

	public McpException(int code, String message, Object data) {
		this(new JSONRPCError(code, message, data));
	}

	public static McpException invalidParams(String message) {
		return new McpException(ErrorCodes.INVALID_PARAMS, message);
	}

	public static McpException invalidParams(String message, Object data) {
		return new McpException(ErrorCodes.INVALID_PARAMS, message, data);
	}

	public static McpException missingClientCapability(ClientCapabilities required) {
		return new McpException(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, "Missing required client capability",
				new MissingRequiredClientCapabilityData(required));
	}

	public JSONRPCError error() {
		return this.error;
	}

	private static JSONRPCError requireError(JSONRPCError error) {
		Assert.notNull(error, "error must not be null");
		return error;
	}

}
