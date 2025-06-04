package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpLastEventId;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSession;
import io.modelcontextprotocol.spec.McpStatelessSession;
import io.modelcontextprotocol.spec.SessionWrapper;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author Aliaksei_Darafeyeu
 */
@WebServlet(asyncSupported = true)
public class StreamableHttpServerTransportProvider extends HttpServlet implements McpServerTransportProvider {

	/**
	 * Logger for this class
	 */
	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpServerTransportProvider.class);

	private static final String MCP_SESSION_ID = "Mcp-Session-Id";

	private static final String APPLICATION_JSON = "application/json";

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private static final String LAST_EVENT_ID = "Last-Event-ID";

	private McpServerSession.Factory sessionFactory;

	private final ObjectMapper objectMapper;

	private final McpServerTransportProvider legacyTransportProvider;

	private final Set<String> allowedOrigins;

	/**
	 * Map of active client sessions, keyed by session ID
	 */
	private final Map<String, SessionWrapper> sessions = new ConcurrentHashMap<>();

	private final Duration sessionTimeout = Duration.ofMinutes(10);

	public StreamableHttpServerTransportProvider(final ObjectMapper objectMapper,
			final McpServerTransportProvider legacyTransportProvider, final Set<String> allowedOrigins) {
		this.objectMapper = objectMapper;
		this.legacyTransportProvider = legacyTransportProvider;
		this.allowedOrigins = allowedOrigins;

		// clean-up sessions
		Executors.newSingleThreadScheduledExecutor()
			.scheduleAtFixedRate(this::cleanupExpiredSessions, 5, 30, TimeUnit.SECONDS);
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	@Override
	public Mono<Void> notifyClients(final String method, final Object params) {
		if (legacyTransportProvider instanceof HttpServletSseServerTransportProvider legacy) {
			return legacy.notifyClients(method, params);
		}

		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());
		return Flux.fromIterable(sessions.values())
			.flatMap(session -> session.session()
				.sendNotification(method, params)
				.doOnError(e -> logger.error("Failed to send message to session {}: {}", session.session().getId(),
						e.getMessage()))
				.onErrorComplete())
			.then();
	}

	@Override
	public Mono<Void> closeGracefully() {
		logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());
		return Flux.fromIterable(sessions.values()).flatMap(session -> session.session().closeGracefully()).then();
	}

	@Override
	public void destroy() {
		closeGracefully().block();
		super.destroy();
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// 1. Origin header check
		String origin = req.getHeader("Origin");
		if (origin != null && !allowedOrigins.contains(origin)) {
			resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
			return;
		}

		// 2. Accept header routing
		final String accept = Optional.ofNullable(req.getHeader("Accept")).orElse("");
		final List<String> acceptTypes = Arrays.stream(accept.split(",")).map(String::trim).toList();

		// todo!!!!
		if (!acceptTypes.contains(APPLICATION_JSON) && !acceptTypes.contains(TEXT_EVENT_STREAM)) {
			if (legacyTransportProvider instanceof HttpServletSseServerTransportProvider legacy) {
				legacy.doPost(req, resp);
			}
			else {
				resp.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE, "Legacy transport not available");
			}
			return;
		}

		resp.setCharacterEncoding("UTF-8");

		final McpServerTransport transport = new StreamableHttpServerTransport(resp.getOutputStream(), objectMapper);
		final McpSession session = getOrCreateSession(req.getHeader(MCP_SESSION_ID), transport);
		if (!"stateless".equals(session.getId())) {
			resp.setHeader(MCP_SESSION_ID, session.getId());
		}

		final String lastEventId = req.getHeader(LAST_EVENT_ID);
		if (session instanceof McpLastEventId resumeAwareSession) {
			resumeAwareSession.resumeFrom(lastEventId);
		}

		final List<McpSchema.JSONRPCMessage> messages;
		try {
			messages = parseRequestBodyAsStream(req);
		}
		catch (final Exception e) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON input");
			return;
		}

		boolean hasRequest = messages.stream().anyMatch(m -> m instanceof McpSchema.JSONRPCRequest);
		if (!hasRequest) {
			resp.setStatus(HttpServletResponse.SC_ACCEPTED);
			if ("stateless".equals(session.getId())) {
				transport.closeGracefully().subscribe();
			}
			return;
		}

		if (accept.contains(TEXT_EVENT_STREAM)) {
			// TODO: stream with SSE
			resp.setContentType(TEXT_EVENT_STREAM);
			resp.setHeader("Connection", "keep-alive");
			// Enable async
			final AsyncContext asyncContext = req.startAsync();
			asyncContext.setTimeout(0);

			Flux.fromIterable(messages)
				.flatMap(session::handle)
				.doOnError(e -> sendError(resp, 500, "Streaming failed: " + e.getMessage()))
				.then(closeIfStateless(session, transport))
				.subscribe();
		}
		else if (accept.contains(APPLICATION_JSON)) {
			// TODO: Handle traditional JSON-RPC,
			resp.setContentType(APPLICATION_JSON);
			Flux.fromIterable(messages)
					.flatMap(session::handle)
					.doOnError(e -> sendError(resp, 500, "Streaming failed: " + e.getMessage()))
					.then(closeIfStateless(session, transport))
					.subscribe();

		}
		else {
			resp.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE, "Unsupported Accept header");
		}
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		if (!"text/event-stream".equalsIgnoreCase(req.getHeader("Accept"))) {
			resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		// todo: legacy support
		if (legacyTransportProvider instanceof HttpServletSseServerTransportProvider legacy) {
			legacy.doGet(req, resp);
		}

		final String sessionId = req.getHeader(MCP_SESSION_ID);
		if (sessionId == null) {
			final ServletOutputStream out = resp.getOutputStream();
			final McpServerTransport transport = new StreamableHttpServerTransport(out, new ObjectMapper());
			final McpSession newSession = getOrCreateSession(req.getHeader(MCP_SESSION_ID), transport);
		}

		final SessionWrapper wrapper = sessionId != null ? sessions.get(sessionId) : null;
		if (wrapper == null) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		if (wrapper.session() instanceof McpLastEventId resumable) {
			String lastEventId = req.getHeader(LAST_EVENT_ID);
			if (lastEventId != null && !lastEventId.isBlank()) {
				resumable.resumeFrom(lastEventId);
			}
		}

		resp.setContentType(TEXT_EVENT_STREAM);
		resp.setCharacterEncoding("UTF-8");

		AsyncContext async = req.startAsync();
		async.setTimeout(0);
	}

	protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		final String sessionId = req.getHeader(MCP_SESSION_ID);
		if (sessionId == null || !sessions.containsKey(sessionId)) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Session not found");
			return;
		}

		final McpSession session = sessions.remove(sessionId).session();
		session.closeGracefully().subscribe();
		resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
	}

	private List<McpSchema.JSONRPCMessage> parseRequestBodyAsStream(final HttpServletRequest req) throws IOException {
		try (final InputStream inputStream = req.getInputStream()) {
			final JsonNode node = objectMapper.readTree(inputStream);
			if (node.isArray()) {
				final List<McpSchema.JSONRPCMessage> messages = new ArrayList<>();
				for (final JsonNode item : node) {
					messages.add(objectMapper.treeToValue(item, McpSchema.JSONRPCMessage.class));
				}
				return messages;
			}
			else if (node.isObject()) {
				final McpSchema.JSONRPCMessage message = objectMapper.treeToValue(node, McpSchema.JSONRPCMessage.class);
				if (message instanceof McpSchema.JSONRPCBatchRequest batch) {
					return batch.items();
				}
				return List.of(message);
			}
			else {
				throw new IllegalArgumentException("Invalid JSON-RPC request: not object or array");

			}
		}
	}

	private void sendEvent(PrintWriter writer, String eventType, String data) throws IOException {
		writer.write("event: " + eventType + "\n");
		writer.write("data: " + data + "\n\n");
		writer.flush();

		if (writer.checkError()) {
			throw new IOException("Client disconnected");
		}
	}

	private McpSession getOrCreateSession(final String sessionId, final McpServerTransport transport) {
		if (sessionId != null && sessionFactory != null) {
			// Reuse or track sessions if you support that; for now, we just create new
			// ones
			return sessions.get(sessionId).session();
		}
		else if (sessionFactory != null) {
			final String newSessionId = UUID.randomUUID().toString();
			return sessions.put(newSessionId, new SessionWrapper(sessionFactory.create(transport), Instant.now()))
				.session();
		}
		else {
			return new McpStatelessSession(transport);
		}
	}

	Mono<Void> closeIfStateless(final McpSession session, final McpServerTransport transport) {
		return "stateless".equals(session.getId())
				? transport.closeGracefully()
				: Mono.empty();
	}

	private void sendError(final HttpServletResponse resp, final int code, final String msg) {
		try {
			resp.sendError(code, msg);
		}
		catch (IOException ignored) {
			logger.debug("Exception during send error");
		}
	}

	private void cleanupExpiredSessions() {
		final Instant now = Instant.now();
		final Iterator<Map.Entry<String, SessionWrapper>> it = sessions.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry<String, SessionWrapper> entry = it.next();
			if (Duration.between(entry.getValue().lastAccessed(), now).compareTo(sessionTimeout) > 0) {
				entry.getValue().session().closeGracefully().subscribe();
				it.remove();
			}
		}
	}

	public static class StreamableHttpServerTransport implements McpServerTransport {

		private final ObjectMapper objectMapper;

		private final OutputStream outputStream;

		public StreamableHttpServerTransport(final OutputStream outputStream, final ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
			this.outputStream = outputStream;
		}

		@Override
		public Mono<Void> sendMessage(final McpSchema.JSONRPCMessage message) {
			return Mono.fromRunnable(() -> {
				try {
					String json = objectMapper.writeValueAsString(message);
					outputStream.write(json.getBytes(StandardCharsets.UTF_8));
					outputStream.write('\n');
					outputStream.flush();
				}
				catch (IOException e) {
					throw new RuntimeException("Failed to send message", e);
				}
			});
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				try {
					outputStream.flush();
					outputStream.close();
				}
				catch (IOException e) {
					// ignore or log
				}
			});
		}

	}

}
