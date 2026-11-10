/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.Map;

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

	private McpHttpRequest(Builder builder) {
		this.uri = builder.uri;
		this.httpMethod = builder.httpMethod;
		this.headers = builder.headers;
		this.mcpMethod = builder.mcpMethod;
		this.mcpName = builder.mcpName;
		this.body = builder.body;
	}

	/**
	 * A builder of the HTTP request that carries {@code request}: a {@code POST} to
	 * {@code uri} with {@code body}, and no headers until they are set.
	 * @param uri the server's MCP endpoint
	 * @param request the MCP request, which gives the MCP method and name
	 * @param body the JSON-RPC request as sent
	 */
	public static Builder builder(URI uri, McpTransportRequest request, String body) {
		Assert.notNull(uri, "uri must not be null");
		Assert.notNull(request, "request must not be null");
		Assert.notNull(body, "body must not be null");
		return new Builder(uri, request.method(), request.name(), body);
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

	/**
	 * Builds a {@link McpHttpRequest}.
	 */
	public static final class Builder {

		private final URI uri;

		private final String mcpMethod;

		private final @Nullable String mcpName;

		private final String body;

		private String httpMethod = "POST";

		private HttpHeaders headers = HttpHeaders.of(Map.of(), (name, value) -> true);

		private Builder(URI uri, String mcpMethod, @Nullable String mcpName, String body) {
			this.uri = uri;
			this.mcpMethod = mcpMethod;
			this.mcpName = mcpName;
			this.body = body;
		}

		/**
		 * Sets the HTTP method; {@code POST} by default.
		 */
		public Builder httpMethod(String httpMethod) {
			Assert.hasText(httpMethod, "httpMethod must not be empty");
			this.httpMethod = httpMethod;
			return this;
		}

		/**
		 * Sets the request headers; see {@link McpHttpRequest#headers()}.
		 */
		public Builder headers(HttpHeaders headers) {
			Assert.notNull(headers, "headers must not be null");
			this.headers = headers;
			return this;
		}

		/**
		 * Builds the request.
		 */
		public McpHttpRequest build() {
			return new McpHttpRequest(this);
		}

	}

}
