/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import org.reactivestreams.Publisher;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.util.Assert;

import reactor.core.publisher.Mono;

/**
 * Composable {@link McpAsyncHttpRequestCustomizer} that applies multiple customizers, in
 * order.
 *
 * @author Daniel Garnier-Moiroux
 */
public class DelegatingMcpAsyncHttpRequestCustomizer implements McpAsyncHttpRequestCustomizer {

	private final List<McpAsyncHttpRequestCustomizer> customizers;

	public DelegatingMcpAsyncHttpRequestCustomizer(List<McpAsyncHttpRequestCustomizer> customizers) {
		Assert.notNull(customizers, "Customizers must not be null");
		this.customizers = customizers;
	}

	@Override
	public Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method, URI endpoint,
			String body, McpTransportContext context) {
		var result = Mono.just(builder);
		for (var customizer : this.customizers) {
			result = result.flatMap(b -> Mono.from(customizer.customize(b, method, endpoint, body, context)));
		}
		return result;
	}

}
