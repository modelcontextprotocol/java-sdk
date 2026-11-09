/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.net.URI;
import java.net.http.HttpHeaders;

/**
 * The server answered with an HTTP error status that carries no JSON-RPC error, or with
 * an authorization error no {@link McpHttpAuthorizationErrorHandler} retried. The request
 * was not processed.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpHttpStatusException extends RuntimeException {

	private final URI uri;

	private final int statusCode;

	private final transient HttpHeaders headers;

	private final String body;

	public McpHttpStatusException(URI uri, int statusCode, HttpHeaders headers, String body) {
		super("HTTP " + statusCode + " from " + uri + (body.isEmpty() ? "" : ": " + abbreviate(body)));
		this.uri = uri;
		this.statusCode = statusCode;
		this.headers = headers;
		this.body = body;
	}

	/** The endpoint that answered. */
	public URI uri() {
		return this.uri;
	}

	/** The HTTP status code. */
	public int statusCode() {
		return this.statusCode;
	}

	/** The response headers. */
	public HttpHeaders headers() {
		return this.headers;
	}

	/** The response body as text, empty if there was none. */
	public String body() {
		return this.body;
	}

	private static String abbreviate(String body) {
		return body.length() > 500 ? body.substring(0, 500) + "..." : body;
	}

}
