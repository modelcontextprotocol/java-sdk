/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.customizer;

import java.net.http.HttpResponse;

import io.modelcontextprotocol.client.transport.McpHttpClientTransportException;
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
	 * Otherwise, the transport will throw an {@link McpHttpClientTransportException},
	 * indicating the error status.
	 * <p>
	 * If the returned {@link Publisher} errors, the error will be propagated to the
	 * calling method, to be handled by the caller.
	 * <p>
	 * The caller is responsible for bounding the number of retries.
	 * @param responseInfo the HTTP response information
	 * @param context the MCP client transport context
	 * @return {@link Publisher} emitting true if the original request should be replayed,
	 * false otherwise.
	 */
	Publisher<Boolean> handle(HttpResponse.ResponseInfo responseInfo, McpTransportContext context);

	/**
	 * A no-op handler, used in the default use-case.
	 */
	McpHttpClientAuthorizationErrorHandler NOOP = new Noop();

	/**
	 * Handle authorization error (HTTP 401 or 403), and optionally retry the HTTP
	 * request, or trigger a transport error. To retry, use the {@code retryAction}
	 * publisher. To emit the default transport error, use the {@code defaultError}
	 * publisher.
	 * <p>
	 * Optionally, the returned {@link Publisher} may error to trigger an out-of-band
	 * action. In that case, the error will be propagated to the calling method, to be
	 * handled by the caller.
	 * <p>
	 * Defaults to {@link #handle(HttpResponse.ResponseInfo, McpTransportContext)}, and
	 * uses the boolean from the return value to decide whether it should retry the
	 * request.
	 * @param responseInfo the HTTP response information
	 * @param context the MCP client transport context
	 * @param retryAction handler to retry the original request
	 * @param defaultError handler to emit an error
	 * @return a {@link Publisher} to signal either an error or a retry
	 */
	default Publisher<Void> onAuthorizationError(HttpResponse.ResponseInfo responseInfo, McpTransportContext context,
			Publisher<Void> retryAction, Publisher<Void> defaultError) {
		return Mono.from(this.handle(responseInfo, context))
			.switchIfEmpty(Mono.just(false))
			.flatMap(shouldRetry -> shouldRetry != null && shouldRetry ? Mono.from(retryAction)
					: Mono.from(defaultError));
	}

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
		 * {@link McpHttpClientTransportException}, indicating the error status.
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
