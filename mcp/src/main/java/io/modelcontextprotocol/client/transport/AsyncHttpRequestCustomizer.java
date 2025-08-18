/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpRequest;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.annotation.Nullable;

/**
 * Customize {@link HttpRequest.Builder} before executing the request, in either SSE or
 * Streamable HTTP transport.
 * <p>
 * When used in a non-blocking context, implementations MUST be non-blocking.
 *
 * @author Daniel Garnier-Moiroux
 */
public interface AsyncHttpRequestCustomizer {

	Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method, URI endpoint,
			@Nullable String body);

	AsyncHttpRequestCustomizer NOOP = new Noop();

	/**
	 * Wrap a sync implementation in an async wrapper.
	 * <p>
	 * Do NOT wrap a blocking implementation for use in a non-blocking context. For a
	 * blocking implementation, consider using {@link Schedulers#boundedElastic()}.
	 */
	static AsyncHttpRequestCustomizer fromSync(SyncHttpRequestCustomizer customizer) {
		return (builder, method, uri, body) -> Mono.fromSupplier(() -> {
			customizer.customize(builder, method, uri, body);
			return builder;
		});
	}

	class Noop implements AsyncHttpRequestCustomizer {

		@Override
		public Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method, URI endpoint,
				String body) {
			return Mono.just(builder);
		}

	}

}
