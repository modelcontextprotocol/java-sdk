/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

/**
 * A {@code subscriptions/listen} stream ended without a result from the server, so
 * changes may be missed until the caller listens again.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpSubscriptionLostException extends McpClientException {

	public McpSubscriptionLostException(Throwable cause) {
		super("The subscriptions/listen stream ended without a result", cause);
	}

}
