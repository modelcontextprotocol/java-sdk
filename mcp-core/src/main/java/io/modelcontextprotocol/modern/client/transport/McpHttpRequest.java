/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.net.URI;
import java.net.http.HttpHeaders;

import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;

/**
 * An HTTP request carrying one MCP request, as the hooks of an HTTP transport see it.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpHttpRequest {

	private final URI uri;

	private final String httpMethod;

	private final HttpHeaders headers;

	private final String mcpMethod;

	private final @Nullable String mcpName;

	private final String body;

	McpHttpRequest(URI uri, String httpMethod, HttpHeaders headers, String mcpMethod, @Nullable String mcpName,
			String body) {
		Assert.notNull(uri, "uri must not be null");
		Assert.hasText(httpMethod, "httpMethod must not be empty");
		Assert.notNull(headers, "headers must not be null");
		Assert.hasText(mcpMethod, "mcpMethod must not be empty");
		Assert.notNull(body, "body must not be null");
		this.uri = uri;
		this.httpMethod = httpMethod;
		this.headers = headers;
		this.mcpMethod = mcpMethod;
		this.mcpName = mcpName;
		this.body = body;
	}

	/**
	 * The server's MCP endpoint.
	 */
	public URI uri() {
		return this.uri;
	}

	/**
	 * The HTTP method.
	 */
	public String httpMethod() {
		return this.httpMethod;
	}

	/**
	 * The request headers: for a {@link McpHttpRequestCustomizer}, those the transport
	 * sets; for a {@link McpHttpAuthorizationErrorHandler}, all headers as sent.
	 */
	public HttpHeaders headers() {
		return this.headers;
	}

	/**
	 * The MCP method, as in {@code Mcp-Method}.
	 */
	public String mcpMethod() {
		return this.mcpMethod;
	}

	/**
	 * The name or URI the request targets, as in {@code Mcp-Name} before encoding, or
	 * {@code null}.
	 */
	public @Nullable String mcpName() {
		return this.mcpName;
	}

	/**
	 * The JSON-RPC request.
	 */
	public String body() {
		return this.body;
	}

	@Override
	public String toString() {
		// The body may hold user data and the headers credentials.
		return "McpHttpRequest[" + this.httpMethod + " " + this.uri + ", " + this.mcpMethod
				+ (this.mcpName != null ? " " + this.mcpName : "") + "]";
	}

}
