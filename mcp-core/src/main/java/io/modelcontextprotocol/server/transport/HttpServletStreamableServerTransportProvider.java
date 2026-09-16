/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

	/**
	 * Default maximum size of a single request body: 16 MiB (16 * 1024 * 1024 bytes).
	 */
	private static final int DEFAULT_REQUEST_MAX_SIZE = 16 * 1024 * 1024;

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

	private final McpJsonMapper jsonMapper;

	/**
	 * Maximum size, in bytes, of a single request body accepted by this transport.
	 */
	private final int requestMaxSize;

	private McpStreamableServerSession.Factory sessionFactory;

	/**
	 * Map of active client sessions, keyed by mcp-session-id.
	 */
	private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

	/**
	 * IDs of the sessions which received a request since the last sweep. The set is
	 * swapped for an empty one on every sweep, so it only ever holds the activity of the
	 * current interval.
	 */
	private final AtomicReference<Set<String>> activeSessions = new AtomicReference<>(ConcurrentHashMap.newKeySet());

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
	 * Periodic eviction of the sessions no client came back to. Activated if
	 * sessionSweepInterval is set.
	 */
	private Disposable sessionSweeper;

	/**
	 * Security validator for validating HTTP requests.
	 */
	private final ServerHttpHeaderValidator httpHeaderValidator;

	/**
	 * Constructs a new HttpServletStreamableServerTransportProvider instance.
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization of
	 * messages.
	 * @param mcpEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages via HTTP. This endpoint will handle GET, POST, and DELETE requests.
	 * @param disallowDelete Whether to disallow DELETE requests on the endpoint.
	 * @param contextExtractor The extractor for transport context from the request.
	 * @param keepAliveInterval The interval for keep-alive pings. If null, no keep-alive
	 * will be scheduled.
	 * @param httpHeaderValidator The HTTP header validator for validating HTTP requests.
	 * @param requestMaxSize The maximum size, in bytes, of a single request body. Must be
	 * positive.
	 * @param sessionSweepInterval The interval at which idle sessions are evicted. If
	 * null, no sweeping will be scheduled.
	 * @throws IllegalArgumentException if any parameter is null
	 */
	private HttpServletStreamableServerTransportProvider(McpJsonMapper jsonMapper, String mcpEndpoint,
			boolean disallowDelete, McpTransportContextExtractor<HttpServletRequest> contextExtractor,
			Duration keepAliveInterval, ServerHttpHeaderValidator httpHeaderValidator, int requestMaxSize,
			Duration sessionSweepInterval) {
		Assert.notNull(jsonMapper, "JsonMapper must not be null");
		Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
		Assert.notNull(contextExtractor, "Context extractor must not be null");
		Assert.notNull(httpHeaderValidator, "HTTP header validator must not be null");
		Assert.isTrue(requestMaxSize > 0, "requestMaxSize must be positive");

		this.jsonMapper = jsonMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.disallowDelete = disallowDelete;
		this.contextExtractor = contextExtractor;
		this.httpHeaderValidator = httpHeaderValidator;
		this.requestMaxSize = requestMaxSize;

		if (keepAliveInterval != null) {

			this.keepAliveScheduler = KeepAliveScheduler.builder(this::sessionsToPing)
				.initialDelay(keepAliveInterval)
				.interval(keepAliveInterval)
				.onPingFailure(session -> {
					// The stream the ping was written to is dead. The session survives:
					// the client may reconnect to it, and the idle timeout reclaims it if
					// it never does.
					if (session instanceof McpStreamableServerSession streamableSession) {
						streamableSession.releaseListeningStream();
					}
				})
				.build();

			this.keepAliveScheduler.start();
		}

		if (sessionSweepInterval != null) {
			this.sessionSweeper = Flux.interval(sessionSweepInterval, sessionSweepInterval, Schedulers.boundedElastic())
				.doOnNext(tick -> {
					// Each sweep runs in its own subscription, so that a sweep failing
					// does not terminate the interval and disable sweeping altogether
					Mono.fromRunnable(this::sweepSessions)
						.doOnError(e -> logger.error("Session sweep failed", e))
						.onErrorComplete()
						.subscribe();
				})
				.onErrorComplete(error -> {
					logger.error("Session sweeper error", error);
					return true;
				})
				.subscribe();
		}

	}

	/**
	 * Evicts the sessions no client is using anymore. A session is kept if it holds an
	 * open stream, which a client can legitimately sit on without ever writing to it, or
	 * if it received a request during the interval which just elapsed. Anything else is a
	 * session whose client went away without deleting it: the protocol lets a client
	 * reconnect to a session, so nothing else ever reclaims it.
	 */
	private void sweepSessions() {
		if (this.isClosing) {
			return;
		}
		Set<String> active = this.activeSessions.getAndSet(ConcurrentHashMap.newKeySet());
		this.sessions.values().removeIf(session -> {
			if (session.hasOpenStream() || active.contains(session.getId())) {
				return false;
			}
			logger.debug("Evicting idle session {}", session.getId());
			try {
				session.closeGracefully().block();
			}
			catch (Exception e) {
				logger.warn("Failed to close idle session {}: {}", session.getId(), e.getMessage());
			}
			return true;
		});
	}

	/**
	 * Records that the given session is being used, so that the next sweep does not
	 * mistake it for a session whose client is gone.
	 * @param sessionId the session the current request belongs to
	 */
	private void markSessionActive(String sessionId) {
		this.activeSessions.get().add(sessionId);
	}

	/**
	 * Returns the sessions a keep-alive ping can be sent to, that is the sessions having
	 * a listening stream. A session without one, e.g. a client which only ever issues
	 * POST requests, has nothing to write a ping to: pinging it would fail on every
	 * interval without ever telling us anything about the client being alive.
	 * @return the sessions to ping
	 */
	private Flux<McpSession> sessionsToPing() {
		if (this.isClosing) {
			return Flux.empty();
		}
		return Flux.fromIterable(this.sessions.values())
			.filter(McpStreamableServerSession::hasListeningStream)
			.cast(McpSession.class);
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
					logger.info("Failed to send message to session {}: {}", session.getId(), e.getMessage());
				}
			});
		});
	}

	@Override
	public Mono<Void> notifyClient(String sessionId, String method, Object params) {
		return Mono.defer(() -> {
			McpStreamableServerSession session = this.sessions.get(sessionId);
			if (session == null) {
				logger.debug("Session {} not found", sessionId);
				return Mono.empty();
			}
			return session.sendNotification(method, params);
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
					logger.warn("Failed to close session {}: {}", session.getId(), e.getMessage());
				}
			});

			this.sessions.clear();
		}).then().doOnSuccess(v -> {
			sessions.clear();
			logger.debug("Graceful shutdown completed");
			if (this.keepAliveScheduler != null) {
				this.keepAliveScheduler.shutdown();
			}
			if (this.sessionSweeper != null) {
				this.sessionSweeper.dispose();
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

		try {
			this.httpHeaderValidator.validate(new HttpServletHeaderAccessor(request));
		}
		catch (ServerTransportSecurityException e) {
			response.sendError(e.getStatusCode(), e.getMessage());
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
			this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
					McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND).message(combinedMessage).build());
			return;
		}

		McpStreamableServerSession session = this.sessions.get(sessionId);

		if (session == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		this.markSessionActive(sessionId);

		logger.debug("Handling GET request for session: {}", sessionId);

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		try {
			response.setContentType(TEXT_EVENT_STREAM);
			response.setCharacterEncoding(UTF_8);
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader("Connection", "keep-alive");

			AsyncContext asyncContext = request.startAsync();
			asyncContext.setTimeout(0);

			HttpServletStreamableMcpSessionTransport sessionTransport = new HttpServletStreamableMcpSessionTransport(
					sessionId, asyncContext, response.getWriter());

			// Replay the messages the client missed while its stream was broken
			String lastEventId = request.getHeader(HttpHeaders.LAST_EVENT_ID);
			if (lastEventId != null
					&& !this.tryReplayMissedMessages(session, lastEventId, sessionTransport, transportContext)) {
				// The replay failed and already closed the transport
				return;
			}

			// Establish the listening stream. Resumed streams are registered too, so
			// that the session keeps delivering messages to the reconnected client and
			// the async context is completed once the client goes away.
			McpStreamableServerSession.McpStreamableServerSessionStream listeningStream = session
				.listeningStream(sessionTransport);

			registerAsyncLifecycle(asyncContext, sessionId, listeningStream::releaseTransport);
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
		if (request.getContentLengthLong() > this.requestMaxSize) {
			response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			return;
		}

		try {
			this.httpHeaderValidator.validate(new HttpServletHeaderAccessor(request));
		}
		catch (ServerTransportSecurityException e) {
			response.sendError(e.getStatusCode(), e.getMessage());
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

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		try {
			String body = HttpServletRequestUtils.readBody(request, this.requestMaxSize);

			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

			// Handle initialization request
			if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest
					&& jsonrpcRequest.method().equals(McpSchema.METHOD_INITIALIZE)) {
				if (!badRequestErrors.isEmpty()) {
					String combinedMessage = String.join("; ", badRequestErrors);
					this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
							McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND).message(combinedMessage).build());
					return;
				}

				McpSchema.InitializeRequest initializeRequest = jsonMapper.convertValue(jsonrpcRequest.params(),
						new TypeRef<McpSchema.InitializeRequest>() {
						});
				McpStreamableServerSession.McpStreamableServerSessionInit init = this.sessionFactory
					.startSession(initializeRequest);
				this.markSessionActive(init.session().getId());
				this.sessions.put(init.session().getId(), init.session());

				try {
					McpSchema.InitializeResult initResult = init.initResult().block();

					response.setContentType(APPLICATION_JSON);
					response.setCharacterEncoding(UTF_8);
					response.setHeader(HttpHeaders.MCP_SESSION_ID, init.session().getId());
					response.setStatus(HttpServletResponse.SC_OK);

					String jsonResponse = jsonMapper
						.writeValueAsString(McpSchema.JSONRPCResponse.result(jsonrpcRequest.id(), initResult));

					PrintWriter writer = response.getWriter();
					writer.write(jsonResponse);
					writer.flush();
					return;
				}
				catch (Exception e) {
					logger.error("Failed to initialize session: {}", e.getMessage());
					this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
							McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
								.message("Failed to initialize session: " + e.getMessage())
								.build());
					return;
				}
			}

			String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);

			if (sessionId == null || sessionId.isBlank()) {
				badRequestErrors.add("Session ID required in mcp-session-id header");
			}

			if (!badRequestErrors.isEmpty()) {
				String combinedMessage = String.join("; ", badRequestErrors);
				this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
						McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND).message(combinedMessage).build());
				return;
			}

			McpStreamableServerSession session = this.sessions.get(sessionId);

			if (session == null) {
				this.responseError(response, HttpServletResponse.SC_NOT_FOUND,
						McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message("Session not found: " + sessionId)
							.build());
				return;
			}

			this.markSessionActive(sessionId);

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

				AsyncContext asyncContext = request.startAsync();
				asyncContext.setTimeout(0);

				HttpServletStreamableMcpSessionTransport sessionTransport = new HttpServletStreamableMcpSessionTransport(
						sessionId, asyncContext, response.getWriter());

				// The listener is given the stream rather than its transport, so that the
				// end of the connection detaches the stream from the session instead of
				// only dropping the socket: a stream outliving its connection keeps the
				// session looking busy and spares it from the sweeper
				McpStreamableServerSession.McpStreamableServerSessionStream responseStream = session
					.responseStream(sessionTransport);
				registerAsyncLifecycle(asyncContext, sessionId, responseStream::releaseTransport);

				try {
					responseStream.handle(jsonrpcRequest)
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
						McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Unknown message type").build());
			}
		}
		catch (MaxSizeExceededException e) {
			response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
		}
		catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
					McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
						.message("Invalid message format: " + e.getMessage())
						.build());
		}
		catch (Exception e) {
			logger.error("Error handling message: {}", e.getMessage());
			try {
				this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message("Error processing message: " + e.getMessage())
							.build());
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

		try {
			this.httpHeaderValidator.validate(new HttpServletHeaderAccessor(request));
		}
		catch (ServerTransportSecurityException e) {
			response.sendError(e.getStatusCode(), e.getMessage());
			return;
		}

		if (this.disallowDelete) {
			response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		if (request.getHeader(HttpHeaders.MCP_SESSION_ID) == null) {
			this.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
					McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND)
						.message("Session ID required in mcp-session-id header")
						.build());
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
						McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(e.getMessage()).build());
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
		String jsonError = jsonMapper.writeValueAsString(mcpError);
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
	 * Replays the messages the client missed while its SSE stream was broken.
	 * @param session the session the client is resuming
	 * @param lastEventId the ID of the last event received by the client
	 * @param sessionTransport the transport of the resumed SSE stream
	 * @param transportContext the context extracted from the request
	 * @return {@code true} if the replay completed, {@code false} if it failed, in which
	 * case the transport has been closed
	 */
	private static boolean tryReplayMissedMessages(McpStreamableServerSession session, String lastEventId,
			McpStreamableServerTransport sessionTransport, McpTransportContext transportContext) {
		try {
			for (McpSchema.JSONRPCMessage message : session.replay(lastEventId)
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
				.toIterable()) {
				sessionTransport.sendMessage(message)
					.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
					.block();
			}
			return true;
		}
		catch (Exception e) {
			logger.error("Failed to replay messages for session {}: {}", session.getId(), e.getMessage());
			sessionTransport.close();
			return false;
		}
	}

	/**
	 * Registers a listener releasing the SSE stream carried by the given asynchronous
	 * request once the container is done with it, whether the client went away, the
	 * request timed out or it errored. Without this, the connection is left open, holding
	 * on to a socket and a container thread, until the process restarts.
	 * @param asyncContext the asynchronous context of the SSE request
	 * @param sessionId the session the stream belongs to
	 * @param onConnectionEnd the action releasing the stream
	 */
	private static void registerAsyncLifecycle(AsyncContext asyncContext, String sessionId, Runnable onConnectionEnd) {
		asyncContext.addListener(new AsyncListener() {
			@Override
			public void onComplete(AsyncEvent event) throws IOException {
				logger.debug("SSE connection completed for session: {}", sessionId);
				onConnectionEnd.run();
			}

			@Override
			public void onTimeout(AsyncEvent event) throws IOException {
				logger.debug("SSE connection timed out for session: {}", sessionId);
				onConnectionEnd.run();
			}

			@Override
			public void onError(AsyncEvent event) throws IOException {
				logger.debug("SSE connection error for session: {}", sessionId);
				onConnectionEnd.run();
			}

			@Override
			public void onStartAsync(AsyncEvent event) throws IOException {
				// No action needed
			}
		});
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

					String jsonText = jsonMapper.writeValueAsString(message);
					HttpServletStreamableServerTransportProvider.this.sendEvent(writer, MESSAGE_EVENT_TYPE, jsonText,
							messageId != null ? messageId : this.sessionId);
					logger.debug("Message sent to session {} with ID {}", this.sessionId, messageId);
				}
				catch (Exception e) {
					// The connection is gone, the session is not: the client may come
					// back for it, and the sweeper reclaims it if it never does
					logger.error("Failed to send message to session {}: {}", this.sessionId, e.getMessage());
					this.close();
					// Surfaced to the caller rather than swallowed: whoever is writing to
					// this stream has to learn that it no longer leads anywhere, or it
					// keeps producing messages for a client which is gone. A request
					// being streamed a response would never finish, holding on to the
					// container thread which has to be given back before the end of the
					// connection can be acted upon.
					throw new McpTransportException("Failed to send message to session " + this.sessionId, e);
				}
				finally {
					lock.unlock();
				}
			});
		}

		/**
		 * Converts data from one type to another using the configured JsonMapper.
		 * @param data The source data object to convert
		 * @param typeRef The target type reference
		 * @return The converted object of type T
		 * @param <T> The target type
		 */
		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return jsonMapper.convertValue(data, typeRef);
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

	@Override
	public List<String> protocolVersions() {
		return List.of(ProtocolVersions.MCP_2025_03_26, ProtocolVersions.MCP_2025_06_18,
				ProtocolVersions.MCP_2025_11_25);
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of
	 * {@link HttpServletStreamableServerTransportProvider}.
	 */
	public static class Builder {

		private McpJsonMapper jsonMapper;

		private String mcpEndpoint = "/mcp";

		private boolean disallowDelete = false;

		private McpTransportContextExtractor<HttpServletRequest> contextExtractor = (
				serverRequest) -> McpTransportContext.EMPTY;

		private Duration keepAliveInterval;

		private ServerHttpHeaderValidator httpHeaderValidator = ServerHttpHeaderValidator.NOOP;

		private int requestMaxSize = DEFAULT_REQUEST_MAX_SIZE;

		private Duration sessionSweepInterval = Duration.ofMinutes(30);

		/**
		 * Sets the JsonMapper to use for JSON serialization/deserialization of MCP
		 * messages.
		 * @param jsonMapper The JsonMapper instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if JsonMapper is null
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
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
		 * Sets the security validator for validating HTTP requests.
		 * @param securityValidator The security validator to use. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if securityValidator is null
		 * @deprecated Use {@link #httpHeaderValidator(ServerHttpHeaderValidator)}
		 * instead.
		 */
		@Deprecated
		public Builder securityValidator(ServerTransportSecurityValidator securityValidator) {
			Assert.notNull(securityValidator, "Security validator must not be null");
			this.httpHeaderValidator = ServerTransportSecurityValidator.toHttpHeaderValidator(securityValidator);
			return this;
		}

		/**
		 * Sets the HTTP header validator for validating HTTP requests.
		 * @param httpHeaderValidator The HTTP header validator to use. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if httpHeaderValidator is null
		 */
		public Builder httpHeaderValidator(ServerHttpHeaderValidator httpHeaderValidator) {
			Assert.notNull(httpHeaderValidator, "HTTP header validator must not be null");
			this.httpHeaderValidator = httpHeaderValidator;
			return this;
		}

		/**
		 * Sets the maximum size, in bytes, of a single request body accepted by this
		 * transport. Requests whose body exceeds this size are rejected with a 413
		 * (Payload Too Large) response. Defaults to 16 MiB if not set.
		 * @param requestMaxSize The maximum request body size, in bytes. Must be
		 * positive.
		 * @return this builder instance
		 */
		public Builder maxRequestSize(int requestMaxSize) {
			this.requestMaxSize = requestMaxSize;
			return this;
		}

		/**
		 * Sets the interval at which idle sessions are evicted. A session is idle once it
		 * holds no open stream and has received no request for a full interval, which is
		 * how a session whose client went away without deleting it looks. Nothing else
		 * reclaims those sessions, as the protocol lets a client reconnect to a session
		 * it has been disconnected from.
		 * @param sessionSweepInterval The interval between two sweeps. If null, no
		 * sweeping will be scheduled and sessions are kept until they are deleted or the
		 * server shuts down. Defaults to 30 minutes.
		 * @return this builder instance
		 */
		public Builder sessionSweepInterval(Duration sessionSweepInterval) {
			this.sessionSweepInterval = sessionSweepInterval;
			return this;
		}

		/**
		 * Builds a new instance of {@link HttpServletStreamableServerTransportProvider}
		 * with the configured settings.
		 * @return A new HttpServletStreamableServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public HttpServletStreamableServerTransportProvider build() {
			Assert.notNull(this.mcpEndpoint, "MCP endpoint must be set");
			return new HttpServletStreamableServerTransportProvider(
					jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, mcpEndpoint, disallowDelete,
					contextExtractor, keepAliveInterval, httpHeaderValidator, requestMaxSize, sessionSweepInterval);
		}

	}

}
