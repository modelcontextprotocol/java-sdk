/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

/**
 * Exception thrown when the {@code message} endpoint returned from the SSE connection is
 * not valid.
 *
 * @author Daniel Garnier-Moiroux
 * @deprecated This exception is part of the deprecated SSE transport.
 * @see HttpClientSseClientTransport
 */
@Deprecated
public class InvalidSseMessageEndpointException extends Exception {

	private final String messageEndpoint;

	public InvalidSseMessageEndpointException(String message, String messageEndpoint) {
		super(message);
		this.messageEndpoint = messageEndpoint;
	}

	public String getMessageEndpoint() {
		return messageEndpoint;
	}

}
