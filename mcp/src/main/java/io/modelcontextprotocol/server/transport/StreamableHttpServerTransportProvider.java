/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Implementation of the MCP Streamable HTTP transport provider for servers. This
 * implementation follows the Streamable HTTP transport specification from protocol
 * version 2024-11-05.
 *
 * <p>
 * The transport handles a single HTTP endpoint that supports POST, GET, & DELETE methods:
 * <ul>
 * <li>POST - For receiving client messages and optionally establishing SSE streams for
 * responses</li>
 * <li>GET - For establishing SSE streams for server-to-client communication</li>
 * <li>DELETE - For terminating sessions</li>
 * </ul>
 *
 * <p>
 * Features:
 * <ul>
 * <li>Session management with secure session IDs</li>
 * <li>Support for resumable SSE streams</li>
 * <li>Support for multiple concurrent client connections</li>
 * <li>Graceful shutdown support</li>
 * </ul>
 *
 */
@WebServlet(asyncSupported = true)
public class StreamableHttpServerTransportProvider extends HttpServlet implements McpServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpServerTransportProvider.class);

	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String TEXT_EVENT_STREAM = "text/event-stream";

	public static final String SESSION_ID_HEADER = "Mcp-Session-Id";

	public static final String LAST_EVENT_ID_HEADER = "Last-Event-Id";

	public static final String MESSAGE_EVENT_TYPE = "message";

	/** JSON object mapper for serialization/deserialization */
	private final ObjectMapper objectMapper;

	/** The endpoint path for handling MCP requests */
	private final String mcpEndpoint;

	/** Map of active client sessions, keyed by session ID */
	private final Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();

	/** Map of active SSE streams, keyed by session ID */
	private final Map<String, StreamableHttpSseStream> sseStreams = new ConcurrentHashMap<>();

	/** Flag indicating if the transport is in the process of shutting down */
	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	/** Session factory for creating new sessions */
	private McpServerSession.Factory sessionFactory;

	/**
	 * Creates a new StreamableHttpServerTransportProvider instance.
	 * @param objectMapper The JSON object mapper to use for message
	 * serialization/deserialization
	 * @param mcpEndpoint The endpoint path for handling MCP requests
	 */
	public StreamableHttpServerTransportProvider(ObjectMapper objectMapper, String mcpEndpoint) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.hasText(mcpEndpoint, "MCP endpoint must not be empty");

		this.objectMapper = objectMapper;
		this.mcpEndpoint = mcpEndpoint;
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

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
	public Mono<Void> closeGracefully() {
		isClosing.set(true);
		logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values()).flatMap(McpServerSession::closeGracefully).then();
	}

	/**
	 * Handles HTTP GET requests to establish SSE connections.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		logger.debug("GET request received for URI: {}", requestURI);

		// Log all headers for debugging
		Enumeration<String> headerNames = request.getHeaderNames();
		while (headerNames.hasMoreElements()) {
			String headerName = headerNames.nextElement();
			logger.debug("Header: {} = {}", headerName, request.getHeader(headerName));
		}

		if (!requestURI.endsWith(mcpEndpoint)) {
			logger.debug("URI does not match mcpEndpoint: {}", mcpEndpoint);
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		if (isClosing.get()) {
			logger.debug("Server is shutting down, rejecting request");
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		String acceptHeader = request.getHeader("Accept");
		logger.debug("Accept header: {}", acceptHeader);
		if (acceptHeader == null || !acceptHeader.contains(TEXT_EVENT_STREAM)) {
			logger.debug("Accept header missing or does not include {}", TEXT_EVENT_STREAM);
			response.setContentType(APPLICATION_JSON);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.getWriter().write(createErrorJson("Accept header must include text/event-stream"));
			return;
		}

		String sessionId = request.getHeader(SESSION_ID_HEADER);
		if (sessionId == null) {
			response.setContentType(APPLICATION_JSON);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.getWriter().write(createErrorJson("Session ID missing in request header"));
			return;
		}

		McpServerSession session = sessions.get(sessionId);
		if (session == null) {
			response.setContentType(APPLICATION_JSON);
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			response.getWriter().write(createErrorJson("Session not found: " + sessionId));
			return;
		}

		// Set up SSE connection
		response.setContentType(TEXT_EVENT_STREAM);
		response.setCharacterEncoding(UTF_8);
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Connection", "keep-alive");
		response.setHeader(SESSION_ID_HEADER, sessionId);

		// Start async processing
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0); // No timeout

		// Check for Last-Event-ID header for resumable streams
		String lastEventId = request.getHeader(LAST_EVENT_ID_HEADER);

		// Create or get SSE stream for this session
		StreamableHttpSseStream sseStream = getOrCreateSseStream(sessionId);
		if (lastEventId != null) {
			sseStream.replayEventsAfter(lastEventId);
		}

		PrintWriter writer = response.getWriter();

		// Subscribe to the SSE stream and write events to the response
		sseStream.getEventFlux().doOnNext(event -> {
			try {
				if (event.id() != null) {
					writer.write("id: " + event.id() + "\n");
				}
				if (event.event() != null) {
					writer.write("event: " + event.event() + "\n");
				}
				writer.write("data: " + event.data() + "\n\n");
				writer.flush();

				if (writer.checkError()) {
					throw new IOException("Client disconnected");
				}
			}
			catch (IOException e) {
				logger.debug("Error writing to SSE stream: {}", e.getMessage());
				asyncContext.complete();
			}
		}).doOnComplete(() -> {
			try {
				writer.close();
			}
			finally {
				asyncContext.complete();
			}
		}).doOnError(e -> {
			logger.error("Error in SSE stream: {}", e.getMessage());
			asyncContext.complete();
		}).subscribe();
	}

	/**
	 * Handles HTTP POST requests for client messages.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		logger.debug("POST request received for URI: {}", requestURI);

		// Log all headers for debugging
		Enumeration<String> headerNames = request.getHeaderNames();
		while (headerNames.hasMoreElements()) {
			String headerName = headerNames.nextElement();
			logger.debug("Header: {} = {}", headerName, request.getHeader(headerName));
		}

		if (!requestURI.endsWith(mcpEndpoint)) {
			logger.debug("URI does not match mcpEndpoint: {}", mcpEndpoint);
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		if (isClosing.get()) {
			logger.debug("Server is shutting down, rejecting request");
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		// According to spec, client MUST include an Accept header listing both
		// application/json and text/event-stream
		String acceptHeader = request.getHeader("Accept");
		logger.debug("Accept header: {}", acceptHeader);
		if (acceptHeader == null
				|| (!acceptHeader.contains(APPLICATION_JSON) || !acceptHeader.contains(TEXT_EVENT_STREAM))) {
			logger.debug("Accept header validation failed. Header: {}", acceptHeader);
			response.setContentType(APPLICATION_JSON);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.getWriter()
				.write(createErrorJson("Accept header must include both application/json and text/event-stream"));
			return;
		}

		// Client accepts SSE since we've validated the Accept header contains
		// text/event-stream
		boolean acceptsEventStream = true;

		// Get session ID from header
		String sessionId = request.getHeader(SESSION_ID_HEADER);
		boolean isInitializeRequest = false;

		try {
			// Read request body
			StringBuilder body = new StringBuilder();
			try (BufferedReader reader = request.getReader()) {
				String line;
				while ((line = reader.readLine()) != null) {
					body.append(line);
				}
			}

			// Parse the JSON-RPC message
			JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString());

			// Check if this is an initialize request
			if (message instanceof McpSchema.JSONRPCRequest req && McpSchema.METHOD_INITIALIZE.equals(req.method())) {
				isInitializeRequest = true;
				// For initialize requests, create a new session if one doesn't exist
				if (sessionId == null) {
					sessionId = UUID.randomUUID().toString();
					logger.debug("Created new session ID for initialize request: {}", sessionId);
				}
			}

			// Validate session ID for non-initialize requests
			if (!isInitializeRequest && sessionId == null) {
				response.setContentType(APPLICATION_JSON);
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				response.getWriter().write(createErrorJson("Session ID missing in request header"));
				return;
			}

			// Get or create session
			McpServerSession session = getOrCreateSession(sessionId, isInitializeRequest);
			if (session == null && !isInitializeRequest) {
				response.setContentType(APPLICATION_JSON);
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				response.getWriter().write(createErrorJson("Session not found: " + sessionId));
				return;
			}

			// Handle the message
			session.handle(message).block(); // Block for servlet compatibility

			// Set session ID header in response
			response.setHeader(SESSION_ID_HEADER, sessionId);

			// For requests that expect responses, we need to set up an SSE stream
			if (message instanceof McpSchema.JSONRPCRequest && acceptsEventStream) {
				// Set up SSE connection
				response.setContentType(TEXT_EVENT_STREAM);
				response.setCharacterEncoding(UTF_8);
				response.setHeader("Cache-Control", "no-cache");
				response.setHeader("Connection", "keep-alive");

				// Start async processing
				AsyncContext asyncContext = request.startAsync();
				asyncContext.setTimeout(0); // No timeout

				StreamableHttpSseStream sseStream = getOrCreateSseStream(sessionId);
				PrintWriter writer = response.getWriter();

				// For initialize requests, include the session ID in the response
				if (isInitializeRequest) {
					response.setHeader(SESSION_ID_HEADER, sessionId);
				}

				// Subscribe to the SSE stream and write events to the response
				sseStream.getEventFlux().doOnNext(event -> {
					try {
						if (event.id() != null) {
							writer.write("id: " + event.id() + "\n");
						}
						if (event.event() != null) {
							writer.write("event: " + event.event() + "\n");
						}
						writer.write("data: " + event.data() + "\n\n");
						writer.flush();

						if (writer.checkError()) {
							throw new IOException("Client disconnected");
						}
					}
					catch (IOException e) {
						logger.debug("Error writing to SSE stream: {}", e.getMessage());
						asyncContext.complete();
					}
				}).doOnComplete(() -> {
					try {
						writer.close();
					}
					finally {
						asyncContext.complete();
					}
				}).doOnError(e -> {
					logger.error("Error in SSE stream: {}", e.getMessage());
					asyncContext.complete();
				}).subscribe();
			}
			else if (message instanceof McpSchema.JSONRPCRequest) {
				// Client doesn't accept SSE, we'll return a regular JSON response
				response.setContentType(APPLICATION_JSON);
				response.setStatus(HttpServletResponse.SC_OK);
				// The actual response would be sent later through another channel
			}
			else {
				// For notifications and responses, return 202 Accepted
				response.setStatus(HttpServletResponse.SC_ACCEPTED);
			}
		}
		catch (Exception e) {
			logger.error("Error processing message: {}", e.getMessage());
			response.setContentType(APPLICATION_JSON);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.getWriter().write(createErrorJson("Invalid JSON-RPC message: " + e.getMessage()));
		}
	}

	/**
	 * Handles HTTP DELETE requests to terminate sessions.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(mcpEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		String sessionId = request.getHeader(SESSION_ID_HEADER);
		if (sessionId == null) {
			response.setContentType(APPLICATION_JSON);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.getWriter().write(createErrorJson("Session ID missing in request header"));
			return;
		}

		McpServerSession session = sessions.remove(sessionId);
		if (session == null) {
			response.setContentType(APPLICATION_JSON);
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			response.getWriter().write(createErrorJson("Session not found: " + sessionId));
			return;
		}

		// Close the session and any associated SSE stream
		StreamableHttpSseStream sseStream = sseStreams.remove(sessionId);
		if (sseStream != null) {
			sseStream.complete();
		}

		session.close();
		logger.debug("Session terminated: {}", sessionId);

		response.setStatus(HttpServletResponse.SC_OK);
	}

	/**
	 * Gets or creates a session for the given session ID.
	 * @param sessionId The session ID
	 * @param createIfMissing Whether to create a new session if one doesn't exist
	 * @return The session, or null if it doesn't exist and createIfMissing is false
	 */
	private McpServerSession getOrCreateSession(String sessionId, boolean createIfMissing) {
		McpServerSession session = sessions.get(sessionId);
		if (session == null && createIfMissing) {
			StreamableHttpServerTransport transport = new StreamableHttpServerTransport(sessionId);
			session = sessionFactory.create(transport);
			sessions.put(sessionId, session);
			logger.debug("Created new session: {}", sessionId);
		}
		return session;
	}

	/**
	 * Gets or creates an SSE stream for the given session ID.
	 * @param sessionId The session ID
	 * @return The SSE stream
	 */
	private StreamableHttpSseStream getOrCreateSseStream(String sessionId) {
		return sseStreams.computeIfAbsent(sessionId, id -> {
			StreamableHttpSseStream stream = new StreamableHttpSseStream();
			logger.debug("Created new SSE stream for session: {}", id);
			return stream;
		});
	}

	/**
	 * Creates a JSON error response.
	 * @param message The error message
	 * @return The JSON error string
	 */
	private String createErrorJson(String message) {
		try {
			return objectMapper.writeValueAsString(new McpError(message));
		}
		catch (IOException e) {
			logger.error("Failed to serialize error message", e);
			return "{\"error\":\"" + message + "\"}";
		}
	}

	/**
	 * Implementation of McpServerTransport for Streamable HTTP sessions.
	 */
	private class StreamableHttpServerTransport implements McpServerTransport {

		private final String sessionId;

		/**
		 * Creates a new session transport with the specified ID.
		 * @param sessionId The unique identifier for this session
		 */
		StreamableHttpServerTransport(String sessionId) {
			this.sessionId = sessionId;
			logger.debug("Session transport {} initialized", sessionId);
		}

		@Override
		public Mono<Void> sendMessage(JSONRPCMessage message) {
			StreamableHttpSseStream sseStream = sseStreams.get(sessionId);
			if (sseStream == null) {
				logger.debug("No SSE stream available for session {}, message will be queued for next connection",
						sessionId);
				// Create a stream that will hold messages until a client connects
				sseStream = getOrCreateSseStream(sessionId);
			}

			try {
				String jsonText = objectMapper.writeValueAsString(message);
				sseStream.sendEvent(MESSAGE_EVENT_TYPE, jsonText);
				logger.debug("Message sent to session {}", sessionId);

				// For responses to requests, we need to complete the stream to avoid
				// hanging
				if (message instanceof McpSchema.JSONRPCResponse) {
					logger.debug("Completing SSE stream after sending response for session {}", sessionId);
					sseStream.complete();
				}

				return Mono.empty();
			}
			catch (Exception e) {
				logger.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
				return Mono.error(e);
			}
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				logger.debug("Closing session transport: {}", sessionId);
				sessions.remove(sessionId);
				StreamableHttpSseStream sseStream = sseStreams.remove(sessionId);
				if (sseStream != null) {
					sseStream.complete();
				}
			});
		}

	}

	/**
	 * Represents an SSE stream for a client connection.
	 */
	public class StreamableHttpSseStream {

		private final Sinks.Many<SseEvent> eventSink = Sinks.many().multicast().onBackpressureBuffer();

		private final Map<String, SseEvent> eventHistory = new ConcurrentHashMap<>();

		private long eventCounter = 0;

		/**
		 * Sends an event on this SSE stream.
		 * @param eventType The event type
		 * @param data The event data
		 */
		public void sendEvent(String eventType, String data) {
			String eventId = String.valueOf(++eventCounter);
			SseEvent event = new SseEvent(eventId, eventType, data);
			eventHistory.put(eventId, event);
			eventSink.tryEmitNext(event);
		}

		/**
		 * Gets the Flux of SSE events for this stream.
		 * @return The Flux of SSE events
		 */
		public Flux<SseEvent> getEventFlux() {
			return eventSink.asFlux();
		}

		/**
		 * Replays events that occurred after the specified event ID.
		 * @param lastEventId The last event ID received by the client
		 */
		public void replayEventsAfter(String lastEventId) {
			try {
				long lastId = Long.parseLong(lastEventId);
				for (long i = lastId + 1; i <= eventCounter; i++) {
					SseEvent event = eventHistory.get(String.valueOf(i));
					if (event != null) {
						eventSink.tryEmitNext(event);
					}
				}
			}
			catch (NumberFormatException e) {
				logger.warn("Invalid last event ID: {}", lastEventId);
			}
		}

		/**
		 * Completes this SSE stream.
		 */
		public void complete() {
			eventSink.tryEmitComplete();
		}

	}

	/**
	 * Represents an SSE event.
	 */
	public record SseEvent(String id, String event, String data) {
	}

	/**
	 * Cleans up resources when the servlet is being destroyed.
	 */
	@Override
	public void destroy() {
		closeGracefully().block();
		super.destroy();
	}

	/**
	 * Helper method to extract headers from an HTTP request.
	 * @param request The HTTP servlet request
	 * @return A map of header names to values
	 */
	private Map<String, String> extractHeaders(HttpServletRequest request) {
		Map<String, String> headers = new HashMap<>();
		Enumeration<String> headerNames = request.getHeaderNames();
		while (headerNames.hasMoreElements()) {
			String name = headerNames.nextElement();
			headers.put(name, request.getHeader(name));
		}
		return headers;
	}

	/**
	 * Creates a new Builder instance for configuring and creating instances of
	 * StreamableHttpServerTransportProvider.
	 * @return A new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of StreamableHttpServerTransportProvider.
	 */
	public static class Builder {

		private ObjectMapper objectMapper = new ObjectMapper();

		private String mcpEndpoint;

		/**
		 * Sets the JSON object mapper to use for message serialization/deserialization.
		 * @param objectMapper The object mapper to use
		 * @return This builder instance for method chaining
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Sets the MCP endpoint path.
		 * @param mcpEndpoint The MCP endpoint path
		 * @return This builder instance for method chaining
		 */
		public Builder withMcpEndpoint(String mcpEndpoint) {
			Assert.hasText(mcpEndpoint, "MCP endpoint must not be empty");
			this.mcpEndpoint = mcpEndpoint;
			return this;
		}

		/**
		 * Builds a new instance of StreamableHttpServerTransportProvider with the
		 * configured settings.
		 * @return A new StreamableHttpServerTransportProvider instance
		 * @throws IllegalStateException if objectMapper or mcpEndpoint is not set
		 */
		public StreamableHttpServerTransportProvider build() {
			if (objectMapper == null) {
				throw new IllegalStateException("ObjectMapper must be set");
			}
			if (mcpEndpoint == null) {
				throw new IllegalStateException("MCP endpoint must be set");
			}
			return new StreamableHttpServerTransportProvider(objectMapper, mcpEndpoint);
		}

	}

}