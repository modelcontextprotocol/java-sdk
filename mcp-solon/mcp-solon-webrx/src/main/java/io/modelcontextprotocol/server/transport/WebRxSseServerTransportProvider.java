/*
 * Copyright 2025 - 2025 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.util.Assert;
import org.noear.solon.SolonApp;
import org.noear.solon.Utils;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Entity;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.web.sse.SseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * Reactor's non-blocking APIs for message processing and delivery. and base JDK8
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @author Dariusz Jędrzejczyk
 * @author noear
 * @see McpServerTransport
 * @see SseEvent
 */
public class WebRxSseServerTransportProvider implements McpServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(WebRxSseServerTransportProvider.class);

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

	private McpServerSession.Factory sessionFactory;

	/**
	 * Map of active client sessions, keyed by session ID.
	 */
	private final ConcurrentHashMap<String, McpServerSession> sessions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, WebRxMcpSessionTransport> sessionTransports = new ConcurrentHashMap<>();
	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	/**
	 * Constructs a new WebFlux SSE server transport provider instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of MCP messages. Must not be null.
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages. This endpoint will be communicated to clients during SSE connection
	 * setup. Must not be null.
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebRxSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(messageEndpoint, "Message endpoint must not be null");
		Assert.notNull(sseEndpoint, "SSE endpoint must not be null");

		this.objectMapper = objectMapper;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
	}

	public void sendHeartbeat(){
		for (WebRxMcpSessionTransport transport : sessionTransports.values()) {
			transport.sendHeartbeat();
		}
	}

	public void toHttpHandler(SolonApp app) {
		if (app != null) {
			app.get(this.sseEndpoint, this::handleSseConnection);
			app.post(this.messageEndpoint, this::handleMessage);
		}
	}

	public String getSseEndpoint() {
		return sseEndpoint;
	}

	public String getMessageEndpoint() {
		return messageEndpoint;
	}

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
	public WebRxSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
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
	public Mono<Void> notifyClients(String method, Map<String,Object> params) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

		return Flux.fromStream(sessions.values().stream())
				.flatMap(session -> session.sendNotification(method, params)
						.doOnError(e -> logger.error("Failed to " + "send message to session " + "{}: {}", session.getId(),
								e.getMessage()))
						.onErrorComplete())
				.then();
	}

	// FIXME: This javadoc makes claims about using isClosing flag but it's not actually
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
	 * Handles new SSE connection requests from clients. Creates a new session for each
	 * connection and sets up the SSE event stream.
	 * @param ctx The incoming server context
	 * @return A Mono which emits a response with the SSE event stream
	 */
	public void handleSseConnection(Context ctx) throws Throwable{
		if (isClosing) {
			ctx.status(503);
			ctx.output("Server is shutting down");
			return;
		}

		Flux<SseEvent> publisher = Flux.create(sink -> {
			WebRxMcpSessionTransport sessionTransport = new WebRxMcpSessionTransport(sink);

			McpServerSession session = sessionFactory.create(sessionTransport);
			String sessionId = session.getId();

			logger.debug("Created new SSE connection for session: {}", sessionId);
			sessions.put(sessionId, session);
			sessionTransports.put(sessionId, sessionTransport);

			// Send initial endpoint event
			logger.debug("Sending initial endpoint event to session: {}", sessionId);
			sink.next(new SseEvent()
					.name(ENDPOINT_EVENT_TYPE)
					.data(messageEndpoint + "?sessionId=" + sessionId));
			sink.onCancel(() -> {
				logger.debug("Session {} cancelled", sessionId);
				sessions.remove(sessionId);
				sessionTransports.remove(sessionId);
			});
		});

		ctx.contentType(MimeType.TEXT_EVENT_STREAM_VALUE);
		ctx.returnValue(publisher);
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
	 * @param ctx The incoming server request context containing the JSON-RPC message
	 * @return A Mono emitting the response indicating the message processing result
	 */
	public void handleMessage(Context ctx) throws Throwable {
		if (isClosing) {
			ctx.status(503);
			ctx.output("Server is shutting down");
			return;
		}

		if (Utils.isEmpty(ctx.param("sessionId"))) {
			ctx.status(404);
			ctx.render(new McpError("Session ID missing in message endpoint"));
			return;
		}

		McpServerSession session = sessions.get(ctx.param("sessionId"));

		String body = ctx.body();
		try {
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

			Mono<Entity> mono = session.handle(message)
					.flatMap(response -> {
						return Mono.just(new Entity());
					})
					.onErrorResume(error -> {
						logger.error("Error processing  message: {}", error.getMessage());
						// TODO: instead of signalling the error, just respond with 200 OK
						// - the error is signalled on the SSE connection
						// return ServerResponse.ok().build();
						return Mono.just(new Entity().status(500).body(new McpError(error.getMessage())));
					});

			ctx.returnValue(mono);
		} catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			ctx.status(400);
			ctx.render(new McpError("Invalid message format"));
		}
	}

	private class WebRxMcpSessionTransport implements McpServerTransport {

		private final FluxSink<SseEvent> sink;

		public WebRxMcpSessionTransport(FluxSink<SseEvent> sink) {
			this.sink = sink;
		}

		public void sendHeartbeat() {
			sink.next(new SseEvent().comment("heartbeat"));
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
				SseEvent event = new SseEvent()
						.name(MESSAGE_EVENT_TYPE)
						.data(jsonText);
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
	 * Builder for creating instances of {@link WebRxSseServerTransportProvider}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * WebFluxSseServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private ObjectMapper objectMapper;

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
		 * Builds a new instance of {@link WebRxSseServerTransportProvider} with the
		 * configured settings.
		 * @return A new WebFluxSseServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebRxSseServerTransportProvider build() {
			Assert.notNull(objectMapper, "ObjectMapper must be set");
			Assert.notNull(messageEndpoint, "Message endpoint must be set");

			return new WebRxSseServerTransportProvider(objectMapper, messageEndpoint, sseEndpoint);
		}
	}
}
