/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.DefaultMcpTransportContext;
import io.modelcontextprotocol.server.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Server-side implementation of the Model Context Protocol (MCP) streamable transport
 * layer using HTTP with Server-Sent Events (SSE) through HttpServlet. This implementation
 * provides a bridge between synchronous HttpServlet operations and reactive programming
 * patterns to maintain compatibility with the reactive transport interface.
 *
 * <p>
 * This is the HttpServlet equivalent of
 * {@link io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider}
 * for the core MCP module, providing streamable HTTP transport functionality without
 * Spring dependencies.
 *
 * @author Zachary German
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpStreamableServerTransportProvider
 * @see HttpServlet
 */
@WebServlet(asyncSupported = true)
public class HttpServletStreamableServerTransportProvider extends HttpServlet
		implements McpStreamableServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(HttpServletStreamableServerTransportProvider.class);

	/**
	 * Event type for JSON-RPC messages sent through the SSE connection.
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/**
	 * Event type for sending the message endpoint URI to clients.
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/**
	 * Header name for the response media types accepted by the requester.
	 */
	private static final String ACCEPT = "Accept";

	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String TEXT_EVENT_STREAM = "text/event-stream";

	public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";

	/**
	 * The endpoint URI where clients should send their JSON-RPC messages. Defaults to
	 * "/mcp".
	 */
	private final String mcpEndpoint;

	/**
	 * Flag indicating whether DELETE requests are disallowed on the endpoint.
	 */
	private final boolean disallowDelete;

	private final ObjectMapper objectMapper;

	private McpStreamableServerSession.Factory sessionFactory;

	/**
	 * Map of active client sessions, keyed by mcp-session-id.
	 */
	private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

	private McpTransportContextExtractor<HttpServletRequest> contextExtractor;

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	/**
	 * Keep-alive scheduler for managing session pings. Activated if keepAliveInterval is
	 * set. Disabled by default.
	 */
	private KeepAliveScheduler keepAliveScheduler;

	/**
	 * Constructs a new HttpServletStreamableServerTransportProvider instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of messages.
	 * @param mcpEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages via HTTP. This endpoint will handle GET, POST, and DELETE requests.
	 * @param disallowDelete Whether to disallow DELETE requests on the endpoint.
	 * @param contextExtractor The extractor for transport context from the request.
	 * @throws IllegalArgumentException if any parameter is null
	 */
	private HttpServletStreamableServerTransportProvider(ObjectMapper objectMapper, String mcpEndpoint,
			boolean disallowDelete, McpTransportContextExtractor<HttpServletRequest> contextExtractor,
			Duration keepAliveInterval) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
		Assert.notNull(contextExtractor, "Context extractor must not be null");

		this.objectMapper = objectMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.disallowDelete = disallowDelete;
		this.contextExtractor = contextExtractor;

		if (keepAliveInterval != null) {

			this.keepAliveScheduler = KeepAliveScheduler
				.builder(() -> (isClosing) ? Flux.empty() : Flux.fromIterable(sessions.values()))
				.initialDelay(keepAliveInterval)
				.interval(keepAliveInterval)
				.build();

			this.keepAliveScheduler.start();
		}

	}

	@Override
	public List<String> protocolVersions() {
		return List.of(ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26);
	}

	@Override
	public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a notification to all connected clients through their SSE connections.
	 * If any errors occur during sending to a particular client, they are logged but
	 * don't prevent sending to other clients.
	 * @param method The method name for the notification
	 * @param params The parameters for the notification
	 * @return A Mono that completes when the broadcast attempt is finished
	 */
	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		if (this.sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", this.sessions.size());

		return Mono.fromRunnable(() -> {
			this.sessions.values().parallelStream().forEach(session -> {
				try {
					session.sendNotification(method, params).block();
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage());
				}
			});
		});
	}

	/**
	 * Initiates a graceful shutdown of the transport.
	 * @return A Mono that completes when all cleanup operations are finished
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			this.isClosing = true;
			logger.debug("Initiating graceful shutdown with {} active sessions", this.sessions.size());

			this.sessions.values().parallelStream().forEach(session -> {
				try {
					session.closeGracefully().block();
				}
				catch (Exception e) {
					logger.error("Failed to close session {}: {}", session.getId(), e.getMessage());
				}
			});

			this.sessions.clear();
			logger.debug("Graceful shutdown completed");
		}).then().doOnSuccess(v -> {
			sessions.clear();
			logger.debug("Graceful shutdown completed");
			if (this.keepAliveScheduler != null) {
				this.keepAliveScheduler.shutdown();
			}
		});
	}

	/**
	 * Handles GET requests to establish SSE connections and message replay.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(mcpEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		if (this.isClosing) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		List<String> badRequestErrors = new ArrayList<>();

		String accept = request.getHeader(ACCEPT);
		if (accept == null || !accept.contains(TEXT_EVENT_STREAM)) {
			badRequestErrors.add("text/event-stream required in Accept header");
		}

		String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);

		if (sessionId == null || sessionId.isBlank()) {
			badRequestErrors.add("Session ID required in mcp-session-id header");
		}

		if (!badRequestErrors.isEmpty()) {
			String combinedMessage = String.join("; ", badRequestErrors);
			this.responseError(response, HttpServletResponse.SC_BAD_REQUEST, new McpError(combinedMessage));
			return;
		}

		McpStreamableServerSession session = this.sessions.get(sessionId);

		if (session == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		logger.debug("Handling GET request for session: {}", sessionId);

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		try {
			response.setContentType(TEXT_EVENT_STREAM);
			response.setCharacterEncoding(UTF_8);
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader("Connection", "keep-alive");
			response.setHeader("Access-Control-Allow-Origin", "*");

			AsyncContext asyncContext = request.startAsync();
			asyncContext.setTimeout(0);

			HttpServletStreamableMcpSessionTransport sessionTransport = new HttpServletStreamableMcpSessionTransport(
					sessionId, asyncContext, response.getWriter());

			// Check if this is a replay request
			if (request.getHeader(HttpHeaders.LAST_EVENT_ID) != null) {
				String lastId = request.getHeader(HttpHeaders.LAST_EVENT_ID);

				try {
					session.replay(lastId)
						.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
						.toIterable()
						.forEach(message -> {
							try {
								sessionTransport.sendMessage(message)
									.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
									.block();
							}
							catch (Exception e) {
								logger.error("Failed to replay message: {}", e.getMessage());
								asyncContext.complete();
							}
						});
				}
				catch (Exception e) {
					logger.error("Failed to replay messages: {}", e.getMessage());
					asyncContext.complete();
				}
			}
			else {
				// Establish new listening stream
				McpStreamableServerSession.McpStreamableServerSessionStream listeningStream = session
					.listeningStream(sessionTransport);

				asyncContext.addListener(new jakarta.servlet.AsyncListener() {
					@Override
					public void onComplete(jakarta.servlet.AsyncEvent event) throws IOException {
						logger.debug("SSE connection completed for session: {}", sessionId);
						listeningStream.close();
					}

					@Override
					public void onTimeout(jakarta.servlet.AsyncEvent event) throws IOException {
						logger.debug("SSE connection timed out for session: {}", sessionId);
						listeningStream.close();
					}

					@Override
					public void onError(jakarta.servlet.AsyncEvent event) throws IOException {
						logger.debug("SSE connection error for session: {}", sessionId);
						listeningStream.close();
					}

					@Override
					public void onStartAsync(jakarta.servlet.AsyncEvent event) throws IOException {
						// No action needed
					}
				});
			}
		}
		catch (Exception e) {
			logger.error("Failed to handle GET request for session {}: {}", sessionId, e.getMessage());
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Handles POST requests for incoming JSON-RPC messages from clients.
	 * @param request The HTTP servlet request containing the JSON-RPC message
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(mcpEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		if (this.isClosing) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		List<String> badRequestErrors = new ArrayList<>();

		String accept = request.getHeader(ACCEPT);
		if (accept == null || !accept.contains(TEXT_EVENT_STREAM)) {
			badRequestErrors.add("text/event-stream required in Accept header");
		}
		if (accept == null || !accept.contains(APPLICATION_JSON)) {
			badRequestErrors.add("application/json required in Accept header");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		try {
			BufferedReader reader = request.getReader();
			StringBuilder body = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line);
			}

			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString());

			// Handle initialization request
			if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest
					&& jsonrpcRequest.method().equals(McpSchema.METHOD_INITIALIZE)) {
				if (!badRequestErrors.isEmpty()) {
					String combinedMessage = String.join("; ", badRequestErrors);
					this.responseError(response, HttpServletResponse.SC_BAD_REQUEST, new McpError(combinedMessage));
					return;
				}

				McpSchema.InitializeRequest initializeRequest = objectMapper.convertValue(jsonrpcRequest.params(),
						new TypeReference<McpSchema.InitializeRequest>() {
						});
				McpStreamableServerSession.McpStreamableServerSessionInit init = this.sessionFactory
					.startSession(initializeRequest);
				this.sessions.put(init.session().getId(), init.session());

				try {
					McpSchema.InitializeResult initResult = init.initResult().block();

					response.setContentType(APPLICATION_JSON);
					response.setCharacterEncoding(UTF_8);
					response.setHeader(HttpHeaders.MCP_SESSION_ID, init.session().getId());
					response.setStatus(HttpServletResponse.SC_OK);

					String jsonResponse = objectMapper.writeValueAsString(new McpSchema.JSONRPCResponse(
							McpSchema.JSONRPC_VERSION, jsonrpcRequest.id(), initResult, null));

					PrintWriter writer = response.getWriter();
					writer.write(jsonResponse);
					writer.flush();
					return;
				}
				catch (Exception e) {
					logger.error("Failed to initialize session: {}", e.getMessage());
					this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
							new McpError("Failed to initialize session: " + e.getMessage()));
					return;
				}
			}

			String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);

			if (sessionId == null || sessionId.isBlank()) {
				badRequestErrors.add("Session ID required in mcp-session-id header");
			}

			if (!badRequestErrors.isEmpty()) {
				String combinedMessage = String.join("; ", badRequestErrors);
				this.responseError(response, HttpServletResponse.SC_BAD_REQUEST, new McpError(combinedMessage));
				return;
			}

			McpStreamableServerSession session = this.sessions.get(sessionId);

			if (session == null) {
				this.responseError(response, HttpServletResponse.SC_NOT_FOUND,
						new McpError("Session not found: " + sessionId));
				return;
			}

			if (message instanceof McpSchema.JSONRPCResponse jsonrpcResponse) {
				session.accept(jsonrpcResponse)
					.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
					.block();
				response.setStatus(HttpServletResponse.SC_ACCEPTED);
			}
			else if (message instanceof McpSchema.JSONRPCNotification jsonrpcNotification) {
				session.accept(jsonrpcNotification)
					.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
					.block();
				response.setStatus(HttpServletResponse.SC_ACCEPTED);
			}
			else if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
				// For streaming responses, we need to return SSE
				response.setContentType(TEXT_EVENT_STREAM);
				response.setCharacterEncoding(UTF_8);
				response.setHeader("Cache-Control", "no-cache");
				response.setHeader("Connection", "keep-alive");
				response.setHeader("Access-Control-Allow-Origin", "*");

				AsyncContext asyncContext = request.startAsync();
				asyncContext.setTimeout(0);

				HttpServletStreamableMcpSessionTransport sessionTransport = new HttpServletStreamableMcpSessionTransport(
						sessionId, asyncContext, response.getWriter());

				try {
					session.responseStream(jsonrpcRequest, sessionTransport)
						.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
						.block();
				}
				catch (Exception e) {
					logger.error("Failed to handle request stream: {}", e.getMessage());
					asyncContext.complete();
				}
			}
			else {
				this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						new McpError("Unknown message type"));
			}
		}
		catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
					new McpError("Invalid message format: " + e.getMessage()));
		}
		catch (Exception e) {
			logger.error("Error handling message: {}", e.getMessage());
			try {
				this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						new McpError("Error processing message: " + e.getMessage()));
			}
			catch (IOException ex) {
				logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error processing message");
			}
		}
	}

	/**
	 * Handles DELETE requests for session deletion.
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

		if (this.isClosing) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		if (this.disallowDelete) {
			response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		if (request.getHeader(HttpHeaders.MCP_SESSION_ID) == null) {
			this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
					new McpError("Session ID required in mcp-session-id header"));
			return;
		}

		String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);
		McpStreamableServerSession session = this.sessions.get(sessionId);

		if (session == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		try {
			session.delete().contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)).block();
			this.sessions.remove(sessionId);
			response.setStatus(HttpServletResponse.SC_OK);
		}
		catch (Exception e) {
			logger.error("Failed to delete session {}: {}", sessionId, e.getMessage());
			try {
				this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						new McpError(e.getMessage()));
			}
			catch (IOException ex) {
				logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error deleting session");
			}
		}
	}

	public void responseError(HttpServletResponse response, int httpCode, McpError mcpError) throws IOException {
		response.setContentType(APPLICATION_JSON);
		response.setCharacterEncoding(UTF_8);
		response.setStatus(httpCode);
		String jsonError = objectMapper.writeValueAsString(mcpError);
		PrintWriter writer = response.getWriter();
		writer.write(jsonError);
		writer.flush();
		return;
	}

	/**
	 * Sends an SSE event to a client with a specific ID.
	 * @param writer The writer to send the event through
	 * @param eventType The type of event (message or endpoint)
	 * @param data The event data
	 * @param id The event ID
	 * @throws IOException If an error occurs while writing the event
	 */
	private void sendEvent(PrintWriter writer, String eventType, String data, String id) throws IOException {
		if (id != null) {
			writer.write("id: " + id + "\n");
		}
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
	 * Implementation of McpStreamableServerTransport for HttpServlet SSE sessions. This
	 * class handles the transport-level communication for a specific client session.
	 *
	 * <p>
	 * This class is thread-safe and uses a ReentrantLock to synchronize access to the
	 * underlying PrintWriter to prevent race conditions when multiple threads attempt to
	 * send messages concurrently.
	 */

	private class HttpServletStreamableMcpSessionTransport implements McpStreamableServerTransport {

		private final String sessionId;

		private final AsyncContext asyncContext;

		private final PrintWriter writer;

		private volatile boolean closed = false;

		private final ReentrantLock lock = new ReentrantLock();

		/**
		 * Creates a new session transport with the specified ID and SSE writer.
		 * @param sessionId The unique identifier for this session
		 * @param asyncContext The async context for the session
		 * @param writer The writer for sending server events to the client
		 */
		HttpServletStreamableMcpSessionTransport(String sessionId, AsyncContext asyncContext, PrintWriter writer) {
			this.sessionId = sessionId;
			this.asyncContext = asyncContext;
			this.writer = writer;
			logger.debug("Streamable session transport {} initialized with SSE writer", sessionId);
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection.
		 * @param message The JSON-RPC message to send
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return sendMessage(message, null);
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection with a
		 * specific message ID.
		 * @param message The JSON-RPC message to send
		 * @param messageId The message ID for SSE event identification
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
			return Mono.fromRunnable(() -> {
				if (this.closed) {
					logger.debug("Attempted to send message to closed session: {}", this.sessionId);
					return;
				}

				lock.lock();
				try {
					if (this.closed) {
						logger.debug("Session {} was closed during message send attempt", this.sessionId);
						return;
					}

					String jsonText = objectMapper.writeValueAsString(message);
					HttpServletStreamableServerTransportProvider.this.sendEvent(writer, MESSAGE_EVENT_TYPE, jsonText,
							messageId != null ? messageId : this.sessionId);
					logger.debug("Message sent to session {} with ID {}", this.sessionId, messageId);
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", this.sessionId, e.getMessage());
					HttpServletStreamableServerTransportProvider.this.sessions.remove(this.sessionId);
					this.asyncContext.complete();
				}
				finally {
					lock.unlock();
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
				HttpServletStreamableMcpSessionTransport.this.close();
			});
		}

		/**
		 * Closes the transport immediately.
		 */
		@Override
		public void close() {
			lock.lock();
			try {
				if (this.closed) {
					logger.debug("Session transport {} already closed", this.sessionId);
					return;
				}

				this.closed = true;

				// HttpServletStreamableServerTransportProvider.this.sessions.remove(this.sessionId);
				this.asyncContext.complete();
				logger.debug("Successfully completed async context for session {}", sessionId);
			}
			catch (Exception e) {
				logger.warn("Failed to complete async context for session {}: {}", sessionId, e.getMessage());
			}
			finally {
				lock.unlock();
			}
		}

	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of
	 * {@link HttpServletStreamableServerTransportProvider}.
	 */
	public static class Builder {

		private ObjectMapper objectMapper;

		private String mcpEndpoint = "/mcp";

		private boolean disallowDelete = false;

		private McpTransportContextExtractor<HttpServletRequest> contextExtractor = (serverRequest, context) -> context;

		private Duration keepAliveInterval;

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
		 * @param mcpEndpoint The MCP endpoint URI. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if mcpEndpoint is null
		 */
		public Builder mcpEndpoint(String mcpEndpoint) {
			Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
			this.mcpEndpoint = mcpEndpoint;
			return this;
		}

		/**
		 * Sets whether to disallow DELETE requests on the endpoint.
		 * @param disallowDelete true to disallow DELETE requests, false otherwise
		 * @return this builder instance
		 */
		public Builder disallowDelete(boolean disallowDelete) {
			this.disallowDelete = disallowDelete;
			return this;
		}

		/**
		 * Sets the context extractor for extracting transport context from the request.
		 * @param contextExtractor The context extractor to use. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if contextExtractor is null
		 */
		public Builder contextExtractor(McpTransportContextExtractor<HttpServletRequest> contextExtractor) {
			Assert.notNull(contextExtractor, "Context extractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Sets the keep-alive interval for the transport. If set, a keep-alive scheduler
		 * will be activated to periodically ping active sessions.
		 * @param keepAliveInterval The interval for keep-alive pings. If null, no
		 * keep-alive will be scheduled.
		 * @return this builder instance
		 */
		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Builds a new instance of {@link HttpServletStreamableServerTransportProvider}
		 * with the configured settings.
		 * @return A new HttpServletStreamableServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public HttpServletStreamableServerTransportProvider build() {
			Assert.notNull(this.objectMapper, "ObjectMapper must be set");
			Assert.notNull(this.mcpEndpoint, "MCP endpoint must be set");

			return new HttpServletStreamableServerTransportProvider(this.objectMapper, this.mcpEndpoint,
					this.disallowDelete, this.contextExtractor, this.keepAliveInterval);
		}

	}

}
