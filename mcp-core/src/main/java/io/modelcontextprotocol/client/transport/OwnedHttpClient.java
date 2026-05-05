/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * Tracks a transport-owned {@link HttpClient} so the transport can release its strong
 * reference on close without changing the public builder API.
 */
final class OwnedHttpClient {

	private final AtomicReference<HttpClient> clientRef;

	private OwnedHttpClient(HttpClient httpClient) {
		Assert.notNull(httpClient, "httpClient must not be null");
		this.clientRef = new AtomicReference<>(httpClient);
	}

	static OwnedHttpClient create(HttpClient httpClient) {
		return new OwnedHttpClient(httpClient);
	}

	/**
	 * Return the currently owned client, or fail fast after the transport released it.
	 */
	HttpClient currentClientOrThrow() {
		HttpClient client = this.clientRef.get();
		if (client == null) {
			throw new McpTransportException("Transport is closed and no longer owns an HttpClient");
		}
		return client;
	}

	Mono<Void> releaseAfterClose() {
		return Mono.fromRunnable(() -> this.clientRef.set(null));
	}

}
