package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.DefaultMcpTransportContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpTransportContext;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class WebFluxStreamableServerTransportProvider implements McpStreamableServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(WebFluxStreamableServerTransportProvider.class);

	public static final String MESSAGE_EVENT_TYPE = "message";

	public static final String DEFAULT_BASE_URL = "";

	private final ObjectMapper objectMapper;

	private final String baseUrl;

	private final String mcpEndpoint;

	private final boolean disallowDelete;

	private final RouterFunction<?> routerFunction;

	private McpStreamableServerSession.Factory sessionFactory;

	private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

	// TODO: add means to specify this
	private Function<ServerRequest, McpTransportContext> contextExtractor = req -> new DefaultMcpTransportContext();

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	/**
	 * Constructs a new WebFlux SSE server transport provider instance with the default
	 * SSE endpoint.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param mcpEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebFluxStreamableServerTransportProvider(ObjectMapper objectMapper, String mcpEndpoint) {
		this(objectMapper, DEFAULT_BASE_URL, mcpEndpoint, false);
	}

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
	public WebFluxStreamableServerTransportProvider(ObjectMapper objectMapper, String baseUrl, String mcpEndpoint,
			boolean disallowDelete) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(baseUrl, "Message base path must not be null");
		Assert.notNull(mcpEndpoint, "Message endpoint must not be null");

		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.mcpEndpoint = mcpEndpoint;
		this.disallowDelete = disallowDelete;
		this.routerFunction = RouterFunctions.route()
			.GET(this.mcpEndpoint, this::handleGet)
			.POST(this.mcpEndpoint, this::handlePost)
			.DELETE(this.mcpEndpoint, this::handleDelete)
			.build();
	}

	@Override
	public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a JSON-RPC message to all connected clients through their SSE
	 * connections. The message is serialized to JSON and sent as a server-sent event to
	 * each active session.
	 *
	 * <p>
	 * The method:
	 * <ul>
	 * <li>Serializes the message to JSON</li>
	 * <li>Creates a server-sent event with the message data</li>
	 * <li>Attempts to send the event to all active sessions</li>
	 * <li>Tracks and reports any delivery failures</li>
	 * </ul>
	 * @param method The JSON-RPC method to send to clients
	 * @param params The method parameters to send to clients
	 * @return A Mono that completes when the message has been sent to all sessions, or
	 * errors if any session fails to receive the message
	 */
	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values())
			.flatMap(session -> session.sendNotification(method, params)
				.doOnError(
						e -> logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage()))
				.onErrorComplete())
			.then();
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
		return Flux.fromIterable(sessions.values())
			.doFirst(() -> logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size()))
			.flatMap(McpStreamableServerSession::closeGracefully)
			.then();
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
	 * Handles new SSE connection requests from clients. Creates a new session for each
	 * connection and sets up the SSE event stream.
	 * @param request The incoming server request
	 * @return A Mono which emits a response with the SSE event stream
	 */
	private Mono<ServerResponse> handleGet(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("Server is shutting down");
		}

		McpTransportContext transportContext = this.contextExtractor.apply(request);

		return Mono.defer(() -> {
			if (!request.headers().asHttpHeaders().containsKey("mcp-session-id")) {
				return ServerResponse.badRequest().build(); // TODO: say we need a session
															// id
			}

			String sessionId = request.headers().asHttpHeaders().getFirst("mcp-session-id");

			McpStreamableServerSession session = this.sessions.get(sessionId);

			if (session == null) {
				return ServerResponse.notFound().build();
			}

			if (request.headers().asHttpHeaders().containsKey("mcp-last-id")) {
				String lastId = request.headers().asHttpHeaders().getFirst("mcp-last-id");
				return ServerResponse.ok()
					.contentType(MediaType.TEXT_EVENT_STREAM)
					.body(session.replay(lastId), ServerSentEvent.class);
			}

			return ServerResponse.ok()
				.contentType(MediaType.TEXT_EVENT_STREAM)
				.body(Flux.<ServerSentEvent<?>>create(sink -> {
					WebFluxStreamableMcpSessionTransport sessionTransport = new WebFluxStreamableMcpSessionTransport(
							sink);
					McpStreamableServerSession.McpStreamableServerSessionStream listeningStream = session
						.listeningStream(sessionTransport);
					sink.onDispose(listeningStream::close);
				}), ServerSentEvent.class);

		}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
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
				if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest
						&& jsonrpcRequest.method().equals(McpSchema.METHOD_INITIALIZE)) {
					McpSchema.InitializeRequest initializeRequest = objectMapper.convertValue(jsonrpcRequest.params(),
							new TypeReference<McpSchema.InitializeRequest>() {
							});
					McpStreamableServerSession.McpStreamableServerSessionInit init = this.sessionFactory
						.startSession(initializeRequest);
					sessions.put(init.session().getId(), init.session());
					return init.initResult().map(initializeResult -> {
						McpSchema.JSONRPCResponse jsonrpcResponse = new McpSchema.JSONRPCResponse(
								McpSchema.JSONRPC_VERSION, jsonrpcRequest.id(), initializeResult, null);
						try {
							return this.objectMapper.writeValueAsString(jsonrpcResponse);
						}
						catch (IOException e) {
							logger.warn("Failed to serialize initResponse", e);
							throw Exceptions.propagate(e);
						}
					})
						.flatMap(initResult -> ServerResponse.ok()
							.contentType(MediaType.APPLICATION_JSON)
							.header("mcp-session-id", init.session().getId())
							.bodyValue(initResult));
				}

				if (!request.headers().asHttpHeaders().containsKey("mcp-session-id")) {
					return ServerResponse.badRequest().bodyValue(new McpError("Session ID missing"));
				}

				String sessionId = request.headers().asHttpHeaders().getFirst("mcp-session-id");
				McpStreamableServerSession session = sessions.get(sessionId);

				if (session == null) {
					return ServerResponse.status(HttpStatus.NOT_FOUND)
						.bodyValue(new McpError("Session not found: " + sessionId));
				}

				if (message instanceof McpSchema.JSONRPCResponse jsonrpcResponse) {
					return session.accept(jsonrpcResponse).then(ServerResponse.accepted().build());
				}
				else if (message instanceof McpSchema.JSONRPCNotification jsonrpcNotification) {
					return session.accept(jsonrpcNotification).then(ServerResponse.accepted().build());
				}
				else if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
					return ServerResponse.ok()
						.contentType(MediaType.TEXT_EVENT_STREAM)
						.body(Flux.<ServerSentEvent<?>>create(sink -> {
							WebFluxStreamableMcpSessionTransport st = new WebFluxStreamableMcpSessionTransport(sink);
							Mono<Void> stream = session.responseStream(jsonrpcRequest, st);
							Disposable streamSubscription = stream.doOnError(err -> sink.error(err))
								.contextWrite(sink.contextView())
								.subscribe();
							sink.onCancel(streamSubscription);
						}), ServerSentEvent.class);
				}
				else {
					return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
						.bodyValue(new McpError("Unknown message type"));
				}
			}
			catch (IllegalArgumentException | IOException e) {
				logger.error("Failed to deserialize message: {}", e.getMessage());
				return ServerResponse.badRequest().bodyValue(new McpError("Invalid message format"));
			}
		})
			.switchIfEmpty(ServerResponse.badRequest().build())
			.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}

	private Mono<ServerResponse> handleDelete(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("Server is shutting down");
		}

		McpTransportContext transportContext = this.contextExtractor.apply(request);

		return Mono.defer(() -> {
			if (!request.headers().asHttpHeaders().containsKey("mcp-session-id")) {
				return ServerResponse.badRequest().build(); // TODO: say we need a session
				// id
			}

			// TODO: The user can configure whether deletions are permitted
			if (this.disallowDelete) {
				return ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).build();
			}

			String sessionId = request.headers().asHttpHeaders().getFirst("mcp-session-id");

			McpStreamableServerSession session = this.sessions.get(sessionId);

			if (session == null) {
				return ServerResponse.notFound().build();
			}

			return session.delete().then(ServerResponse.ok().build());
		}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}

	private class WebFluxStreamableMcpSessionTransport implements McpStreamableServerTransport {

		private final FluxSink<ServerSentEvent<?>> sink;

		public WebFluxStreamableMcpSessionTransport(FluxSink<ServerSentEvent<?>> sink) {
			this.sink = sink;
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return this.sendMessage(message, null);
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
			return Mono.fromSupplier(() -> {
				try {
					return objectMapper.writeValueAsString(message);
				}
				catch (IOException e) {
					throw Exceptions.propagate(e);
				}
			}).doOnNext(jsonText -> {
				ServerSentEvent<Object> event = ServerSentEvent.builder()
					.id(messageId)
					.event(MESSAGE_EVENT_TYPE)
					.data(jsonText)
					.build();
				sink.next(event);
			}).doOnError(e -> {
				// TODO log with sessionid
				Throwable exception = Exceptions.unwrap(e);
				sink.error(exception);
			}).then();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(sink::complete);
		}

		@Override
		public void close() {
			sink.complete();
		}

	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link WebFluxStreamableServerTransportProvider}.
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
		 * Builds a new instance of {@link WebFluxStreamableServerTransportProvider} with
		 * the configured settings.
		 * @return A new WebFluxSseServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebFluxStreamableServerTransportProvider build() {
			Assert.notNull(objectMapper, "ObjectMapper must be set");
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");

			return new WebFluxStreamableServerTransportProvider(objectMapper, baseUrl, mcpEndpoint, false);
		}

	}

}
