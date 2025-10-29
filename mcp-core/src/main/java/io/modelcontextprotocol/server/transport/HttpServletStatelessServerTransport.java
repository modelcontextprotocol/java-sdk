/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import io.modelcontextprotocol.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonMapper;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.util.Assert;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * Implementation of an HttpServlet based {@link McpStatelessServerTransport}.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@WebServlet(asyncSupported = true)
public class HttpServletStatelessServerTransport extends HttpServlet implements McpStatelessServerTransport {

	private static final TypeRef<Map<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
	};

	private static final Logger logger = LoggerFactory.getLogger(HttpServletStatelessServerTransport.class);

	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String TEXT_EVENT_STREAM = "text/event-stream";

	public static final String ACCEPT = "Accept";

	public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";

	private final McpJsonMapper jsonMapper;

	private final String mcpEndpoint;

	private volatile boolean isClosing = false;

	private HttpServerMcpStatelessServerTransport<HttpServletRequest> delegate;

	private HttpServletStatelessServerTransport(HttpServerMcpStatelessServerTransport<HttpServletRequest> delegate,
			McpJsonMapper jsonMapper, String mcpEndpoint) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(mcpEndpoint, "mcpEndpoint must not be null");
		this.delegate = delegate;
		this.jsonMapper = jsonMapper;
		this.mcpEndpoint = mcpEndpoint;
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		this.delegate.setMcpHandler(mcpHandler);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> this.isClosing = true);
	}

	/**
	 * Handles GET requests - returns 405 METHOD NOT ALLOWED as stateless transport
	 * doesn't support GET requests.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(mcpEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}

	/**
	 * Handles POST requests for incoming JSON-RPC messages from clients.
	 * @param request The HTTP servlet request containing the JSON-RPC message
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(mcpEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		Map<String, Object> body = jsonMapper.readValue(request.getInputStream().readAllBytes(), MAP_TYPE_REF);
		HttpJsonRpcResponse jsonRpcResponse = delegate.handlePost(request, body).block();
		respond(jsonRpcResponse, response);
	}

	/**
	 * Sends an error response to the client.
	 * @param jsonRpcResponse The HTTP JSON RPC response
	 * @param response The HTTP servlet response
	 * @throws IOException If an I/O error occurs
	 */
	private void respond(HttpJsonRpcResponse jsonRpcResponse, HttpServletResponse response) throws IOException {
		response.setContentType(APPLICATION_JSON);
		response.setCharacterEncoding(UTF_8);
		if (jsonRpcResponse != null) {
			response.setStatus(jsonRpcResponse.statusCode());
			String jsonResponseText = jsonMapper.writeValueAsString(jsonRpcResponse.body());
			PrintWriter writer = response.getWriter();
			writer.write(jsonResponseText);
			writer.flush();
		}
		else {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Cleans up resources when the servlet is being destroyed.
	 * <p>
	 * This method ensures a graceful shutdown before calling the parent's destroy method.
	 */
	@Override
	public void destroy() {
		closeGracefully().block();
		super.destroy();
	}

	/**
	 * Create a builder for the server.
	 * @return a fresh {@link Builder} instance.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link HttpServletStatelessServerTransport}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * HttpServletStatelessServerTransport with custom settings.
	 */
	public static class Builder {

		private McpJsonMapper jsonMapper;

		private String mcpEndpoint = "/mcp";

		private McpTransportContextExtractor<HttpServletRequest> contextExtractor = (
				serverRequest) -> McpTransportContext.EMPTY;

		private Builder() {
			// used by a static method
		}

		/**
		 * Sets the JsonMapper to use for JSON serialization/deserialization of MCP
		 * messages.
		 * @param jsonMapper The JsonMapper instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the endpoint URI where clients should send their JSON-RPC messages.
		 * @param messageEndpoint The message endpoint URI. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if messageEndpoint is null
		 */
		public Builder messageEndpoint(String messageEndpoint) {
			Assert.notNull(messageEndpoint, "Message endpoint must not be null");
			this.mcpEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the context extractor that allows providing the MCP feature
		 * implementations to inspect HTTP transport level metadata that was present at
		 * HTTP request processing time. This allows to extract custom headers and other
		 * useful data for use during execution later on in the process.
		 * @param contextExtractor The contextExtractor to fill in a
		 * {@link McpTransportContext}.
		 * @return this builder instance
		 * @throws IllegalArgumentException if contextExtractor is null
		 */
		public Builder contextExtractor(McpTransportContextExtractor<HttpServletRequest> contextExtractor) {
			Assert.notNull(contextExtractor, "Context extractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Builds a new instance of {@link HttpServletStatelessServerTransport} with the
		 * configured settings.
		 * @return A new HttpServletStatelessServerTransport instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public HttpServletStatelessServerTransport build() {
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");
			return new HttpServletStatelessServerTransport(
					new HttpServerMcpStatelessServerTransport<>(contextExtractor),
					jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper, mcpEndpoint);
		}

	}

}
