/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reacts to a {@code 401} or {@code 403} answer, for example by obtaining a token with
 * more scopes, and decides whether the request is sent again. A retried request runs the
 * {@link McpHttpRequestCustomizer}s again.
 * <p>
 * The handler is asked only while the request has been retried fewer than
 * {@link #maxRetries()} times. When it doesn't ask for a retry, the request fails with
 * {@link McpHttpStatusException}. The time it takes doesn't count towards the round
 * timeout.
 *
 * @author Dariusz Jędrzejczyk
 */
@FunctionalInterface
public interface McpHttpAuthorizationErrorHandler {

	/**
	 * Handles an authorization error.
	 * @param request the request as it was sent
	 * @param response the status and headers of the answer, including
	 * {@code WWW-Authenticate}
	 * @param context the transport context of the call
	 * @return {@code true} to send the request again; an error reaches the caller as it
	 * is
	 */
	Mono<Boolean> handle(McpHttpRequest request, McpHttpResponseInfo response, McpTransportContext context);

	/**
	 * How many times this handler may have a request sent again.
	 * @return the maximum number of retries, {@code 1} by default
	 */
	default int maxRetries() {
		return 1;
	}

	/**
	 * Adapts a synchronous handler. It runs on {@link Schedulers#boundedElastic()}, so it
	 * may block.
	 * @param handler the synchronous handler
	 * @return the handler
	 */
	static McpHttpAuthorizationErrorHandler fromSync(Sync handler) {
		return new McpHttpAuthorizationErrorHandler() {
			@Override
			public Mono<Boolean> handle(McpHttpRequest request, McpHttpResponseInfo response,
					McpTransportContext context) {
				return Mono.fromCallable(() -> handler.handle(request, response, context))
					.subscribeOn(Schedulers.boundedElastic());
			}

			@Override
			public int maxRetries() {
				return handler.maxRetries();
			}
		};
	}

	/**
	 * A handler that may block.
	 */
	@FunctionalInterface
	interface Sync {

		/**
		 * Handles an authorization error.
		 * @param request the request as it was sent
		 * @param response the status and headers of the answer
		 * @param context the transport context of the call
		 * @return {@code true} to send the request again
		 */
		boolean handle(McpHttpRequest request, McpHttpResponseInfo response, McpTransportContext context);

		/**
		 * How many times this handler may have a request sent again.
		 * @return the maximum number of retries, {@code 1} by default
		 */
		default int maxRetries() {
			return 1;
		}

	}

}
