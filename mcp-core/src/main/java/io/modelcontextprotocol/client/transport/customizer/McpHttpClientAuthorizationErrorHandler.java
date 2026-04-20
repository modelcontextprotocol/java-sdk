/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.customizer;

import java.net.http.HttpResponse;

import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.common.McpTransportContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Handle security-related errors in HTTP-client based transports. This class handles MCP
 * server responses with status code 401 and 403.
 *
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization">MCP
 * Specification: Authorization</a>
 * @author Daniel Garnier-Moiroux
 */
public interface McpHttpClientAuthorizationErrorHandler {

	/**
	 * Handle authorization error (HTTP 401 or 403), and signal whether the HTTP request
	 * should be retried or not. If the publisher returns true, the original transport
	 * method (connect, sendMessage) will be replayed with the original arguments.
	 * Otherwise, the transport will throw an
	 * {@link McpHttpClientTransportAuthorizationException}, indicating the error status.
	 * <p>
	 * If the returned {@link Publisher} errors, the error will be propagated to the
	 * calling method, to be handled by the caller.
	 * <p>
	 * The number of retries is bounded by {@link #maxRetries()}.
	 * @param responseInfo the HTTP response information
	 * @param context the MCP client transport context
	 * @return {@link Publisher} emitting true if the original request should be replayed,
	 * false otherwise.
	 */
	Publisher<Boolean> handle(HttpResponse.ResponseInfo responseInfo, McpTransportContext context);

	/**
	 * Maximum number of authorization error retries the transport will attempt. When the
	 * handler signals a retry via {@link #handle}, the transport will replay the original
	 * request at most this many times. If the authorization error persists after
	 * exhausting all retries, the transport will propagate the
	 * {@link McpHttpClientTransportAuthorizationException}.
	 * <p>
	 * Defaults to {@code 1}.
	 * @return the maximum number of retries
	 */
	default int maxRetries() {
		return 1;
	}

	/**
	 * A no-op handler, used in the default use-case.
	 */
	McpHttpClientAuthorizationErrorHandler NOOP = new Noop();

	/**
	 * Create a {@link McpHttpClientAuthorizationErrorHandler} from a synchronous handler.
	 * Will be subscribed on {@link Schedulers#boundedElastic()}. The handler may be
	 * blocking.
	 * @param handler the synchronous handler
	 * @return an async handler
	 */
	static McpHttpClientAuthorizationErrorHandler fromSync(Sync handler) {
		return (info, context) -> Mono.fromCallable(() -> handler.handle(info, context))
			.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * Synchronous authorization error handler.
	 */
	interface Sync {

		/**
		 * Handle authorization error (HTTP 401 or 403), and signal whether the HTTP
		 * request should be retried or not. If the return value is true, the original
		 * transport method (connect, sendMessage) will be replayed with the original
		 * arguments. Otherwise, the transport will throw an
		 * {@link McpHttpClientTransportAuthorizationException}, indicating the error
		 * status.
		 * @param responseInfo the HTTP response information
		 * @param context the MCP client transport context
		 * @return true if the original request should be replayed, false otherwise.
		 */
		boolean handle(HttpResponse.ResponseInfo responseInfo, McpTransportContext context);

	}

	class Noop implements McpHttpClientAuthorizationErrorHandler {

		@Override
		public Publisher<Boolean> handle(HttpResponse.ResponseInfo responseInfo, McpTransportContext context) {
			return Mono.just(false);
		}

	}

}
