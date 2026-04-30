/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.URI;

/**
 * Validate the that message endpoint in the SSE transport is valid. Throws
 * {@link InvalidSseMessageEndpointException} when then endpoint is not valid.
 *
 * @author Daniel Garnier-Moiroux
 */
@FunctionalInterface
public interface SseMessageEndpointValidator {

	/**
	 * Validate the message endpoint coming from an SSE connection. Throws if not valid.
	 * @param sseUri the URI used to establish the SSE connection
	 * @param messageEndpoint the message endpoint from the SSE connection
	 * @throws InvalidSseMessageEndpointException error thrown if the message endpoint is
	 * not valid.
	 */
	void validate(URI sseUri, String messageEndpoint) throws InvalidSseMessageEndpointException;

}
