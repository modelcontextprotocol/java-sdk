/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;

/**
 * Implementation of a WebMVC based {@link McpStatelessServerTransport}.
 *
 * <p>
 * This is the non-reactive version of
 * {@link io.modelcontextprotocol.server.transport.WebFluxStatelessServerTransport}
 *
 * @author Christian Tzolov
 */
public class WebMvcStatelessServerTransport implements McpStatelessServerTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebMvcStatelessServerTransport.class);

	private final McpJsonMapper jsonMapper;

	private final String mcpEndpoint;

	private final RouterFunction<ServerResponse> routerFunction;

	private McpStatelessServerHandler mcpHandler;

	private McpTransportContextExtractor<ServerRequest> contextExtractor;

	private volatile boolean isClosing = false;

	private WebMvcStatelessServerTransport(McpJsonMapper jsonMapper, String mcpEndpoint,
			McpTransportContextExtractor<ServerRequest> contextExtractor) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(mcpEndpoint, "mcpEndpoint must not be null");
		Assert.notNull(contextExtractor, "contextExtractor must not be null");

		this.jsonMapper = jsonMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.contextExtractor = contextExtractor;
		this.routerFunction = RouterFunctions.route()
			.GET(this.mcpEndpoint, this::handleGet)
			.POST(this.mcpEndpoint, this::handlePost)
			.build();
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		this.mcpHandler = mcpHandler;
	}

	@Override
	public McpStatelessServerHandler getMcpHandler() {
		return mcpHandler;
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> this.isClosing = true);
	}

	/**
	 * Returns the WebMVC router function that defines the transport's HTTP endpoints.
	 * This router function should be integrated into the application's web configuration.
	 *
	 * <p>
	 * The router function defines one endpoint handling two HTTP methods:
	 * <ul>
	 * <li>GET {messageEndpoint} - Unsupported, returns 405 METHOD NOT ALLOWED</li>
	 * <li>POST {messageEndpoint} - For handling client requests and notifications</li>
	 * </ul>
	 * @return The configured {@link RouterFunction} for handling HTTP requests
	 */
	public RouterFunction<ServerResponse> getRouterFunction() {
		return this.routerFunction;
	}

	private ServerResponse handleGet(ServerRequest request) {
		return ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).build();
	}

	private ServerResponse handlePost(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		List<MediaType> acceptHeaders = request.headers().asHttpHeaders().getAccept();
		if (!(acceptHeaders.contains(MediaType.APPLICATION_JSON)
				&& acceptHeaders.contains(MediaType.TEXT_EVENT_STREAM))) {
			return ServerResponse.badRequest().build();
		}

		try {
			String body = request.body(String.class);
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

			if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
				try {
					McpSchema.JSONRPCResponse jsonrpcResponse = this.mcpHandler
						.handleRequest(transportContext, jsonrpcRequest)
						.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
						.block();
					return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(jsonrpcResponse);
				}
				catch (Exception e) {
					logger.error("Failed to handle request: {}", e.getMessage());
					return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
						.body(new McpError("Failed to handle request: " + e.getMessage()));
				}
			}
			else if (message instanceof McpSchema.JSONRPCNotification jsonrpcNotification) {
				try {
					this.mcpHandler.handleNotification(transportContext, jsonrpcNotification)
						.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
						.block();
					return ServerResponse.accepted().build();
				}
				catch (Exception e) {
					logger.error("Failed to handle notification: {}", e.getMessage());
					return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
						.body(new McpError("Failed to handle notification: " + e.getMessage()));
				}
			}
			else {
				return ServerResponse.badRequest()
					.body(new McpError("The server accepts either requests or notifications"));
			}
		}
		catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			return ServerResponse.badRequest().body(new McpError("Invalid message format"));
		}
		catch (Exception e) {
			logger.error("Unexpected error handling message: {}", e.getMessage());
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new McpError("Unexpected error: " + e.getMessage()));
		}
	}

	/**
	 * Create a builder for the server.
	 * @return a fresh {@link Builder} instance.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link WebMvcStatelessServerTransport}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * WebMvcStatelessServerTransport with custom settings.
	 */
	public static class Builder {

		private McpJsonMapper jsonMapper;

		private String mcpEndpoint = "/mcp";

		private McpTransportContextExtractor<ServerRequest> contextExtractor = (
				serverRequest) -> McpTransportContext.EMPTY;

		private Builder() {
			// used by a static method
		}

		/**
		 * Sets the ObjectMapper to use for JSON serialization/deserialization of MCP
		 * messages.
		 * @param jsonMapper The ObjectMapper instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "ObjectMapper must not be null");
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
		public Builder contextExtractor(McpTransportContextExtractor<ServerRequest> contextExtractor) {
			Assert.notNull(contextExtractor, "Context extractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Builds a new instance of {@link WebMvcStatelessServerTransport} with the
		 * configured settings.
		 * @return A new WebMvcStatelessServerTransport instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebMvcStatelessServerTransport build() {
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");
			return new WebMvcStatelessServerTransport(jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper,
					mcpEndpoint, contextExtractor);
		}

	}

}
