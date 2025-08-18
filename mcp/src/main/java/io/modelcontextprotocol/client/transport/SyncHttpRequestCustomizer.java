/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpRequest;
import reactor.util.annotation.Nullable;

/**
 * Customize {@link HttpRequest.Builder} before executing the request, either in SSE or
 * Streamable HTTP transport.
 *
 * @author Daniel Garnier-Moiroux
 */
public interface SyncHttpRequestCustomizer {

	void customize(HttpRequest.Builder builder, String method, URI endpoint, @Nullable String body);

}
