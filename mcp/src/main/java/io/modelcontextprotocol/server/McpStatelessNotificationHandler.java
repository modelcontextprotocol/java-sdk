/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import reactor.core.publisher.Mono;

/**
 * Handler for MCP notifications in a stateless server.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpStatelessNotificationHandler {

	/**
	 * Handle to notification and complete once done.
	 * @param transportContext {@link McpTransportContext} associated with the transport
	 * @param params the payload of the MCP notification
	 * @return Mono which completes once the processing is done
	 */
	Mono<Void> handle(McpTransportContext transportContext, Object params);

}
