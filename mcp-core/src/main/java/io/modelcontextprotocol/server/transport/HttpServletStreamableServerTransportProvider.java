/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
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
import reactor.core.scheduler.Scheduler;
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

	private static final Duration MIN_SESSION_TIMEOUT = Duration.ofMillis(1);

	private static final Duration MAX_SESSION_CLEANUP_INTERVAL = Duration.ofSeconds(30);

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
	private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();

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

	private final Duration sessionTimeout;

	private final Scheduler sessionCleanupScheduler;

	private Disposable sessionCleanupSubscription;

	/**
	 * Security validator for validating HTTP requests.
	 */
	private final ServerTransportSecurityValidator securityValidator;

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
	 * @param sessionTimeout The idle timeout for sessions. If null, idle sessions are not
	 * automatically closed.
	 * @param sessionCleanupScheduler The scheduler used to clean up idle sessions.
	 * @param securityValidator The security validator for validating HTTP requests.
	 * @param requestMaxSize The maximum size, in bytes, of a single request body. Must be
	 * positive.
	 * @throws IllegalArgumentException if a required parameter is null or the session
	 * timeout or request size is invalid
	 */
	private HttpServletStreamableServerTransportProvider(McpJsonMapper jsonMapper, String mcpEndpoint,
			boolean disallowDelete, McpTransportContextExtractor<HttpServletRequest> contextExtractor,
			Duration keepAliveInterval, Duration sessionTimeout, Scheduler sessionCleanupScheduler,
			ServerTransportSecurityValidator securityValidator, int requestMaxSize) {
		Assert.notNull(jsonMapper, "JsonMapper must not be null");
		Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
		Assert.notNull(contextExtractor, "Context extractor must not be null");
		Assert.notNull(securityValidator, "Security validator must not be null");
		Assert.isTrue(requestMaxSize > 0, "requestMaxSize must be positive");

		this.jsonMapper = jsonMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.disallowDelete = disallowDelete;
		this.contextExtractor = contextExtractor;
		this.securityValidator = securityValidator;
		this.requestMaxSize = requestMaxSize;
		this.sessionTimeout = sessionTimeout;
		if (this.sessionTimeout != null) {
			validateSessionTimeout(this.sessionTimeout);
		}
		this.sessionCleanupScheduler = sessionTimeout == null ? null : (sessionCleanupScheduler != null
				? sessionCleanupScheduler : Schedulers.newSingle("mcp-session-cleanup"));

		if (keepAliveInterval != null) {

			this.keepAliveScheduler = KeepAliveScheduler
				.builder(() -> (isClosing) ? Flux.empty()
						: Flux.fromIterable(sessions.values()).map(SessionEntry::session))
				.initialDelay(keepAliveInterval)
				.interval(keepAliveInterval)
				.build();

			this.keepAliveScheduler.start();
		}

		if (this.sessionTimeout != null) {
			Duration cleanupInterval = this.sessionTimeout.compareTo(MAX_SESSION_CLEANUP_INTERVAL) < 0
					? this.sessionTimeout : MAX_SESSION_CLEANUP_INTERVAL;
			long cleanupIntervalMillis = cleanupInterval.toMillis();
			this.sessionCleanupSubscription = this.sessionCleanupScheduler.schedulePeriodically(
					this::cleanupExpiredSessions, cleanupIntervalMillis, cleanupIntervalMillis, TimeUnit.MILLISECONDS);
		}

	}

	private static void validateSessionTimeout(Duration sessionTimeout) {
		Assert.isTrue(!sessionTimeout.isNegative() && !sessionTimeout.isZero(),
				"Session timeout must be greater than zero");
		Assert.isTrue(sessionTimeout.compareTo(MIN_SESSION_TIMEOUT) >= 0,
				"Session timeout must be at least 1 millisecond");
	}

	private SessionActivityLease acquireSessionActivity(String sessionId, SessionEntry sessionEntry) {
		SessionActivityLease lease = sessionEntry.acquire();
		if (lease == null) {
			return null;
		}

		if (this.sessions.get(sessionId) != sessionEntry) {
			lease.close();
			return null;
		}

		return lease;
	}

	private void cleanupExpiredSessions() {
		if (this.isClosing) {
			return;
		}

		this.sessions.forEach((sessionId, sessionEntry) -> {
			if (!sessionEntry.tryExpire(this.sessionTimeout)) {
				return;
			}

			if (!this.sessions.remove(sessionId, sessionEntry)) {
				return;
			}

			logger.info("Session {} exceeded idle timeout of {} and will be closed", sessionId, this.sessionTimeout);
			sessionEntry.session()
				.closeGracefully()
				.doOnError(error -> logger.warn("Failed to close idle session {}: {}", sessionId, error.getMessage()))
				.onErrorComplete()
				.subscribe();
		});
	}

	private static final class SessionEntry {

		private final McpStreamableServerSession session;

		private final Scheduler scheduler;

		private long lastActivityMillis;

		private int references;

		private boolean closed;

		SessionEntry(McpStreamableServerSession session, Scheduler scheduler) {
			this.session = session;
			this.scheduler = scheduler;
			if (scheduler != null) {
				this.lastActivityMillis = scheduler.now(TimeUnit.MILLISECONDS);
			}
		}

		McpStreamableServerSession session() {
			return this.session;
		}

		synchronized SessionActivityLease acquire() {
			if (this.closed) {
				return null;
			}
			if (this.scheduler == null) {
				return SessionActivityLease.noop();
			}
			this.references++;
			return new SessionActivityLease(this);
		}

		synchronized boolean tryExpire(Duration timeout) {
			if (this.scheduler == null || this.closed || this.references > 0) {
				return false;
			}

			long idleMillis = Math.max(0, this.scheduler.now(TimeUnit.MILLISECONDS) - this.lastActivityMillis);
			if (Duration.ofMillis(idleMillis).compareTo(timeout) < 0) {
				return false;
			}

			this.closed = true;
			return true;
		}

		synchronized void release() {
			Assert.isTrue(this.references > 0, "Session activity reference count must be greater than zero");
			this.references--;
			if (this.references == 0) {
				this.lastActivityMillis = this.scheduler.now(TimeUnit.MILLISECONDS);
			}
		}

		synchronized void close() {
			this.closed = true;
		}

	}

	private static final class SessionActivityLease implements AutoCloseable, AsyncListener {

		private static final SessionActivityLease NOOP = new SessionActivityLease(null);

		private final SessionEntry sessionEntry;

		private final AtomicBoolean closed = new AtomicBoolean();

		SessionActivityLease(SessionEntry sessionEntry) {
			this.sessionEntry = sessionEntry;
		}

		static SessionActivityLease noop() {
			return NOOP;
		}

		@Override
		public void close() {
			if (this.sessionEntry != null && this.closed.compareAndSet(false, true)) {
				this.sessionEntry.release();
			}
		}

		@Override
		public void onComplete(AsyncEvent event) {
			close();
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			close();
		}

		@Override
		public void onError(AsyncEvent event) {
			close();
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
		}

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
			this.sessions.values().parallelStream().forEach(sessionEntry -> {
				McpStreamableServerSession session = sessionEntry.session();
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
			SessionEntry sessionEntry = this.sessions.get(sessionId);
			if (sessionEntry == null) {
				logger.debug("Session {} not found", sessionId);
				return Mono.empty();
			}
			return sessionEntry.session().sendNotification(method, params);
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

			this.sessions.values().forEach(SessionEntry::close);
			this.sessions.values().parallelStream().forEach(sessionEntry -> {
				McpStreamableServerSession session = sessionEntry.session();
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
			if (this.sessionCleanupSubscription != null) {
				this.sessionCleanupSubscription.dispose();
			}
			if (this.sessionCleanupScheduler != null) {
				this.sessionCleanupScheduler.dispose();
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
			Map<String, List<String>> headers = HttpServletRequestUtils.extractHeaders(request);
			this.securityValidator.validateHeaders(headers);
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

		SessionEntry sessionEntry = this.sessions.get(sessionId);

		if (sessionEntry == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		SessionActivityLease activityLease = acquireSessionActivity(sessionId, sessionEntry);
		if (activityLease == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		logger.debug("Handling GET request for session: {}", sessionId);

		McpStreamableServerSession session = sessionEntry.session();

		try {
			response.setContentType(TEXT_EVENT_STREAM);
			response.setCharacterEncoding(UTF_8);
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader("Connection", "keep-alive");

			AsyncContext asyncContext = request.startAsync();
			asyncContext.setTimeout(0);

			HttpServletStreamableMcpSessionTransport sessionTransport = new HttpServletStreamableMcpSessionTransport(
					sessionId, sessionEntry, asyncContext, response.getWriter(), activityLease);
			registerSessionActivityLifecycle(asyncContext, activityLease);

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
								sessionTransport.close();
							}
						});
				}
				catch (Exception e) {
					logger.error("Failed to replay messages: {}", e.getMessage());
					sessionTransport.close();
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
			activityLease.close();
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
			Map<String, List<String>> headers = HttpServletRequestUtils.extractHeaders(request);
			this.securityValidator.validateHeaders(headers);
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
				SessionEntry sessionEntry = new SessionEntry(init.session(), this.sessionCleanupScheduler);
				SessionActivityLease initializationActivity = sessionEntry.acquire();
				this.sessions.put(init.session().getId(), sessionEntry);

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
				finally {
					initializationActivity.close();
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

			SessionEntry sessionEntry = this.sessions.get(sessionId);

			if (sessionEntry == null) {
				this.responseError(response, HttpServletResponse.SC_NOT_FOUND,
						McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message("Session not found: " + sessionId)
							.build());
				return;
			}

			SessionActivityLease activityLease = acquireSessionActivity(sessionId, sessionEntry);
			if (activityLease == null) {
				this.responseError(response, HttpServletResponse.SC_NOT_FOUND,
						McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message("Session not found: " + sessionId)
							.build());
				return;
			}

			McpStreamableServerSession session = sessionEntry.session();

			try {
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
							sessionId, sessionEntry, asyncContext, response.getWriter(), activityLease);
					registerSessionActivityLifecycle(asyncContext, activityLease);

					try {
						session.responseStream(jsonrpcRequest, sessionTransport)
							.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
							.block();
					}
					catch (Exception e) {
						logger.error("Failed to handle request stream: {}", e.getMessage());
						sessionTransport.close();
					}
				}
				else {
					this.responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
							McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
								.message("Unknown message type")
								.build());
				}
			}
			finally {
				activityLease.close();
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

	private void registerSessionActivityLifecycle(AsyncContext asyncContext, SessionActivityLease activityLease) {
		if (this.sessionTimeout != null) {
			asyncContext.addListener(activityLease);
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
			Map<String, List<String>> headers = HttpServletRequestUtils.extractHeaders(request);
			this.securityValidator.validateHeaders(headers);
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
		SessionEntry sessionEntry = this.sessions.get(sessionId);

		if (sessionEntry == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		SessionActivityLease activityLease = acquireSessionActivity(sessionId, sessionEntry);
		if (activityLease == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		McpStreamableServerSession session = sessionEntry.session();

		try {
			session.delete().contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)).block();
			if (this.sessions.remove(sessionId, sessionEntry)) {
				sessionEntry.close();
			}
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
		finally {
			activityLease.close();
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

		private final SessionEntry sessionEntry;

		private final AsyncContext asyncContext;

		private final PrintWriter writer;

		private final SessionActivityLease activityLease;

		private volatile boolean closed = false;

		private final ReentrantLock lock = new ReentrantLock();

		/**
		 * Creates a new session transport with the specified ID and SSE writer.
		 * @param sessionId The unique identifier for this session
		 * @param sessionEntry The session state associated with this transport
		 * @param asyncContext The async context for the session
		 * @param writer The writer for sending server events to the client
		 * @param activityLease The activity lease held while this transport is open
		 */
		HttpServletStreamableMcpSessionTransport(String sessionId, SessionEntry sessionEntry, AsyncContext asyncContext,
				PrintWriter writer, SessionActivityLease activityLease) {
			this.sessionId = sessionId;
			this.sessionEntry = sessionEntry;
			this.asyncContext = asyncContext;
			this.writer = writer;
			this.activityLease = activityLease;
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
					logger.error("Failed to send message to session {}: {}", this.sessionId, e.getMessage());
					if (HttpServletStreamableServerTransportProvider.this.sessions.remove(this.sessionId,
							this.sessionEntry)) {
						this.sessionEntry.close();
					}
					this.activityLease.close();
					this.asyncContext.complete();
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

				// HttpServletStreamableServerTransportProvider.this.sessions.remove(this.sessionId);
				this.asyncContext.complete();
				logger.debug("Successfully completed async context for session {}", sessionId);
			}
			catch (Exception e) {
				logger.warn("Failed to complete async context for session {}: {}", sessionId, e.getMessage());
			}
			finally {
				this.activityLease.close();
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

		private McpJsonMapper jsonMapper;

		private String mcpEndpoint = "/mcp";

		private boolean disallowDelete = false;

		private McpTransportContextExtractor<HttpServletRequest> contextExtractor = (
				serverRequest) -> McpTransportContext.EMPTY;

		private Duration keepAliveInterval;

		private Duration sessionTimeout;

		private Scheduler sessionCleanupScheduler;

		private ServerTransportSecurityValidator securityValidator = ServerTransportSecurityValidator.NOOP;

		private int requestMaxSize = DEFAULT_REQUEST_MAX_SIZE;

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
		 * Sets the idle timeout after which a session becomes eligible for periodic
		 * cleanup. Idle session cleanup is disabled by default.
		 * @param sessionTimeout The session idle timeout. Must be greater than zero. If
		 * null, idle session cleanup is disabled.
		 * @return this builder instance
		 * @throws IllegalArgumentException if the timeout is non-null and less than one
		 * millisecond
		 */
		public Builder sessionTimeout(Duration sessionTimeout) {
			if (sessionTimeout != null) {
				validateSessionTimeout(sessionTimeout);
			}
			this.sessionTimeout = sessionTimeout;
			return this;
		}

		Builder sessionCleanupScheduler(Scheduler sessionCleanupScheduler) {
			Assert.notNull(sessionCleanupScheduler, "Session cleanup scheduler must not be null");
			this.sessionCleanupScheduler = sessionCleanupScheduler;
			return this;
		}

		/**
		 * Sets the security validator for validating HTTP requests.
		 * @param securityValidator The security validator to use. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if securityValidator is null
		 */
		public Builder securityValidator(ServerTransportSecurityValidator securityValidator) {
			Assert.notNull(securityValidator, "Security validator must not be null");
			this.securityValidator = securityValidator;
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
		 * Builds a new instance of {@link HttpServletStreamableServerTransportProvider}
		 * with the configured settings.
		 * @return A new HttpServletStreamableServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public HttpServletStreamableServerTransportProvider build() {
			Assert.notNull(this.mcpEndpoint, "MCP endpoint must be set");
			return new HttpServletStreamableServerTransportProvider(
					jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, mcpEndpoint, disallowDelete,
					contextExtractor, keepAliveInterval, sessionTimeout, sessionCleanupScheduler, securityValidator,
					requestMaxSize);
		}

	}

}
