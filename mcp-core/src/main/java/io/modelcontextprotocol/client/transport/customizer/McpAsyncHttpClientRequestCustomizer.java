/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;
import java.net.http.HttpRequest;

import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.common.McpTransportContext;
import org.reactivestreams.Publisher;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Customize {@link HttpRequest.Builder} before executing the request, in either SSE or
 * Streamable HTTP transport.
 * <p>
 * When used in a non-blocking context, implementations MUST be non-blocking.
 * <p>
 * The {@link McpTransportContext} handed to {@code customize} is read from the Reactor
 * context, under {@link McpTransportContext#KEY}, and is
 * {@link McpTransportContext#EMPTY} when the caller wrote nothing there. Write it once
 * where the reactive chain starts, with
 * {@code contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))}, rather than at
 * every call site. With a synchronous client, configure
 * {@link SyncSpec#transportContextProvider} instead.
 *
 * @author Daniel Garnier-Moiroux
 */
public interface McpAsyncHttpClientRequestCustomizer {

	Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method, URI endpoint,
			@Nullable String body, McpTransportContext context);

	McpAsyncHttpClientRequestCustomizer NOOP = new Noop();

	/**
	 * Wrap a sync implementation in an async wrapper.
	 * <p>
	 * Do NOT wrap a blocking implementation for use in a non-blocking context. For a
	 * blocking implementation, consider using {@link Schedulers#boundedElastic()}.
	 */
	static McpAsyncHttpClientRequestCustomizer fromSync(McpSyncHttpClientRequestCustomizer customizer) {
		return (builder, method, uri, body, context) -> Mono.fromSupplier(() -> {
			customizer.customize(builder, method, uri, body, context);
			return builder;
		});
	}

	class Noop implements McpAsyncHttpClientRequestCustomizer {

		@Override
		public Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method, URI endpoint,
				String body, McpTransportContext context) {
			return Mono.just(builder);
		}

	}

}
