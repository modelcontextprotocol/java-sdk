/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import reactor.core.publisher.Mono;

/**
 * Adds headers to each HTTP request before it is sent, for example an access token for
 * the identity in the context. It is called again for every attempt, including the retry
 * after an authorization error, so a refreshed token is picked up.
 * <p>
 * A transport may call it on any thread. The identity of the call is the {@code context};
 * thread-locals of the thread that started the call are not available.
 *
 * @author Dariusz Jędrzejczyk
 */
@FunctionalInterface
public interface McpHttpRequestCustomizer {

	/**
	 * Customizes one request.
	 * @param request the request about to be sent
	 * @param headers the headers of the request, to modify
	 * @param context the transport context of the call
	 * @return completes once the headers are added; an error fails the request and
	 * reaches the caller as it is
	 */
	Mono<Void> customize(McpHttpRequest request, McpHttpRequestHeaders headers, McpTransportContext context);

	/**
	 * Adapts a synchronous customizer. It runs on the thread that sends the request, so
	 * it must not block.
	 * @param customizer the synchronous customizer
	 * @return the customizer
	 */
	static McpHttpRequestCustomizer fromSync(Sync customizer) {
		return (request, headers, context) -> Mono.fromRunnable(() -> customizer.customize(request, headers, context));
	}

	/**
	 * A customizer that adds headers without waiting for anything.
	 */
	@FunctionalInterface
	interface Sync {

		/**
		 * Customizes one request.
		 * @param request the request about to be sent
		 * @param headers the headers of the request, to modify
		 * @param context the transport context of the call
		 */
		void customize(McpHttpRequest request, McpHttpRequestHeaders headers, McpTransportContext context);

	}

}
