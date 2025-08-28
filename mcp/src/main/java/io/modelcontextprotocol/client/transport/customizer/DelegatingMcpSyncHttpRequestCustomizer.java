/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.customizer;

import io.modelcontextprotocol.util.Assert;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

/**
 * Composable {@link McpSyncHttpRequestCustomizer} that applies multiple customizers, in
 * order.
 *
 * @author Daniel Garnier-Moiroux
 */
public class DelegatingMcpSyncHttpRequestCustomizer implements McpSyncHttpRequestCustomizer {

	private final List<McpSyncHttpRequestCustomizer> delegates;

	public DelegatingMcpSyncHttpRequestCustomizer(List<McpSyncHttpRequestCustomizer> customizers) {
		Assert.notNull(customizers, "Customizers must not be null");
		this.delegates = customizers;
	}

	@Override
	public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body) {
		this.delegates.forEach(delegate -> delegate.customize(builder, method, endpoint, body));
	}

}
