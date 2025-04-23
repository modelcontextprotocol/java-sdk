package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.schema.McpJacksonCodec;
import io.modelcontextprotocol.schema.McpSchemaCodec;
import io.modelcontextprotocol.schema.McpType;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.schema.McpSchema;
import io.modelcontextprotocol.session.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.ServerSessionFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Server-side implementation of the MCP (Model Context Protocol) HTTP transport using
 * Server-Sent Events (SSE). This implementation provides a bidirectional communication
 * channel between MCP clients and servers using HTTP POST for client-to-server messages
 * and SSE for server-to-client messages.
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Implements the {@link McpServerTransportProvider} interface that allows managing
 * {@link McpServerSession} instances and enabling their communication with the
 * {@link McpServerTransport} abstraction.</li>
 * <li>Uses WebFlux for non-blocking request handling and SSE support</li>
 * <li>Maintains client sessions for reliable message delivery</li>
 * <li>Supports graceful shutdown with session cleanup</li>
 * <li>Thread-safe message broadcasting to multiple clients</li>
 * </ul>
 *
 * <p>
 * The transport sets up two main endpoints:
 * <ul>
 * <li>SSE endpoint (/sse) - For establishing SSE connections with clients</li>
 * <li>Message endpoint (configurable) - For receiving JSON-RPC messages from clients</li>
 * </ul>
 *
 * <p>
 * This implementation is thread-safe and can handle multiple concurrent client
 * connections. It uses {@link ConcurrentHashMap} for session management and Project
 * Reactor's non-blocking APIs for message processing and delivery.
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @author Dariusz Jędrzejczyk
 * @see McpServerTransport
 * @see ServerSentEvent
 */
public class WebFluxSseServerTransportProvider implements McpServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(WebFluxSseServerTransportProvider.class);

	/**
	 * Event type for JSON-RPC messages sent through the SSE connection.
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/**
	 * Event type for sending the message endpoint URI to clients.
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/**
	 * Default SSE endpoint path as specified by the MCP transport specification.
	 */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	public static final String DEFAULT_BASE_URL = "";

	private final McpSchemaCodec schemaCodec;

	/**
	 * Base URL for the message endpoint. This is used to construct the full URL for
	 * clients to send their JSON-RPC messages.
	 */
	private final String baseUrl;

	private final String messageEndpoint;

	private final String sseEndpoint;

	private final RouterFunction<?> routerFunction;

	private ServerSessionFactory sessionFactory;

	/**
	 * Map of active client sessions, keyed by session ID.
	 */
	private final ConcurrentHashMap<String, McpServerSession> sessions = new ConcurrentHashMap<>();

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	/**
	 * Constructs a new WebFlux SSE server transport provider instance with the default
	 * SSE endpoint.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebFluxSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * Constructs a new WebFlux SSE server transport provider instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebFluxSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
		this(objectMapper, DEFAULT_BASE_URL, messageEndpoint, sseEndpoint);
	}

	/**
	 * Constructs a new WebFlux SSE server transport provider instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param baseUrl webflux message base path
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebFluxSseServerTransportProvider(ObjectMapper objectMapper, String baseUrl, String messageEndpoint,
			String sseEndpoint) {
		this(new McpJacksonCodec(objectMapper), baseUrl, messageEndpoint, sseEndpoint);
	}

	/**
	 * Constructs a new WebFlux SSE server transport provider instance.
	 * @param schemaCodec The McpSchemaCodec to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param baseUrl webflux message base path
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	WebFluxSseServerTransportProvider(McpSchemaCodec schemaCodec, String baseUrl, String messageEndpoint,
			String sseEndpoint) {
		Assert.notNull(schemaCodec, "schemaCodec must not be null");
		Assert.notNull(baseUrl, "Message base path must not be null");
		Assert.notNull(messageEndpoint, "Message endpoint must not be null");
		Assert.notNull(sseEndpoint, "SSE endpoint must not be null");

		this.schemaCodec = schemaCodec;
		this.baseUrl = baseUrl;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
		this.routerFunction = RouterFunctions.route()
			.GET(this.sseEndpoint, this::handleSseConnection)
			.POST(this.messageEndpoint, this::handleMessage)
			.build();
	}

	@Override
	public void setSessionFactory(ServerSessionFactory sessionFactory) {
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

	@Override
	public void close() {
		this.closeGracefully().subscribe();
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
			.flatMap(McpServerSession::closeGracefully)
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
	private Mono<ServerResponse> handleSseConnection(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("Server is shutting down");
		}

		return ServerResponse.ok()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.body(Flux.<ServerSentEvent<?>>create(sink -> {
				WebFluxMcpSessionTransport sessionTransport = new WebFluxMcpSessionTransport(sink);

				McpServerSession session = (McpServerSession) sessionFactory.create(sessionTransport);
				String sessionId = session.getId();

				logger.debug("Created new SSE connection for session: {}", sessionId);
				sessions.put(sessionId, session);

				// Send initial endpoint event
				logger.debug("Sending initial endpoint event to session: {}", sessionId);
				sink.next(ServerSentEvent.builder()
					.event(ENDPOINT_EVENT_TYPE)
					.data(this.baseUrl + this.messageEndpoint + "?sessionId=" + sessionId)
					.build());
				sink.onCancel(() -> {
					logger.debug("Session {} cancelled", sessionId);
					sessions.remove(sessionId);
				});
			}), ServerSentEvent.class);
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
	private Mono<ServerResponse> handleMessage(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("Server is shutting down");
		}

		if (request.queryParam("sessionId").isEmpty()) {
			return ServerResponse.badRequest().bodyValue(new McpError("Session ID missing in message endpoint"));
		}

		McpServerSession session = sessions.get(request.queryParam("sessionId").get());

		if (session == null) {
			return ServerResponse.status(HttpStatus.NOT_FOUND)
				.bodyValue(new McpError("Session not found: " + request.queryParam("sessionId").get()));
		}

		return request.bodyToMono(String.class).flatMap(body -> {
			try {
				McpSchema.JSONRPCMessage message = schemaCodec.decodeFromString(body);
				return session.handle(message).flatMap(response -> ServerResponse.ok().build()).onErrorResume(error -> {
					logger.error("Error processing  message: {}", error.getMessage());
					// TODO: instead of signalling the error, just respond with 200 OK
					// - the error is signalled on the SSE connection
					// return ServerResponse.ok().build();
					return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
						.bodyValue(new McpError(error.getMessage()));
				});
			}
			catch (IllegalArgumentException | IOException e) {
				logger.error("Failed to deserialize message: {}", e.getMessage());
				return ServerResponse.badRequest().bodyValue(new McpError("Invalid message format"));
			}
		});
	}

	private class WebFluxMcpSessionTransport implements McpServerTransport {

		private final FluxSink<ServerSentEvent<?>> sink;

		public WebFluxMcpSessionTransport(FluxSink<ServerSentEvent<?>> sink) {
			this.sink = sink;
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.fromSupplier(() -> {
				try {
					return schemaCodec.encodeAsString(message);
				}
				catch (IOException e) {
					throw Exceptions.propagate(e);
				}
			}).doOnNext(jsonText -> {
				ServerSentEvent<Object> event = ServerSentEvent.builder()
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
		public <T> T unmarshalFrom(Object data, McpType<T> typeRef) {
			return schemaCodec.decodeResult(data, typeRef);
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
	 * Builder for creating instances of {@link WebFluxSseServerTransportProvider}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * WebFluxSseServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private McpSchemaCodec schemaCodec;

		private ObjectMapper objectMapper;

		private String baseUrl = DEFAULT_BASE_URL;

		private String messageEndpoint;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

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
		 * Sets the schema codec for JSON serialization/deserialization.
		 * @param schemaCodec the McpSchemaCodec implementation
		 * @return this builder
		 */
		public Builder withSchemaCodec(final McpSchemaCodec schemaCodec) {
			Assert.notNull(schemaCodec, "McpSchemaCodec must not be null");
			this.schemaCodec = schemaCodec;
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
			this.messageEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the SSE endpoint path.
		 * @param sseEndpoint The SSE endpoint path. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if sseEndpoint is null
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.notNull(sseEndpoint, "SSE endpoint must not be null");
			this.sseEndpoint = sseEndpoint;
			return this;
		}

		/**
		 * Builds a new instance of {@link WebFluxSseServerTransportProvider} with the
		 * configured settings.
		 * @return A new WebFluxSseServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebFluxSseServerTransportProvider build() {
			Assert.notNull(objectMapper, "ObjectMapper must be set");
			Assert.notNull(messageEndpoint, "Message endpoint must be set");
			if (schemaCodec == null) {
				schemaCodec = new McpJacksonCodec(objectMapper);
			}
			return new WebFluxSseServerTransportProvider(schemaCodec, baseUrl, messageEndpoint, sseEndpoint);
		}

	}

}
