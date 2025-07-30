package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.DefaultMcpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.server.McpTransportContext;
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
import java.util.List;
import java.util.function.Function;

/**
 * Implementation of a WebFlux based {@link McpStatelessServerTransport}.
 *
 * @author Dariusz Jędrzejczyk
 */
public class WebFluxStatelessServerTransport implements McpStatelessServerTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebFluxStatelessServerTransport.class);

	private final ObjectMapper objectMapper;

	private final String mcpEndpoint;

	private final RouterFunction<?> routerFunction;

	private McpStatelessServerHandler mcpHandler;

	private McpTransportContextExtractor<ServerRequest> contextExtractor;

	private volatile boolean isClosing = false;

	private WebFluxStatelessServerTransport(ObjectMapper objectMapper, String mcpEndpoint,
			McpTransportContextExtractor<ServerRequest> contextExtractor) {
		Assert.notNull(objectMapper, "objectMapper must not be null");
		Assert.notNull(mcpEndpoint, "mcpEndpoint must not be null");
		Assert.notNull(contextExtractor, "contextExtractor must not be null");

		this.objectMapper = objectMapper;
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
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> this.isClosing = true);
	}

	/**
	 * Returns the WebFlux router function that defines the transport's HTTP endpoints.
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
	public RouterFunction<?> getRouterFunction() {
		return this.routerFunction;
	}

	private Mono<ServerResponse> handleGet(ServerRequest request) {
		return ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).build();
	}

	private Mono<ServerResponse> handlePost(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("Server is shutting down");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		List<MediaType> acceptHeaders = request.headers().asHttpHeaders().getAccept();
		if (!(acceptHeaders.contains(MediaType.APPLICATION_JSON)
				&& acceptHeaders.contains(MediaType.TEXT_EVENT_STREAM))) {
			return ServerResponse.badRequest().build();
		}

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

	/**
	 * Create a builder for the server.
	 * @return a fresh {@link Builder} instance.
	 */
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

		private String mcpEndpoint = "/mcp";

		private McpTransportContextExtractor<ServerRequest> contextExtractor = (serverRequest, context) -> context;

		private Builder() {
			// used by a static method
		}

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
		 * Builds a new instance of {@link WebFluxStatelessServerTransport} with the
		 * configured settings.
		 * @return A new WebFluxSseServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebFluxStatelessServerTransport build() {
			Assert.notNull(objectMapper, "ObjectMapper must be set");
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");

			return new WebFluxStatelessServerTransport(objectMapper, mcpEndpoint, contextExtractor);
		}

	}

}
