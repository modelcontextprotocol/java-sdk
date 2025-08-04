/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.KeepAliveScheduler;
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

/**
 * A Servlet-based implementation of the MCP HTTP with Server-Sent Events (SSE) transport
 * specification. This implementation provides similar functionality to
 * WebFluxSseServerTransportProvider but uses the traditional Servlet API instead of
 * WebFlux.
 *
 * <p>
 * The transport handles two types of endpoints:
 * <ul>
 * <li>SSE endpoint (/sse) - Establishes a long-lived connection for server-to-client
 * events</li>
 * <li>Message endpoint (configurable) - Handles client-to-server message requests</li>
 * </ul>
 *
 * <p>
 * Features:
 * <ul>
 * <li>Asynchronous message handling using Servlet 6.0 async support</li>
 * <li>Session management for multiple client connections</li>
 * <li>Graceful shutdown support</li>
 * <li>Error handling and response formatting</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see McpServerTransportProvider
 * @see HttpServlet
 */

@WebServlet(asyncSupported = true)
public class HttpServletSseServerTransportProvider extends HttpServlet implements McpServerTransportProvider {

	/** Logger for this class */
	private static final Logger logger = LoggerFactory.getLogger(HttpServletSseServerTransportProvider.class);

	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";

	/** Default endpoint path for SSE connections */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/** Event type for regular messages */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/** Event type for endpoint information */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	public static final String DEFAULT_BASE_URL = "";

	/** JSON object mapper for serialization/deserialization */
	private final ObjectMapper objectMapper;

	/** Base URL for the server transport */
	private final String baseUrl;

	/** The endpoint path for handling client messages */
	private final String messageEndpoint;

	/** The endpoint path for handling SSE connections */
	private final String sseEndpoint;

	/** Map of active client sessions, keyed by session ID */
	private final Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();

	/** Flag indicating if the transport is in the process of shutting down */
	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	/** Session factory for creating new sessions */
	private McpServerSession.Factory sessionFactory;

	/**
	 * Keep-alive scheduler for managing session pings. Activated if keepAliveInterval is
	 * set. Disabled by default.
	 */
	private KeepAliveScheduler keepAliveScheduler;

	/**
	 * Creates a new HttpServletSseServerTransportProvider instance with a custom SSE
	 * endpoint.
	 * @param objectMapper The JSON object mapper to use for message
	 * serialization/deserialization
	 * @param messageEndpoint The endpoint path where clients will send their messages
	 * @param sseEndpoint The endpoint path where clients will establish SSE connections
	 * @deprecated Use the builder {@link #builder()} instead for better configuration
	 * options.
	 */
	@Deprecated
	public HttpServletSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint,
			String sseEndpoint) {
		this(objectMapper, DEFAULT_BASE_URL, messageEndpoint, sseEndpoint);
	}

	/**
	 * Creates a new HttpServletSseServerTransportProvider instance with a custom SSE
	 * endpoint.
	 * @param objectMapper The JSON object mapper to use for message
	 * serialization/deserialization
	 * @param baseUrl The base URL for the server transport
	 * @param messageEndpoint The endpoint path where clients will send their messages
	 * @param sseEndpoint The endpoint path where clients will establish SSE connections
	 * @deprecated Use the builder {@link #builder()} instead for better configuration
	 * options.
	 */
	@Deprecated
	public HttpServletSseServerTransportProvider(ObjectMapper objectMapper, String baseUrl, String messageEndpoint,
			String sseEndpoint) {
		this(objectMapper, baseUrl, messageEndpoint, sseEndpoint, null);
	}

	/**
	 * Creates a new HttpServletSseServerTransportProvider instance with a custom SSE
	 * endpoint.
	 * @param objectMapper The JSON object mapper to use for message
	 * serialization/deserialization
	 * @param baseUrl The base URL for the server transport
	 * @param messageEndpoint The endpoint path where clients will send their messages
	 * @param sseEndpoint The endpoint path where clients will establish SSE connections
	 * @param keepAliveInterval The interval for keep-alive pings, or null to disable
	 * keep-alive functionality
	 * @deprecated Use the builder {@link #builder()} instead for better configuration
	 * options.
	 */
	@Deprecated
	public HttpServletSseServerTransportProvider(ObjectMapper objectMapper, String baseUrl, String messageEndpoint,
			String sseEndpoint, Duration keepAliveInterval) {

		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;

		if (keepAliveInterval != null) {

			this.keepAliveScheduler = KeepAliveScheduler
				.builder(() -> (isClosing.get()) ? Flux.empty() : Flux.fromIterable(sessions.values()))
				.initialDelay(keepAliveInterval)
				.interval(keepAliveInterval)
				.build();

			this.keepAliveScheduler.start();
		}
	}

	@Override
	public List<String> protocolVersions() {
		return List.of(ProtocolVersions.MCP_2024_11_05);
	}

	/**
	 * Creates a new HttpServletSseServerTransportProvider instance with the default SSE
	 * endpoint.
	 * @param objectMapper The JSON object mapper to use for message
	 * serialization/deserialization
	 * @param messageEndpoint The endpoint path where clients will send their messages
	 */
	public HttpServletSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * Sets the session factory for creating new sessions.
	 * @param sessionFactory The session factory to use
	 */
	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a notification to all connected clients.
	 * @param method The method name for the notification
	 * @param params The parameters for the notification
	 * @return A Mono that completes when the broadcast attempt is finished
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

	/**
	 * Handles GET requests to establish SSE connections.
	 * <p>
	 * This method sets up a new SSE connection when a client connects to the SSE
	 * endpoint. It configures the response headers for SSE, creates a new session, and
	 * sends the initial endpoint information to the client.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(sseEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		response.setContentType("text/event-stream");
		response.setCharacterEncoding(UTF_8);
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Connection", "keep-alive");
		response.setHeader("Access-Control-Allow-Origin", "*");

		String sessionId = UUID.randomUUID().toString();
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);

		PrintWriter writer = response.getWriter();

		// Create a new session transport
		HttpServletMcpSessionTransport sessionTransport = new HttpServletMcpSessionTransport(sessionId, asyncContext,
				writer);

		// Create a new session using the session factory
		McpServerSession session = sessionFactory.create(sessionTransport);
		this.sessions.put(sessionId, session);

		// Send initial endpoint event
		this.sendEvent(writer, ENDPOINT_EVENT_TYPE, this.baseUrl + this.messageEndpoint + "?sessionId=" + sessionId);
	}

	/**
	 * Handles POST requests for client messages.
	 * <p>
	 * This method processes incoming messages from clients, routes them through the
	 * session handler, and sends back the appropriate response. It handles error cases
	 * and formats error responses according to the MCP specification.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(messageEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		// Get the session ID from the request parameter
		String sessionId = request.getParameter("sessionId");
		if (sessionId == null) {
			response.setContentType(APPLICATION_JSON);
			response.setCharacterEncoding(UTF_8);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			String jsonError = objectMapper.writeValueAsString(new McpError("Session ID missing in message endpoint"));
			PrintWriter writer = response.getWriter();
			writer.write(jsonError);
			writer.flush();
			return;
		}

		// Get the session from the sessions map
		McpServerSession session = sessions.get(sessionId);
		if (session == null) {
			response.setContentType(APPLICATION_JSON);
			response.setCharacterEncoding(UTF_8);
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			String jsonError = objectMapper.writeValueAsString(new McpError("Session not found: " + sessionId));
			PrintWriter writer = response.getWriter();
			writer.write(jsonError);
			writer.flush();
			return;
		}

		try {
			BufferedReader reader = request.getReader();
			StringBuilder body = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line);
			}

			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString());

			// Process the message through the session's handle method
			session.handle(message).block(); // Block for Servlet compatibility

			response.setStatus(HttpServletResponse.SC_OK);
		}
		catch (Exception e) {
			logger.error("Error processing message: {}", e.getMessage());
			try {
				McpError mcpError = new McpError(e.getMessage());
				response.setContentType(APPLICATION_JSON);
				response.setCharacterEncoding(UTF_8);
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				String jsonError = objectMapper.writeValueAsString(mcpError);
				PrintWriter writer = response.getWriter();
				writer.write(jsonError);
				writer.flush();
			}
			catch (IOException ex) {
				logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error processing message");
			}
		}
	}

	/**
	 * Initiates a graceful shutdown of the transport.
	 * <p>
	 * This method marks the transport as closing and closes all active client sessions.
	 * New connection attempts will be rejected during shutdown.
	 * @return A Mono that completes when all sessions have been closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		isClosing.set(true);
		logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values()).flatMap(McpServerSession::closeGracefully).then().doOnSuccess(v -> {
			sessions.clear();
			logger.debug("Graceful shutdown completed");
			if (this.keepAliveScheduler != null) {
				this.keepAliveScheduler.shutdown();
			}
		});
	}

	/**
	 * Sends an SSE event to a client.
	 * @param writer The writer to send the event through
	 * @param eventType The type of event (message or endpoint)
	 * @param data The event data
	 * @throws IOException If an error occurs while writing the event
	 */
	private void sendEvent(PrintWriter writer, String eventType, String data) throws IOException {
		writer.write("event: " + eventType + "\n");
		writer.write("data: " + data + "\n\n");
		writer.flush();

		if (writer.checkError()) {
			throw new IOException("Client disconnected");
		}
	}

	/**
	 * Cleans up resources when the servlet is being destroyed.
	 * <p>
	 * This method ensures a graceful shutdown by closing all client connections before
	 * calling the parent's destroy method.
	 */
	@Override
	public void destroy() {
		closeGracefully().block();
		super.destroy();
	}

	/**
	 * Implementation of McpServerTransport for HttpServlet SSE sessions. This class
	 * handles the transport-level communication for a specific client session.
	 */
	private class HttpServletMcpSessionTransport implements McpServerTransport {

		private final String sessionId;

		private final AsyncContext asyncContext;

		private final PrintWriter writer;

		/**
		 * Creates a new session transport with the specified ID and SSE writer.
		 * @param sessionId The unique identifier for this session
		 * @param asyncContext The async context for the session
		 * @param writer The writer for sending server events to the client
		 */
		HttpServletMcpSessionTransport(String sessionId, AsyncContext asyncContext, PrintWriter writer) {
			this.sessionId = sessionId;
			this.asyncContext = asyncContext;
			this.writer = writer;
			logger.debug("Session transport {} initialized with SSE writer", sessionId);
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection.
		 * @param message The JSON-RPC message to send
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.fromRunnable(() -> {
				try {
					String jsonText = objectMapper.writeValueAsString(message);
					sendEvent(writer, MESSAGE_EVENT_TYPE, jsonText);
					logger.debug("Message sent to session {}", sessionId);
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
					sessions.remove(sessionId);
					asyncContext.complete();
				}
			});
		}

		/**
		 * Converts data from one type to another using the configured ObjectMapper.
		 * @param data The source data object to convert
		 * @param typeRef The target type reference
		 * @return The converted object of type T
		 * @param <T> The target type
		 */
		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		/**
		 * Initiates a graceful shutdown of the transport.
		 * @return A Mono that completes when the shutdown is complete
		 */
		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				logger.debug("Closing session transport: {}", sessionId);
				try {
					sessions.remove(sessionId);
					asyncContext.complete();
					logger.debug("Successfully completed async context for session {}", sessionId);
				}
				catch (Exception e) {
					logger.warn("Failed to complete async context for session {}: {}", sessionId, e.getMessage());
				}
			});
		}

		/**
		 * Closes the transport immediately.
		 */
		@Override
		public void close() {
			try {
				sessions.remove(sessionId);
				asyncContext.complete();
				logger.debug("Successfully completed async context for session {}", sessionId);
			}
			catch (Exception e) {
				logger.warn("Failed to complete async context for session {}: {}", sessionId, e.getMessage());
			}
		}

	}

	/**
	 * Creates a new Builder instance for configuring and creating instances of
	 * HttpServletSseServerTransportProvider.
	 * @return A new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of HttpServletSseServerTransportProvider.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * HttpServletSseServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private ObjectMapper objectMapper = new ObjectMapper();

		private String baseUrl = DEFAULT_BASE_URL;

		private String messageEndpoint;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		private Duration keepAliveInterval;

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
		 * Sets the base URL for the server transport.
		 * @param baseUrl The base URL to use
		 * @return This builder instance for method chaining
		 */
		public Builder baseUrl(String baseUrl) {
			Assert.notNull(baseUrl, "Base URL must not be null");
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * Sets the endpoint path where clients will send their messages.
		 * @param messageEndpoint The message endpoint path
		 * @return This builder instance for method chaining
		 */
		public Builder messageEndpoint(String messageEndpoint) {
			Assert.hasText(messageEndpoint, "Message endpoint must not be empty");
			this.messageEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the endpoint path where clients will establish SSE connections.
		 * <p>
		 * If not specified, the default value of {@link #DEFAULT_SSE_ENDPOINT} will be
		 * used.
		 * @param sseEndpoint The SSE endpoint path
		 * @return This builder instance for method chaining
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.hasText(sseEndpoint, "SSE endpoint must not be empty");
			this.sseEndpoint = sseEndpoint;
			return this;
		}

		/**
		 * Sets the interval for keep-alive pings.
		 * <p>
		 * If not specified, keep-alive pings will be disabled.
		 * @param keepAliveInterval The interval duration for keep-alive pings
		 * @return This builder instance for method chaining
		 */
		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Builds a new instance of HttpServletSseServerTransportProvider with the
		 * configured settings.
		 * @return A new HttpServletSseServerTransportProvider instance
		 * @throws IllegalStateException if objectMapper or messageEndpoint is not set
		 */
		public HttpServletSseServerTransportProvider build() {
			if (objectMapper == null) {
				throw new IllegalStateException("ObjectMapper must be set");
			}
			if (messageEndpoint == null) {
				throw new IllegalStateException("MessageEndpoint must be set");
			}
			return new HttpServletSseServerTransportProvider(objectMapper, baseUrl, messageEndpoint, sseEndpoint,
					keepAliveInterval);
		}

	}

}
