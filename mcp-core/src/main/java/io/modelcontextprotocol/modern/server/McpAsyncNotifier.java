/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import reactor.core.publisher.Mono;

/**
 * Emits request-scoped notifications from inside the body of a streaming
 * {@link McpAsyncResponse}.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpAsyncNotifier {

	/**
	 * Send a progress update. A no-op if the request carries no progress token, or if
	 * {@code progress} does not strictly increase over the previous call.
	 */
	Mono<Void> progress(double progress, Double total, String message);

	/** Send an arbitrary request-scoped notification, for use by extensions. */
	Mono<Void> notify(String method, Object params);

}
