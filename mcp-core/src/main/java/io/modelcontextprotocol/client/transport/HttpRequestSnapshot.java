/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;

/**
 * Captures information about an HTTP request. We use this instead of passing the plain
 * {@link HttpRequest} object because we want to avoid retaining a reference to the
 * request's {@link BodyPublisher}.
 *
 * @param requestUri the HTTP request URI
 * @param method the HTTP method
 * @param headers the HTTP request headers
 * @author Daniel Garnier-Moiroux
 */
public record HttpRequestSnapshot(URI requestUri, String method, HttpHeaders headers) {
}
