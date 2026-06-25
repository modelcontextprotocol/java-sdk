/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import io.modelcontextprotocol.json.TypeRef;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * Representation of a Streamable HTTP server session that keeps track of mapping
 * server-initiated requests to the client and mapping arriving responses. It also allows
 * handling incoming notifications. For requests, it provides the default SSE streaming
 * capability without the insight into the transport-specific details of HTTP handling.
 *
 * @author Dariusz Jędrzejczyk
 * @author Yanming Zhou
 */
public class McpStreamableServerSession implements McpLoggableSession {

	private static final Logger logger = LoggerFactory.getLogger(McpStreamableServerSession.class);

	private final ConcurrentHashMap<Object, McpStreamableServerSessionStream> requestIdToStream = new ConcurrentHashMap<>();

	private final String id;

	private final Duration requestTimeout;

	private final AtomicLong requestCounter = new AtomicLong(0);

	private final Map<String, McpRequestHandler<?>> requestHandlers;

	private final Map<String, McpNotificationHandler> notificationHandlers;

	private final AtomicReference<McpSchema.ClientCapabilities> clientCapabilities = new AtomicReference<>();

	private final AtomicReference<McpSchema.Implementation> clientInfo = new AtomicReference<>();

	private final AtomicReference<McpLoggableSession> listeningStreamRef;

	private final MissingMcpTransportSession missingMcpTransportSession;

	/**
	 * Veoci resumability: maximum number of recent events retained per stream for
	 * {@code Last-Event-Id} replay. Bounds memory for long-lived listening streams.
	 */
	private static final int MAX_EVENTS_PER_STREAM = 1024;

	/**
	 * Veoci resumability: maximum number of recent listening streams whose history is
	 * retained for replay after they disconnect. Eldest are evicted.
	 */
	private static final int MAX_TRACKED_STREAMS = 8;

	/**
	 * Veoci resumability: registry of recent listening streams keyed by their transport
	 * id (the first component of every event id). A client reconnecting with a
	 * {@code Last-Event-Id} is replayed from the matching stream. Bounded so memory stays
	 * flat over a long session. Only listening (GET) streams are registered; short-lived
	 * per-request POST streams are not replayable.
	 */
	private final Map<String, McpStreamableServerSessionStream> trackedStreams = Collections
		.synchronizedMap(new LinkedHashMap<>());

	/**
	 * Veoci customization: Spring Security authentication captured at session creation
	 * time (the initialize request thread, where Spring Security filters have populated
	 * the {@code SecurityContextHolder}). Mirrors the SSE {@link McpServerSession}
	 * behaviour so tool handlers can recover the caller identity.
	 */
	private final Authentication authentication;

	/**
	 * Veoci customization (multi-instance): marks a session created on an instance that
	 * does not own the original session, to serve a request that was routed there without
	 * sticky load-balancing. Mirrors {@link McpServerSession#isProxySession()}. A proxy
	 * session handles the request locally and replies on the caller's own connection (for
	 * Streamable HTTP the response is not a separate stream, so — unlike SSE — nothing
	 * has to be forwarded back to the owner for a tool call).
	 */
	private boolean proxySession = false;

	private volatile McpSchema.LoggingLevel minLoggingLevel = McpSchema.LoggingLevel.INFO;

	/**
	 * Create an instance of the streamable session.
	 * @param id session ID
	 * @param clientCapabilities client capabilities
	 * @param clientInfo client info
	 * @param requestTimeout timeout to use for requests
	 * @param requestHandlers the map of MCP request handlers keyed by method name
	 * @param notificationHandlers the map of MCP notification handlers keyed by method
	 * name
	 */
	public McpStreamableServerSession(String id, McpSchema.ClientCapabilities clientCapabilities,
			McpSchema.Implementation clientInfo, Duration requestTimeout,
			Map<String, McpRequestHandler<?>> requestHandlers,
			Map<String, McpNotificationHandler> notificationHandlers) {
		this.id = id;
		this.missingMcpTransportSession = new MissingMcpTransportSession(id);
		this.listeningStreamRef = new AtomicReference<>(this.missingMcpTransportSession);
		this.clientCapabilities.lazySet(clientCapabilities);
		this.clientInfo.lazySet(clientInfo);
		this.requestTimeout = requestTimeout;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
		this.authentication = SecurityContextHolder.getContext().getAuthentication();
	}

	/**
	 * Veoci customization: retrieve the Spring Security {@link Authentication} captured
	 * when this Streamable HTTP session was created.
	 * @return the captured authentication, or {@code null} if none was present
	 */
	@Override
	public Authentication getAuthentication() {
		return this.authentication;
	}

	/**
	 * Veoci customization: whether this is a proxy session (see {@link #proxySession}).
	 * @return {@code true} if this session was created to serve a request routed to a
	 * non-owning instance
	 */
	public boolean isProxySession() {
		return this.proxySession;
	}

	/**
	 * Veoci customization: mark this session as a proxy session.
	 * @param proxySession {@code true} to mark as a proxy session
	 */
	public void setProxySession(boolean proxySession) {
		this.proxySession = proxySession;
	}

	@Override
	public void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel) {
		Assert.notNull(minLoggingLevel, "minLoggingLevel must not be null");
		this.minLoggingLevel = minLoggingLevel;
	}

	@Override
	public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel loggingLevel) {
		return loggingLevel.level() >= this.minLoggingLevel.level();
	}

	/**
	 * Return the Session ID.
	 * @return session ID
	 */
	public String getId() {
		return this.id;
	}

	private String generateRequestId() {
		return this.id + "-" + this.requestCounter.getAndIncrement();
	}

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
		return Mono.defer(() -> {
			McpLoggableSession listeningStream = this.listeningStreamRef.get();
			return listeningStream.sendRequest(method, requestParams, typeRef);
		});
	}

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		return Mono.defer(() -> {
			McpLoggableSession listeningStream = this.listeningStreamRef.get();
			return listeningStream.sendNotification(method, params);
		});
	}

	public Mono<Void> delete() {
		return this.closeGracefully().then(Mono.fromRunnable(() -> {
			// TODO: review in the context of history storage
			// delete history, etc.
		}));
	}

	/**
	 * Create a listening stream (the generic HTTP GET request without Last-Event-ID
	 * header).
	 * @param transport The dedicated SSE transport stream
	 * @return a stream representation
	 */
	public McpStreamableServerSessionStream listeningStream(McpStreamableServerTransport transport) {
		McpStreamableServerSessionStream listeningStream = new McpStreamableServerSessionStream(transport);
		this.listeningStreamRef.set(listeningStream);
		// Veoci resumability: track the listening stream (bounded) so a later reconnect
		// carrying a Last-Event-Id can be replayed from its retained history.
		synchronized (this.trackedStreams) {
			this.trackedStreams.put(listeningStream.getTransportId(), listeningStream);
			while (this.trackedStreams.size() > MAX_TRACKED_STREAMS) {
				var eldest = this.trackedStreams.keySet().iterator();
				if (!eldest.hasNext()) {
					break;
				}
				eldest.next();
				eldest.remove();
			}
		}
		return listeningStream;
	}

	/**
	 * Veoci resumability: replay the events a client missed on a stream that dropped,
	 * identified by the {@code Last-Event-Id} it last received. Returns the bare messages
	 * (event ids are dropped); prefer {@link #replayEvents(Object)} when the original
	 * event ids must be preserved on the wire (the Veoci WebMVC transport does this).
	 * @param lastEventId the last event id the client received, of the form
	 * {@code <transportId>_<sequence>}
	 * @return the missed messages in order, or empty if nothing can be replayed
	 */
	public Flux<McpSchema.JSONRPCMessage> replay(Object lastEventId) {
		List<EventMessage> events = replayEvents(lastEventId);
		List<McpSchema.JSONRPCMessage> messages = new ArrayList<>(events.size());
		for (EventMessage event : events) {
			messages.add(event.message());
		}
		return Flux.fromIterable(messages);
	}

	/**
	 * Veoci resumability: like {@link #replay(Object)} but preserves each event's
	 * original id so the transport can re-send it under the same SSE {@code id:} the
	 * client already tracked. Replay is scoped to the stream encoded in
	 * {@code lastEventId}; only listening streams are retained (see
	 * {@link #trackedStreams}).
	 * @param lastEventId the last event id the client received, of the form
	 * {@code <transportId>_<sequence>}
	 * @return the missed events (id + message) in order, or empty if nothing can be
	 * replayed (unknown/expired stream, unparseable id, or events already evicted)
	 */
	public List<EventMessage> replayEvents(Object lastEventId) {
		if (lastEventId == null) {
			return List.of();
		}
		String eventId = lastEventId.toString();
		int separator = eventId.lastIndexOf('_');
		if (separator < 0) {
			logger.warn("Unparseable Last-Event-Id '{}' for session {}; skipping replay", eventId, this.id);
			return List.of();
		}
		String transportId = eventId.substring(0, separator);
		long lastSequence;
		try {
			lastSequence = Long.parseLong(eventId.substring(separator + 1));
		}
		catch (NumberFormatException e) {
			logger.warn("Unparseable Last-Event-Id '{}' for session {}; skipping replay", eventId, this.id);
			return List.of();
		}
		McpStreamableServerSessionStream stream = this.trackedStreams.get(transportId);
		if (stream == null) {
			logger.debug("No retained stream {} to replay for session {} (expired or unknown)", transportId, this.id);
			return List.of();
		}
		return stream.eventsAfter(lastSequence);
	}

	/**
	 * Provide the SSE stream of MCP messages finalized with a Response.
	 * @param jsonrpcRequest the MCP request triggering the stream creation
	 * @param transport the SSE transport stream to send messages to
	 * @return Mono which completes once the processing is done
	 */
	public Mono<Void> responseStream(McpSchema.JSONRPCRequest jsonrpcRequest, McpStreamableServerTransport transport) {
		return Mono.deferContextual(ctx -> {
			McpTransportContext transportContext = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);

			McpStreamableServerSessionStream stream = new McpStreamableServerSessionStream(transport);
			McpRequestHandler<?> requestHandler = McpStreamableServerSession.this.requestHandlers
				.get(jsonrpcRequest.method());
			// TODO: delegate to stream, which upon successful response should close
			// remove itself from the registry and also close the underlying transport
			// (sink)
			if (requestHandler == null) {
				MethodNotFoundError error = getMethodNotFoundError(jsonrpcRequest.method());
				return transport
					.sendMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, jsonrpcRequest.id(), null,
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
									error.message(), error.data())));
			}
			return requestHandler
				.handle(new McpAsyncServerExchange(this.id, stream, clientCapabilities.get(), clientInfo.get(),
						transportContext), jsonrpcRequest.params())
				.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, jsonrpcRequest.id(), result,
						null))
				.onErrorResume(e -> {
					McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = (e instanceof McpError mcpError
							&& mcpError.getJsonRpcError() != null) ? mcpError.getJsonRpcError()
									: new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
											e.getMessage(), McpError.aggregateExceptionMessages(e));

					var errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, jsonrpcRequest.id(),
							null, jsonRpcError);
					return Mono.just(errorResponse);
				})
				.flatMap(transport::sendMessage)
				.then(transport.closeGracefully());
		});
	}

	/**
	 * Handle the MCP notification.
	 * @param notification MCP notification
	 * @return Mono which completes upon succesful handling
	 */
	public Mono<Void> accept(McpSchema.JSONRPCNotification notification) {
		return Mono.deferContextual(ctx -> {
			McpTransportContext transportContext = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
			McpNotificationHandler notificationHandler = this.notificationHandlers.get(notification.method());
			if (notificationHandler == null) {
				logger.warn("No handler registered for notification method: {}", notification);
				return Mono.empty();
			}
			McpLoggableSession listeningStream = this.listeningStreamRef.get();
			return notificationHandler.handle(new McpAsyncServerExchange(this.id, listeningStream,
					this.clientCapabilities.get(), this.clientInfo.get(), transportContext), notification.params());
		});

	}

	/**
	 * Handle the MCP response.
	 * @param response MCP response to the server-initiated request
	 * @return Mono which completes upon successful processing
	 */
	public Mono<Void> accept(McpSchema.JSONRPCResponse response) {
		return Mono.defer(() -> {
			logger.debug("Received response: {}", response);

			if (response.id() != null) {
				var stream = this.requestIdToStream.get(response.id());
				if (stream == null) {
					return Mono.error(McpError.builder(ErrorCodes.INTERNAL_ERROR)
						.message("Unexpected response for unknown id " + response.id())
						.build());
				}
				// TODO: encapsulate this inside the stream itself
				var sink = stream.pendingResponses.remove(response.id());
				if (sink == null) {
					return Mono.error(McpError.builder(ErrorCodes.INTERNAL_ERROR)
						.message("Unexpected response for unknown id " + response.id())
						.build());
				}
				else {
					sink.success(response);
				}
			}
			else {
				logger.error("Discarded MCP request response without session id. "
						+ "This is an indication of a bug in the request sender code that can lead to memory "
						+ "leaks as pending requests will never be completed.");
			}
			return Mono.empty();
		});
	}

	record MethodNotFoundError(String method, String message, Object data) {
	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		return new MethodNotFoundError(method, "Method not found: " + method, null);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			McpLoggableSession listeningStream = this.listeningStreamRef.getAndSet(missingMcpTransportSession);
			this.trackedStreams.clear();
			return listeningStream.closeGracefully();
			// TODO: Also close all the open streams
		});
	}

	@Override
	public void close() {
		McpLoggableSession listeningStream = this.listeningStreamRef.getAndSet(missingMcpTransportSession);
		this.trackedStreams.clear();
		if (listeningStream != null) {
			listeningStream.close();
		}
		// TODO: Also close all open streams
	}

	/**
	 * Request handler for the initialization request.
	 */
	public interface InitRequestHandler {

		/**
		 * Handles the initialization request.
		 * @param initializeRequest the initialization request by the client
		 * @return a Mono that will emit the result of the initialization
		 */
		Mono<McpSchema.InitializeResult> handle(McpSchema.InitializeRequest initializeRequest);

	}

	/**
	 * Factory for new Streamable HTTP MCP sessions.
	 */
	public interface Factory {

		/**
		 * Given an initialize request, create a composite for the session initialization
		 * @param initializeRequest the initialization request from the client
		 * @return a composite allowing the session to start
		 */
		McpStreamableServerSessionInit startSession(McpSchema.InitializeRequest initializeRequest);

		/**
		 * Veoci customization (multi-instance): create a proxy session bound to an
		 * existing session id, without re-running the {@code initialize} handshake. Used
		 * by an instance that receives a request for a session it does not hold (no
		 * sticky load-balancing) so it can handle the request locally and reply on the
		 * caller's own connection. The session carries default (empty) client
		 * capabilities; the Spring {@code Authentication} is captured from the calling
		 * thread, exactly like the SSE proxy session.
		 * @param sessionId the existing session id supplied by the client
		 * @return a proxy session ready to handle requests/notifications locally
		 */
		McpStreamableServerSession createProxySession(String sessionId);

	}

	/**
	 * Composite holding the {@link McpStreamableServerSession} and the initialization
	 * result
	 *
	 * @param session the session instance
	 * @param initResult the result to use to respond to the client
	 */
	public record McpStreamableServerSessionInit(McpStreamableServerSession session,
			Mono<McpSchema.InitializeResult> initResult) {
	}

	/**
	 * Veoci resumability: an outbound event retained for replay — its SSE event id and
	 * the JSON-RPC message that was sent to the client.
	 *
	 * @param eventId the SSE event id ({@code <transportId>_<sequence>})
	 * @param message the JSON-RPC message that was sent to the client
	 */
	public record EventMessage(String eventId, McpSchema.JSONRPCMessage message) {
	}

	/**
	 * An individual SSE stream within a Streamable HTTP context. Can be either the
	 * listening GET SSE stream or a request-specific POST SSE stream.
	 */
	public final class McpStreamableServerSessionStream implements McpLoggableSession {

		private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

		private final McpStreamableServerTransport transport;

		private final String transportId;

		/**
		 * Veoci resumability: monotonic per-stream event sequence. Combined with
		 * {@link #transportId} it yields ordered, replayable event ids of the form
		 * {@code <transportId>_<sequence>}.
		 */
		private final AtomicLong eventSequence = new AtomicLong(0);

		/**
		 * Veoci resumability: bounded, ordered history of events sent on this stream,
		 * keyed by sequence number. Capped at {@link #MAX_EVENTS_PER_STREAM}.
		 */
		private final NavigableMap<Long, McpSchema.JSONRPCMessage> eventLog = new ConcurrentSkipListMap<>();

		/**
		 * Constructor accepting the dedicated transport representing the SSE stream.
		 * @param transport request-specific SSE transport stream
		 */
		public McpStreamableServerSessionStream(McpStreamableServerTransport transport) {
			this.transport = transport;
			// The first component of every event id identifies this stream, allowing
			// constant-time lookup of its history during replay.
			this.transportId = UUID.randomUUID().toString();
		}

		String getTransportId() {
			return this.transportId;
		}

		/**
		 * Veoci resumability: record an outbound message in this stream's bounded history
		 * and return the SSE event id to send it under. Oldest events are evicted beyond
		 * {@link #MAX_EVENTS_PER_STREAM}.
		 */
		private String recordEvent(McpSchema.JSONRPCMessage message) {
			long sequence = this.eventSequence.incrementAndGet();
			this.eventLog.put(sequence, message);
			while (this.eventLog.size() > MAX_EVENTS_PER_STREAM) {
				var oldest = this.eventLog.firstEntry();
				if (oldest == null) {
					break;
				}
				this.eventLog.remove(oldest.getKey());
			}
			return this.transportId + "_" + sequence;
		}

		/**
		 * Veoci resumability: return the events recorded after the given sequence number,
		 * in order, each paired with its original event id.
		 */
		private List<EventMessage> eventsAfter(long lastSequence) {
			List<EventMessage> events = new ArrayList<>();
			this.eventLog.tailMap(lastSequence, false)
				.forEach((sequence, message) -> events
					.add(new EventMessage(this.transportId + "_" + sequence, message)));
			return events;
		}

		@Override
		public void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel) {
			Assert.notNull(minLoggingLevel, "minLoggingLevel must not be null");
			McpStreamableServerSession.this.setMinLoggingLevel(minLoggingLevel);
		}

		@Override
		public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel loggingLevel) {
			return McpStreamableServerSession.this.isNotificationForLevelAllowed(loggingLevel);
		}

		/**
		 * Veoci customization: per-request and listening SSE streams expose the same
		 * captured authentication as their owning session. A request-specific stream
		 * backs the exchange handed to tool handlers (see
		 * {@link McpStreamableServerSession#responseStream}), so the auth must be
		 * reachable through it.
		 */
		@Override
		public Authentication getAuthentication() {
			return McpStreamableServerSession.this.authentication;
		}

		@Override
		public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
			String requestId = McpStreamableServerSession.this.generateRequestId();

			McpStreamableServerSession.this.requestIdToStream.put(requestId, this);

			return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
				this.pendingResponses.put(requestId, sink);
				McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
						method, requestId, requestParams);
				// Veoci resumability: record before sending so the event can be replayed.
				String messageId = recordEvent(jsonrpcRequest);
				this.transport.sendMessage(jsonrpcRequest, messageId).subscribe(v -> {
				}, sink::error);
			}).timeout(requestTimeout).doOnError(e -> {
				this.pendingResponses.remove(requestId);
				McpStreamableServerSession.this.requestIdToStream.remove(requestId);
			}).handle((jsonRpcResponse, sink) -> {
				if (jsonRpcResponse.error() != null) {
					sink.error(new McpError(jsonRpcResponse.error()));
				}
				else {
					if (typeRef.getType().equals(Void.class)) {
						sink.complete();
					}
					else {
						sink.next(this.transport.unmarshalFrom(jsonRpcResponse.result(), typeRef));
					}
				}
			});
		}

		@Override
		public Mono<Void> sendNotification(String method, Object params) {
			McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(
					McpSchema.JSONRPC_VERSION, method, params);
			// Veoci resumability: record before sending so the event can be replayed.
			String messageId = recordEvent(jsonrpcNotification);
			return this.transport.sendMessage(jsonrpcNotification, messageId);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.defer(() -> {
				this.pendingResponses.values().forEach(s -> s.error(new RuntimeException("Stream closed")));
				this.pendingResponses.clear();
				// If this was the generic stream, reset it
				McpStreamableServerSession.this.listeningStreamRef.compareAndExchange(this,
						McpStreamableServerSession.this.missingMcpTransportSession);
				McpStreamableServerSession.this.requestIdToStream.values().removeIf(this::equals);
				return this.transport.closeGracefully();
			});
		}

		@Override
		public void close() {
			this.pendingResponses.values().forEach(s -> s.error(new RuntimeException("Stream closed")));
			this.pendingResponses.clear();
			// If this was the generic stream, reset it
			McpStreamableServerSession.this.listeningStreamRef.compareAndExchange(this,
					McpStreamableServerSession.this.missingMcpTransportSession);
			McpStreamableServerSession.this.requestIdToStream.values().removeIf(this::equals);
			this.transport.close();
		}

	}

}
