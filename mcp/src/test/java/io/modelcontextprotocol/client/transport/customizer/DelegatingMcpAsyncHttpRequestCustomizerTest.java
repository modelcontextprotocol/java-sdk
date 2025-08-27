/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import io.modelcontextprotocol.server.McpTransportContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DelegatingMcpAsyncHttpRequestCustomizer}.
 *
 * @author Daniel Garnier-Moiroux
 */
class DelegatingMcpAsyncHttpRequestCustomizerTest {

	private static final URI TEST_URI = URI.create("https://example.com");

	private final HttpRequest.Builder TEST_BUILDER = HttpRequest.newBuilder(TEST_URI);

	@Test
	void delegates() {
		var mockCustomizer = mock(McpAsyncHttpRequestCustomizer.class);
		when(mockCustomizer.customize(any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> Mono.just(invocation.getArguments()[0]));
		var customizer = new DelegatingMcpAsyncHttpRequestCustomizer(List.of(mockCustomizer));

		var context = McpTransportContext.EMPTY;
		StepVerifier
			.create(customizer.customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}", context))
			.expectNext(TEST_BUILDER)
			.verifyComplete();

		verify(mockCustomizer).customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}", context);
	}

	@Test
	void delegatesInOrder() {
		var customizer = new DelegatingMcpAsyncHttpRequestCustomizer(
				List.of((builder, method, uri, body, ctx) -> Mono.just(builder.copy().header("x-test", "one")),
						(builder, method, uri, body, ctx) -> Mono.just(builder.copy().header("x-test", "two"))));

		var headers = Mono
			.from(customizer.customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}",
					McpTransportContext.EMPTY))
			.map(HttpRequest.Builder::build)
			.map(HttpRequest::headers)
			.flatMapIterable(h -> h.allValues("x-test"));

		StepVerifier.create(headers).expectNext("one").expectNext("two").verifyComplete();
	}

	@Test
	void constructorRequiresNonNull() {
		assertThatThrownBy(() -> new DelegatingMcpAsyncHttpRequestCustomizer(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Customizers must not be null");
	}

}
