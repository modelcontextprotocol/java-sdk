package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpRequest;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

/**
 * Customize {@link HttpRequest.Builder} before sending out SSE or Streamable HTTP
 * transport.
 * <p>
 * When used in a non-blocking context, implementations MUST be non-blocking.
 */
public interface AsyncHttpRequestCustomizer {

	Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method, URI endpoint,
			@Nullable String body);

	AsyncHttpRequestCustomizer NOOP = new Noop();

	/**
	 * Wrap a sync implementation in an async wrapper.
	 * <p>
	 * Do NOT use in a non-blocking context.
	 */
	static AsyncHttpRequestCustomizer fromSync(SyncHttpRequestCustomizer customizer) {
		return (builder, method, uri, body) -> Mono.defer(() -> {
			customizer.customize(builder, method, uri, body);
			return Mono.just(builder);
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
