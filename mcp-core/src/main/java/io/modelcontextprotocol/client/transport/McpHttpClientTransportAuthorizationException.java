/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpResponse;

import io.modelcontextprotocol.spec.McpTransportException;

/**
 * Thrown when the MCP server responds with an authorization error (HTTP 401 or HTTP 403).
 * Subclass of {@link McpTransportException} for targeted retry handling in
 * {@link HttpClientStreamableHttpTransport}.
 *
 * @author Daniel Garnier-Moiroux
 */
public class McpHttpClientTransportAuthorizationException extends McpTransportException {

	private final HttpResponse.ResponseInfo responseInfo;

	public McpHttpClientTransportAuthorizationException(String message, HttpResponse.ResponseInfo responseInfo) {
		super(message);
		this.responseInfo = responseInfo;
	}

	public HttpResponse.ResponseInfo getResponseInfo() {
		return responseInfo;
	}

}
