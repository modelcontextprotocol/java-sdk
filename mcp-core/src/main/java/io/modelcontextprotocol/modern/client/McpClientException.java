/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import io.modelcontextprotocol.modern.McpException;
import org.jspecify.annotations.Nullable;

/**
 * A call failed in the client or on the way to the server, rather than with an error the
 * server answered, which is an {@link McpException}. Exceptions thrown by the transport's
 * hooks reach the caller as they are, not as this type.
 *
 * @author Dariusz Jędrzejczyk
 */
public abstract class McpClientException extends RuntimeException {

	protected McpClientException(String message) {
		super(message);
	}

	protected McpClientException(String message, @Nullable Throwable cause) {
		super(message, cause);
	}

}
