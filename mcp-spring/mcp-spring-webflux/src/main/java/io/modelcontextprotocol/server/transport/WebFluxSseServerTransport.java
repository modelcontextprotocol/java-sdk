package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

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
 * <li>Implements the {@link ServerMcpTransport} interface for MCP server transport
 * functionality</li>
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
 * connections. It uses {@link ConcurrentHashMap} for session management and Reactor's
 * {@link Sinks} for thread-safe message broadcasting.
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see ServerMcpTransport
 * @see ServerSentEvent
 * @deprecated This class will be removed in 0.9.0. Use
 * {@link WebFluxSseServerTransportProvider}.
 */
@Deprecated
public class WebFluxSseServerTransport implements ServerMcpTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebFluxSseServerTransport.class);

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

	private final ObjectMapper objectMapper;

	private final String messageEndpoint;

	private final String sseEndpoint;

	private final RouterFunction<?> routerFunction;

	/**
	 * Map of active client sessions, keyed by session ID.
	 */
	private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> connectHandler;

	/**
	 * Constructs a new WebFlux SSE server transport instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebFluxSseServerTransport(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(messageEndpoint, "Message endpoint must not be null");
		Assert.notNull(sseEndpoint, "SSE endpoint must not be null");

		this.objectMapper = objectMapper;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
		this.routerFunction = RouterFunctions.route()
			.GET(this.sseEndpoint, this::handleSseConnection)
			.POST(this.messageEndpoint, this::handleMessage)
			.build();
	}

	/**
	 * Constructs a new WebFlux SSE server transport instance with the default SSE
	 * endpoint.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebFluxSseServerTransport(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * Configures the message handler for this transport. In the WebFlux SSE
	 * implementation, this method stores the handler for processing incoming messages but
	 * doesn't establish any connections since the server accepts connections rather than
	 * initiating them.
	 * @param handler A function that processes incoming JSON-RPC messages and returns
	 * responses. This handler will be called for each message received through the
	 * message endpoint.
	 * @return An empty Mono since the server doesn't initiate connections
	 */
	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		this.connectHandler = handler;
		// Server-side transport doesn't initiate connections
		return Mono.empty().then();
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
	 * @param message The JSON-RPC message to broadcast
	 * @return A Mono that completes when the message has been sent to all sessions, or
	 * errors if any session fails to receive the message
	 */
	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		return Mono.<Void>create(sink -> {
			try {// @formatter:off
				String jsonText = objectMapper.writeValueAsString(message);
				ServerSentEvent<Object> event = ServerSentEvent.builder()
				                                               .event(MESSAGE_EVENT_TYPE)
				                                               .data(jsonText)
				                                               .build();

				logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

				List<String> failedSessions = sessions.values().stream()
				                                      .filter(session -> session.messageSink.tryEmitNext(event).isFailure())
				                                      .map(session -> session.id)
				                                      .toList();

				if (failedSessions.isEmpty()) {
					logger.debug("Successfully broadcast message to all sessions");
					sink.success();
				}
				else {
					String error = "Failed to broadcast message to sessions: " + String.join(", ", failedSessions);
					logger.error(error);
					sink.error(new RuntimeException(error));
				} // @formatter:on
			}
			catch (IOException e) {
				logger.error("Failed to serialize message: {}", e.getMessage());
				sink.error(e);
			}
		});
	}

	/**
	 * Converts data from one type to another using the configured ObjectMapper. This
	 * method is primarily used for converting between different representations of
	 * JSON-RPC message data.
	 * @param <T> The target type to convert to
	 * @param data The source data to convert
	 * @param typeRef Type reference describing the target type
	 * @return The converted data
	 * @throws IllegalArgumentException if the conversion fails
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	/**
	 * Initiates a graceful shutdown of the transport. This method ensures all active
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
		return Mono.fromRunnable(() -> {
			isClosing = true;
			logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());
		}).then(Mono.when(sessions.values().stream().map(session -> {
			String sessionId = session.id;
			return Mono.fromRunnable(() -> session.close())
				.then(Mono.delay(Duration.ofMillis(100)))
				.then(Mono.fromRunnable(() -> sessions.remove(sessionId)));
		}).toList()))
			.timeout(Duration.ofSeconds(5))
			.doOnSuccess(v -> logger.debug("Graceful shutdown completed"))
			.doOnError(e -> logger.error("Error during graceful shutdown: {}", e.getMessage()));
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
	 *
	 * <p>
	 * The handler performs the following steps:
	 * <ul>
	 * <li>Generates a unique session ID</li>
	 * <li>Creates a new ClientSession instance</li>
	 * <li>Sends the message endpoint URI as an initial event</li>
	 * <li>Sets up message forwarding for the session</li>
	 * <li>Handles connection cleanup on completion or errors</li>
	 * </ul>
	 * @param request The incoming server request
	 * @return A response with the SSE event stream
	 */
	private Mono<ServerResponse> handleSseConnection(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("Server is shutting down");
		}
		String sessionId = UUID.randomUUID().toString();
		logger.debug("Creating new SSE connection for session: {}", sessionId);
		ClientSession session = new ClientSession(sessionId);
		this.sessions.put(sessionId, session);

		return ServerResponse.ok()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.body(Flux.<ServerSentEvent<?>>create(sink -> {
				// Send initial endpoint event
				logger.debug("Sending initial endpoint event to session: {}", sessionId);
				sink.next(ServerSentEvent.builder().event(ENDPOINT_EVENT_TYPE).data(messageEndpoint).build());

				// Subscribe to session messages
				session.messageSink.asFlux()
					.doOnSubscribe(s -> logger.debug("Session {} subscribed to message sink", sessionId))
					.doOnComplete(() -> {
						logger.debug("Session {} completed", sessionId);
						sessions.remove(sessionId);
					})
					.doOnError(error -> {
						logger.error("Error in session {}: {}", sessionId, error.getMessage());
						sessions.remove(sessionId);
					})
					.doOnCancel(() -> {
						logger.debug("Session {} cancelled", sessionId);
						sessions.remove(sessionId);
					})
					.subscribe(event -> {
						logger.debug("Forwarding event to session {}: {}", sessionId, event);
						sink.next(event);
					}, sink::error, sink::complete);

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
	 * @return A response indicating the message processing result
	 */
	private Mono<ServerResponse> handleMessage(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("Server is shutting down");
		}

		return request.bodyToMono(String.class).flatMap(body -> {
			try {
				McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);
				return Mono.just(message)
					.transform(this.connectHandler)
					.flatMap(response -> ServerResponse.ok().build())
					.onErrorResume(error -> {
						logger.error("Error processing message: {}", error.getMessage());
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

	/**
	 * Represents an active client SSE connection session. Manages the message sink for
	 * sending events to the client and handles session lifecycle.
	 *
	 * <p>
	 * Each session:
	 * <ul>
	 * <li>Has a unique identifier</li>
	 * <li>Maintains its own message sink for event broadcasting</li>
	 * <li>Supports clean shutdown through the close method</li>
	 * </ul>
	 */
	private static class ClientSession {

		private final String id;

		private final Sinks.Many<ServerSentEvent<?>> messageSink;

		ClientSession(String id) {
			this.id = id;
			logger.debug("Creating new session: {}", id);
			this.messageSink = Sinks.many().replay().latest();
			logger.debug("Session {} initialized with replay sink", id);
		}

		void close() {
			logger.debug("Closing session: {}", id);
			Sinks.EmitResult result = messageSink.tryEmitComplete();
			if (result.isFailure()) {
				logger.warn("Failed to complete message sink for session {}: {}", id, result);
			}
			else {
				logger.debug("Successfully completed message sink for session {}", id);
			}
		}

	}

}