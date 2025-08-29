/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.modelcontextprotocol.common.McpTransportContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DelegatingMcpSyncHttpClientRequestCustomizer}.
 *
 * @author Daniel Garnier-Moiroux
 */
class DelegatingMcpSyncHttpClientRequestCustomizerTest {

	private static final URI TEST_URI = URI.create("https://example.com");

	private final HttpRequest.Builder TEST_BUILDER = HttpRequest.newBuilder(TEST_URI);

	@Test
	void delegates() {
		var mockCustomizer = Mockito.mock(McpSyncHttpClientRequestCustomizer.class);
		var customizer = new DelegatingMcpSyncHttpClientRequestCustomizer(List.of(mockCustomizer));

		var context = McpTransportContext.EMPTY;
		customizer.customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}", context);

		verify(mockCustomizer).customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}", context);
	}

	@Test
	void delegatesInOrder() {
		var testHeaderName = "x-test";
		var customizer = new DelegatingMcpSyncHttpClientRequestCustomizer(
				List.of((builder, method, uri, body, ctx) -> builder.header(testHeaderName, "one"),
						(builder, method, uri, body, ctx) -> builder.header(testHeaderName, "two")));

		customizer.customize(TEST_BUILDER, "GET", TEST_URI, null, McpTransportContext.EMPTY);
		var request = TEST_BUILDER.build();

		assertThat(request.headers().allValues(testHeaderName)).containsExactly("one", "two");
	}

	@Test
	void constructorRequiresNonNull() {
		assertThatThrownBy(() -> new DelegatingMcpAsyncHttpClientRequestCustomizer(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Customizers must not be null");
	}

}
