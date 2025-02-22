/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
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
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.ServerResponse.SseBuilder;

/**
 * Server-side implementation of the Model Context Protocol (MCP) transport layer using
 * HTTP with Server-Sent Events (SSE) through Spring WebMVC. This implementation provides
 * a bridge between synchronous WebMVC operations and reactive programming patterns to
 * maintain compatibility with the reactive transport interface.
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Implements bidirectional communication using HTTP POST for client-to-server
 * messages and SSE for server-to-client messages</li>
 * <li>Manages client sessions with unique IDs for reliable message delivery</li>
 * <li>Supports graceful shutdown with proper session cleanup</li>
 * <li>Provides JSON-RPC message handling through configured endpoints</li>
 * <li>Includes built-in error handling and logging</li>
 * </ul>
 *
 * <p>
 * The transport operates on two main endpoints:
 * <ul>
 * <li>{@code /sse} - The SSE endpoint where clients establish their event stream
 * connection</li>
 * <li>A configurable message endpoint where clients send their JSON-RPC messages via HTTP
 * POST</li>
 * </ul>
 *
 * <p>
 * This implementation uses {@link ConcurrentHashMap} to safely manage multiple client
 * sessions in a thread-safe manner. Each client session is assigned a unique ID and
 * maintains its own SSE connection.
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see ServerMcpTransport
 * @see RouterFunction
 */
public class WebMvcSseServerTransport implements ServerMcpTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebMvcSseServerTransport.class);

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

	private final RouterFunction<ServerResponse> routerFunction;

	/**
	 * Map of active client sessions, keyed by session ID.
	 */
	private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	/**
	 * The function to process incoming JSON-RPC messages and produce responses.
	 */
	private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> connectHandler;

	/**
	 * Constructs a new WebMvcSseServerTransport instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of messages.
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages via HTTP POST. This endpoint will be communicated to clients through the
	 * SSE connection's initial endpoint event.
	 * @throws IllegalArgumentException if either objectMapper or messageEndpoint is null
	 */
	public WebMvcSseServerTransport(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
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
	 * Constructs a new WebMvcSseServerTransport instance with the default SSE endpoint.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of messages.
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages via HTTP POST. This endpoint will be communicated to clients through the
	 * SSE connection's initial endpoint event.
	 * @throws IllegalArgumentException if either objectMapper or messageEndpoint is null
	 */
	public WebMvcSseServerTransport(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * Sets up the message handler for this transport. In the WebMVC SSE implementation,
	 * this method only stores the handler for later use, as connections are initiated by
	 * clients rather than the server.
	 * @param connectionHandler The function to process incoming JSON-RPC messages and
	 * produce responses
	 * @return An empty Mono since the server doesn't initiate connections
	 */
	@Override
	public Mono<Void> connect(
			Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> connectionHandler) {
		this.connectHandler = connectionHandler;
		// Server-side transport doesn't initiate connections
		return Mono.empty();
	}

	/**
	 * Broadcasts a message to all connected clients through their SSE connections. The
	 * message is serialized to JSON and sent as an SSE event with type "message". If any
	 * errors occur during sending to a particular client, they are logged but don't
	 * prevent sending to other clients.
	 * @param message The JSON-RPC message to broadcast to all connected clients
	 * @return A Mono that completes when the broadcast attempt is finished
	 */
	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		return Mono.fromRunnable(() -> {
			if (sessions.isEmpty()) {
				logger.debug("No active sessions to broadcast message to");
				return;
			}

			try {
				String jsonText = objectMapper.writeValueAsString(message);
				logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

				sessions.values().forEach(session -> {
					try {
						session.sseBuilder.id(session.id).event(MESSAGE_EVENT_TYPE).data(jsonText);
					}
					catch (Exception e) {
						logger.error("Failed to send message to session {}: {}", session.id, e.getMessage());
						session.sseBuilder.error(e);
					}
				});
			}
			catch (IOException e) {
				logger.error("Failed to serialize message: {}", e.getMessage());
			}
		});
	}

	/**
	 * Handles new SSE connection requests from clients by creating a new session and
	 * establishing an SSE connection. This method:
	 * <ul>
	 * <li>Generates a unique session ID</li>
	 * <li>Creates a new ClientSession with an SSE builder</li>
	 * <li>Sends an initial endpoint event to inform the client where to send
	 * messages</li>
	 * <li>Maintains the session in the sessions map</li>
	 * </ul>
	 * @param request The incoming server request
	 * @return A ServerResponse configured for SSE communication, or an error response if
	 * the server is shutting down or the connection fails
	 */
	private ServerResponse handleSseConnection(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		String sessionId = UUID.randomUUID().toString();
		logger.debug("Creating new SSE connection for session: {}", sessionId);

		// Send initial endpoint event
		try {
			return ServerResponse.sse(sseBuilder -> {
				sseBuilder.onComplete(() -> {
					logger.debug("SSE connection completed for session: {}", sessionId);
					sessions.remove(sessionId);
				});
				sseBuilder.onTimeout(() -> {
					logger.debug("SSE connection timed out for session: {}", sessionId);
					sessions.remove(sessionId);
				});

				ClientSession session = new ClientSession(sessionId, sseBuilder);
				this.sessions.put(sessionId, session);

				try {
					session.sseBuilder.id(session.id).event(ENDPOINT_EVENT_TYPE).data(messageEndpoint);
				}
				catch (Exception e) {
					logger.error("Failed to poll event from session queue: {}", e.getMessage());
					sseBuilder.error(e);
				}
			});
		}
		catch (Exception e) {
			logger.error("Failed to send initial endpoint event to session {}: {}", sessionId, e.getMessage());
			sessions.remove(sessionId);
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	/**
	 * Handles incoming JSON-RPC messages from clients. This method:
	 * <ul>
	 * <li>Deserializes the request body into a JSON-RPC message</li>
	 * <li>Processes the message through the configured connect handler</li>
	 * <li>Returns appropriate HTTP responses based on the processing result</li>
	 * </ul>
	 * @param request The incoming server request containing the JSON-RPC message
	 * @return A ServerResponse indicating success (200 OK) or appropriate error status
	 * with error details in case of failures
	 */
	private ServerResponse handleMessage(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		try {
			String body = request.body(String.class);
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

			// Convert the message to a Mono, apply the handler, and block for the
			// response
			@SuppressWarnings("unused")
			McpSchema.JSONRPCMessage response = Mono.just(message).transform(connectHandler).block();

			return ServerResponse.ok().build();
		}
		catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			return ServerResponse.badRequest().body(new McpError("Invalid message format"));
		}
		catch (Exception e) {
			logger.error("Error handling message: {}", e.getMessage());
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
		}
	}

	/**
	 * Represents an active client session with its associated SSE connection. Each
	 * session maintains:
	 * <ul>
	 * <li>A unique session identifier</li>
	 * <li>An SSE builder for sending server events to the client</li>
	 * <li>Logging of session lifecycle events</li>
	 * </ul>
	 */
	private static class ClientSession {

		private final String id;

		private final SseBuilder sseBuilder;

		/**
		 * Creates a new client session with the specified ID and SSE builder.
		 * @param id The unique identifier for this session
		 * @param sseBuilder The SSE builder for sending server events to the client
		 */
		ClientSession(String id, SseBuilder sseBuilder) {
			this.id = id;
			this.sseBuilder = sseBuilder;
			logger.debug("Session {} initialized with SSE emitter", id);
		}

		/**
		 * Closes this session by completing the SSE connection. Any errors during
		 * completion are logged but do not prevent the session from being marked as
		 * closed.
		 */
		void close() {
			logger.debug("Closing session: {}", id);
			try {
				sseBuilder.complete();
				logger.debug("Successfully completed SSE emitter for session {}", id);
			}
			catch (Exception e) {
				logger.warn("Failed to complete SSE emitter for session {}: {}", id, e.getMessage());
				// sseBuilder.error(e);
			}
		}

	}

	/**
	 * Converts data from one type to another using the configured ObjectMapper. This is
	 * particularly useful for handling complex JSON-RPC parameter types.
	 * @param data The source data object to convert
	 * @param typeRef The target type reference
	 * @return The converted object of type T
	 * @param <T> The target type
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	/**
	 * Initiates a graceful shutdown of the transport. This method:
	 * <ul>
	 * <li>Sets the closing flag to prevent new connections</li>
	 * <li>Closes all active SSE connections</li>
	 * <li>Removes all session records</li>
	 * </ul>
	 * @return A Mono that completes when all cleanup operations are finished
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			this.isClosing = true;
			logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

			sessions.values().forEach(session -> {
				String sessionId = session.id;
				session.close();
				sessions.remove(sessionId);
			});

			logger.debug("Graceful shutdown completed");
		});
	}

	/**
	 * Returns the RouterFunction that defines the HTTP endpoints for this transport. The
	 * router function handles two endpoints:
	 * <ul>
	 * <li>GET /sse - For establishing SSE connections</li>
	 * <li>POST [messageEndpoint] - For receiving JSON-RPC messages from clients</li>
	 * </ul>
	 * @return The configured RouterFunction for handling HTTP requests
	 */
	public RouterFunction<ServerResponse> getRouterFunction() {
		return this.routerFunction;
	}

}
