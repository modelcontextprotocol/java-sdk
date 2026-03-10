/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpResponse;

import io.modelcontextprotocol.spec.McpTransportException;

/**
 * Authorization-related exception for {@link java.net.http.HttpClient}-based client
 * transport. Thrown when the server responds with HTTP 401 or HTTP 403. Wraps the
 * response info for further inspection of the headers and the status code.
 *
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization">MCP
 * Specification: Authorization</a>
 * @author Daniel Garnier-Moiroux
 */
public class McpHttpClientTransportException extends McpTransportException {

	private final HttpResponse.ResponseInfo responseInfo;

	public McpHttpClientTransportException(String message, HttpResponse.ResponseInfo responseInfo) {
		super(message);
		this.responseInfo = responseInfo;
	}

	public HttpResponse.ResponseInfo getResponseInfo() {
		return responseInfo;
	}

}
