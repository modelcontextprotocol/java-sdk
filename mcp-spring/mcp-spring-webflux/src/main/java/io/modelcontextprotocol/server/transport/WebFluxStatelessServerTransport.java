package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.DefaultMcpTransportContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.McpTransportContext;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.function.Function;

public class WebFluxStatelessServerTransport implements McpStatelessServerTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebFluxStatelessServerTransport.class);

	public static final String DEFAULT_BASE_URL = "";

	private final ObjectMapper objectMapper;

	private final String baseUrl;

	private final String mcpEndpoint;

	private final RouterFunction<?> routerFunction;

	private McpStatelessServerHandler mcpHandler;

	// TODO: add means to specify this
	private Function<ServerRequest, McpTransportContext> contextExtractor = req -> new DefaultMcpTransportContext();

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	/**
	 * Constructs a new WebFlux SSE server transport provider instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param baseUrl webflux message base path
	 * @param mcpEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebFluxStatelessServerTransport(ObjectMapper objectMapper, String baseUrl, String mcpEndpoint) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(baseUrl, "Message base path must not be null");
		Assert.notNull(mcpEndpoint, "Message endpoint must not be null");

		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.mcpEndpoint = mcpEndpoint;
		this.routerFunction = RouterFunctions.route()
			.GET(this.mcpEndpoint, this::handleGet)
			.POST(this.mcpEndpoint, this::handlePost)
			.build();
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		this.mcpHandler = mcpHandler;
	}

	// FIXME: This javadoc makes claims about using isClosing flag but it's not
	// actually
	// doing that.
	/**
	 * Initiates a graceful shutdown of all the sessions. This method ensures all active
	 * sessions are properly closed and cleaned up.
	 *
	 * <p>
	 * The shutdown process:
	 * <ul>
	 * <li>Marks the transport as closing to prevent new connections</li>
	 * <li>Closes each active session</li>
	 * <li>Removes closed sessions from the sessions map</li>
	 * <li>Times out after 5 seconds if shutdown takes too long</li>
	 * </ul>
	 * @return A Mono that completes when all sessions have been closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.empty();
	}

	/**
	 * Returns the WebFlux router function that defines the transport's HTTP endpoints.
	 * This router function should be integrated into the application's web configuration.
	 *
	 * <p>
	 * The router function defines two endpoints:
	 * <ul>
	 * <li>GET {sseEndpoint} - For establishing SSE connections</li>
	 * <li>POST {messageEndpoint} - For receiving client messages</li>
	 * </ul>
	 * @return The configured {@link RouterFunction} for handling HTTP requests
	 */
	public RouterFunction<?> getRouterFunction() {
		return this.routerFunction;
	}

	/**
	 * Handles GET requests from clients.
	 * @param request The incoming server request
	 * @return A Mono which emits a response informing the client that listening stream is
	 * unavailable
	 */
	private Mono<ServerResponse> handleGet(ServerRequest request) {
		return ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).build();
	}

	/**
	 * Handles incoming JSON-RPC messages from clients. Deserializes the message and
	 * processes it through the configured message handler.
	 *
	 * <p>
	 * The handler:
	 * <ul>
	 * <li>Deserializes the incoming JSON-RPC message</li>
	 * <li>Passes it through the message handler chain</li>
	 * <li>Returns appropriate HTTP responses based on processing results</li>
	 * <li>Handles various error conditions with appropriate error responses</li>
	 * </ul>
	 * @param request The incoming server request containing the JSON-RPC message
	 * @return A Mono emitting the response indicating the message processing result
	 */
	private Mono<ServerResponse> handlePost(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("Server is shutting down");
		}

		McpTransportContext transportContext = this.contextExtractor.apply(request);

		return request.bodyToMono(String.class).<ServerResponse>flatMap(body -> {
			try {
				McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

				if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
					return this.mcpHandler.handleRequest(transportContext, jsonrpcRequest)
						.flatMap(jsonrpcResponse -> ServerResponse.ok()
							.contentType(MediaType.APPLICATION_JSON)
							.bodyValue(jsonrpcResponse));
				}
				else if (message instanceof McpSchema.JSONRPCNotification jsonrpcNotification) {
					return this.mcpHandler.handleNotification(transportContext, jsonrpcNotification)
						.then(ServerResponse.accepted().build());
				}
				else {
					return ServerResponse.badRequest()
						.bodyValue(new McpError("The server accepts either requests or notifications"));
				}
			}
			catch (IllegalArgumentException | IOException e) {
				logger.error("Failed to deserialize message: {}", e.getMessage());
				return ServerResponse.badRequest().bodyValue(new McpError("Invalid message format"));
			}
		}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link WebFluxStatelessServerTransport}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * WebFluxSseServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private ObjectMapper objectMapper;

		private String baseUrl = DEFAULT_BASE_URL;

		private String mcpEndpoint = "/mcp";

		/**
		 * Sets the ObjectMapper to use for JSON serialization/deserialization of MCP
		 * messages.
		 * @param objectMapper The ObjectMapper instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if objectMapper is null
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Sets the project basePath as endpoint prefix where clients should send their
		 * JSON-RPC messages
		 * @param baseUrl the message basePath . Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if basePath is null
		 */
		public Builder basePath(String baseUrl) {
			Assert.notNull(baseUrl, "basePath must not be null");
			this.baseUrl = baseUrl;
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
		 * Builds a new instance of {@link WebFluxStatelessServerTransport} with the
		 * configured settings.
		 * @return A new WebFluxSseServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebFluxStatelessServerTransport build() {
			Assert.notNull(objectMapper, "ObjectMapper must be set");
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");

			return new WebFluxStatelessServerTransport(objectMapper, baseUrl, mcpEndpoint);
		}

	}

}
