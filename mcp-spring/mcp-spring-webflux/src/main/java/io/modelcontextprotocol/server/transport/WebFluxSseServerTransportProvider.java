package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.ServerMcpSession;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
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
 */
public class WebFluxSseServerTransportProvider implements McpServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(
			WebFluxSseServerTransportProvider.class);

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

	private ServerMcpSession.Factory sessionFactory;

	/**
	 * Map of active client sessions, keyed by session ID.
	 */
	private final ConcurrentHashMap<String, ServerMcpSession> sessions = new ConcurrentHashMap<>();

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	/**
	 * Constructs a new WebFlux SSE server transport instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebFluxSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
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
	public WebFluxSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	@Override
	public void setSessionFactory(ServerMcpSession.Factory sessionFactory) {
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
	public Mono<Void> notifyClients(String method, Map<String, Object> params) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

		return Flux.fromStream(sessions.values().stream())
			.flatMap(session -> session.sendNotification(method, params)
				.doOnError(e -> logger.error("Failed to " + "send message to session " +
								"{}: {}", session.getId(),
						e.getMessage()))
				.onErrorComplete())
			.then();
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
		return Flux.fromIterable(sessions.values())
			.doFirst(() -> logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size()))
			.flatMap(ServerMcpSession::closeGracefully)
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

		return ServerResponse.ok()
			.contentType(MediaType.TEXT_EVENT_STREAM)
			.body(Flux.<ServerSentEvent<?>>create(sink -> {
				WebFluxMcpSessionTransport sessionTransport = new WebFluxMcpSessionTransport(sink);

				ServerMcpSession session = sessionFactory.create(sessionTransport);
				String sessionId = session.getId();

				logger.debug("Created new SSE connection for session: {}", sessionId);
				sessions.put(sessionId, session);

				// Send initial endpoint event
				logger.debug("Sending initial endpoint event to session: {}", sessionId);
				sink.next(ServerSentEvent.builder()
					.event(ENDPOINT_EVENT_TYPE)
					.data(messageEndpoint + "?sessionId=" + sessionId)
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
	 * @return A response indicating the message processing result
	 */
	private Mono<ServerResponse> handleMessage(ServerRequest request) {
		if (isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("Server is shutting down");
		}

		if (request.queryParam("sessionId").isEmpty()) {
			return ServerResponse.badRequest().bodyValue(new McpError("Session ID missing in message endpoint"));
		}

		ServerMcpSession session = sessions.get(request.queryParam("sessionId").get());

		return request.bodyToMono(String.class).flatMap(body -> {
			try {
				McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);
				return session.handle(message).flatMap(response -> ServerResponse.ok().build()).onErrorResume(error -> {
					logger.error("Error processing  message: {}", error.getMessage());
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

	/*
	Current:

	framework layer:
	  var transport = new WebFluxSseServerTransport(objectMapper, "/mcp", "/sse");
	  McpServer.async(ServerMcpTransport transport)

	client connects ->
	  WebFluxSseServerTransport creates a:
	    - var sessionTransport = WebFluxMcpSessionTransport
	    - ServerMcpSession(sessionId, sessionTransport)

	  WebFluxSseServerTransport IS_A ServerMcpTransport IS_A McpTransport
	  WebFluxMcpSessionTransport IS_A ServerMcpSessionTransport IS_A McpTransport

	  McpTransport contains connect() which should be removed
	  ClientMcpTransport should have connect()
	  ServerMcpTransport should have setSessionFactory()

	Possible Future:
	  var transportProvider = new WebFluxSseServerTransport(objectMapper, "/mcp", "/sse");
	  WebFluxSseServerTransport IS_A ServerMcpTransportProvider ?
	  ServerMcpTransportProvider creates ServerMcpTransport

	  // disadvantage - too much breaks, e.g.
	  McpServer.async(ServerMcpTransportProvider transportProvider)

	  // advantage

	  ClientMcpTransport and ServerMcpTransport BOTH represent 1:1 relationship




	 */

	private class WebFluxMcpSessionTransport implements McpServerTransport {

		private final FluxSink<ServerSentEvent<?>> sink;

		public WebFluxMcpSessionTransport(FluxSink<ServerSentEvent<?>> sink) {
			this.sink = sink;
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.fromSupplier(() -> {
				try {
					return objectMapper.writeValueAsString(message);
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

}
