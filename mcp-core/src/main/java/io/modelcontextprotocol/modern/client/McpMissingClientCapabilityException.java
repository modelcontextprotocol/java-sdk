/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import org.jspecify.annotations.Nullable;

/**
 * The server answered {@code -32021}: the request needs client capabilities the call
 * didn't declare.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpMissingClientCapabilityException extends McpException {

	private final transient ClientCapabilities requiredCapabilities;

	public McpMissingClientCapabilityException(JSONRPCError error, @Nullable ClientCapabilities requiredCapabilities) {
		super(error);
		this.requiredCapabilities = requiredCapabilities;
	}

	/**
	 * The capabilities the server requires, or {@code null} if its error didn't list
	 * them.
	 */
	@Nullable public ClientCapabilities requiredCapabilities() {
		return this.requiredCapabilities;
	}

}
