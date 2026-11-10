/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.net.http.HttpHeaders;

import io.modelcontextprotocol.util.Assert;

/**
 * The status and headers of an HTTP response, such as a {@code WWW-Authenticate}
 * challenge.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpHttpResponseInfo {

	private final int statusCode;

	private final HttpHeaders headers;

	private McpHttpResponseInfo(int statusCode, HttpHeaders headers) {
		this.statusCode = statusCode;
		this.headers = headers;
	}

	/**
	 * The status and headers of a response.
	 * @param statusCode the HTTP status code
	 * @param headers the response headers
	 */
	public static McpHttpResponseInfo of(int statusCode, HttpHeaders headers) {
		Assert.notNull(headers, "headers must not be null");
		return new McpHttpResponseInfo(statusCode, headers);
	}

	/**
	 * The HTTP status code.
	 */
	public int statusCode() {
		return this.statusCode;
	}

	/**
	 * The response headers.
	 */
	public HttpHeaders headers() {
		return this.headers;
	}

	@Override
	public String toString() {
		// The headers may hold credentials.
		return "McpHttpResponseInfo[" + this.statusCode + "]";
	}

}
