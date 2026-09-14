/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private final ConcurrentHashMap<String, McpStreamableServerSessionStream> requestIdToStream = new ConcurrentHashMap<>();

	/**
	 * Every stream with a connection currently attached, whether the listening stream or
	 * a POST response stream, so that they can all be released when the session ends.
	 */
	private final Set<McpStreamableServerSessionStream> openStreams = ConcurrentHashMap.newKeySet();

	private final String id;

	private final Duration requestTimeout;

	private final AtomicLong requestCounter = new AtomicLong(0);

	private final Map<String, McpRequestHandler<?>> requestHandlers;

	private final Map<String, McpNotificationHandler> notificationHandlers;

	private final AtomicReference<McpSchema.ClientCapabilities> clientCapabilities = new AtomicReference<>();

	private final AtomicReference<McpSchema.Implementation> clientInfo = new AtomicReference<>();

	private final AtomicReference<McpLoggableSession> listeningStreamRef;

	private final MissingMcpTransportSession missingMcpTransportSession;

	private volatile McpSchema.LoggingLevel minLoggingLevel = McpSchema.LoggingLevel.INFO;

	private final Supplier<Mono<Void>> onClose;

	private final JsonSchemaValidator jsonSchemaValidator;

	/**
	 * Create an instance of the streamable session.
	 * @param id session ID
	 * @param clientCapabilities client capabilities
	 * @param clientInfo client info
	 * @param requestTimeout timeout to use for requests
	 * @param requestHandlers the map of MCP request handlers keyed by method name
	 * @param notificationHandlers the map of MCP notification handlers keyed by method
	 * name
	 * @param onClose supplier of a reactive callback invoked when the session is closed
	 * @param jsonSchemaValidator optional validator threaded to exchanges for elicitation
	 * schema validation
	 */
	public McpStreamableServerSession(String id, McpSchema.ClientCapabilities clientCapabilities,
			McpSchema.Implementation clientInfo, Duration requestTimeout,
			Map<String, McpRequestHandler<?>> requestHandlers, Map<String, McpNotificationHandler> notificationHandlers,
			Supplier<Mono<Void>> onClose, JsonSchemaValidator jsonSchemaValidator) {
		this.id = id;
		this.missingMcpTransportSession = new MissingMcpTransportSession(id);
		this.listeningStreamRef = new AtomicReference<>(this.missingMcpTransportSession);
		this.clientCapabilities.lazySet(clientCapabilities);
		this.clientInfo.lazySet(clientInfo);
		this.requestTimeout = requestTimeout;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
		this.onClose = onClose;
		this.jsonSchemaValidator = jsonSchemaValidator;
	}

	/**
	 * Create an instance of the streamable session.
	 * @param id session ID
	 * @param clientCapabilities client capabilities
	 * @param clientInfo client info
	 * @param requestTimeout timeout to use for requests
	 * @param requestHandlers the map of MCP request handlers keyed by method name
	 * @param notificationHandlers the map of MCP notification handlers keyed by method
	 * name
	 * @param onClose supplier of a reactive callback invoked when the session is closed
	 */
	public McpStreamableServerSession(String id, McpSchema.ClientCapabilities clientCapabilities,
			McpSchema.Implementation clientInfo, Duration requestTimeout,
			Map<String, McpRequestHandler<?>> requestHandlers, Map<String, McpNotificationHandler> notificationHandlers,
			Supplier<Mono<Void>> onClose) {
		this(id, clientCapabilities, clientInfo, requestTimeout, requestHandlers, notificationHandlers, onClose, null);
	}

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
		this(id, clientCapabilities, clientInfo, requestTimeout, requestHandlers, notificationHandlers, Mono::empty);
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
		// onClose is invoked inside closeGracefully
		return this.closeGracefully().then(Mono.fromRunnable(() -> {
			// TODO: review in the context of history storage
			// delete history, etc.
		}));
	}

	/**
	 * Whether the session currently has a listening stream, that is a stream the server
	 * can send its own requests and notifications to. Sessions have none until the client
	 * issues the GET request establishing one, and clients are not required to ever issue
	 * it.
	 * @return {@code true} if the session has a listening stream
	 */
	public boolean hasListeningStream() {
		return this.listeningStreamRef.get() instanceof McpStreamableServerSessionStream;
	}

	/**
	 * Whether the session currently has at least one stream with a connection attached,
	 * whether the listening stream or a POST response stream. A client holding such a
	 * connection open is still there, however long it stays silent on it.
	 * @return {@code true} if the session has an open stream
	 */
	public boolean hasOpenStream() {
		return !this.openStreams.isEmpty();
	}

	/**
	 * Create a listening stream (the generic HTTP GET request, with or without a
	 * Last-Event-ID header). A session addresses a single listening stream at a time, so
	 * the stream being replaced, if any, is closed: no message would ever be sent to it
	 * again, and leaving it open would leak the underlying connection.
	 * @param transport The dedicated SSE transport stream
	 * @return a stream representation
	 */
	public McpStreamableServerSessionStream listeningStream(McpStreamableServerTransport transport) {
		McpStreamableServerSessionStream listeningStream = new McpStreamableServerSessionStream(transport);
		McpLoggableSession replaced = this.listeningStreamRef.getAndSet(listeningStream);
		if (replaced instanceof McpStreamableServerSessionStream replacedStream) {
			logger.debug("Releasing the connection of the listening stream replaced in session {}", this.id);
			replacedStream.releaseTransport();
		}
		return listeningStream;
	}

	/**
	 * Releases the connection of the listening stream, if one is attached, leaving the
	 * session without one until the client establishes a new stream. Used when the
	 * connection turns out to be dead, typically because a keep-alive ping went
	 * unanswered, so that the socket behind it is not held on to for nothing.
	 */
	public void releaseListeningStream() {
		if (this.listeningStreamRef.get() instanceof McpStreamableServerSessionStream stream) {
			stream.releaseTransport();
		}
	}

	// TODO: keep track of history by keeping a map from eventId to stream and then
	// iterate over the events using the lastEventId
	public Flux<McpSchema.JSONRPCMessage> replay(Object lastEventId) {
		return Flux.empty();
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
			if (requestHandler == null) {
				MethodNotFoundError error = getMethodNotFoundError(jsonrpcRequest.method());
				return transport
					.sendMessage(
							McpSchema.JSONRPCResponse
								.error(jsonrpcRequest.id(),
										new McpSchema.JSONRPCResponse.JSONRPCError(
												McpSchema.ErrorCodes.METHOD_NOT_FOUND, error.message(), error.data())))
					.then(stream.closeGracefully());
			}
			return requestHandler
				.handle(new McpAsyncServerExchange(this.id, stream, clientCapabilities.get(), clientInfo.get(),
						transportContext, this.jsonSchemaValidator), jsonrpcRequest.params())
				.map(result -> McpSchema.JSONRPCResponse.result(jsonrpcRequest.id(), result))
				.onErrorResume(e -> {
					McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = (e instanceof McpError mcpError
							&& mcpError.getJsonRpcError() != null) ? mcpError.getJsonRpcError()
									: new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
											e.getMessage(), McpError.aggregateExceptionMessages(e));

					var errorResponse = McpSchema.JSONRPCResponse.error(jsonrpcRequest.id(), jsonRpcError);
					return Mono.just(errorResponse);
				})
				.flatMap(transport::sendMessage)
				.then(stream.closeGracefully());
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
					this.clientCapabilities.get(), this.clientInfo.get(), transportContext, this.jsonSchemaValidator),
					notification.params());
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
		return this.onClose.get().onErrorComplete().then(Mono.defer(() -> {
			this.listeningStreamRef.set(this.missingMcpTransportSession);
			return Flux.fromIterable(List.copyOf(this.openStreams))
				.flatMap(McpStreamableServerSessionStream::closeGracefully)
				.then();
		}));
	}

	@Override
	public void close() {
		this.onClose.get().onErrorComplete().subscribe();
		this.listeningStreamRef.set(this.missingMcpTransportSession);
		List.copyOf(this.openStreams).forEach(McpStreamableServerSessionStream::close);
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
	 * An individual SSE stream within a Streamable HTTP context. Can be either the
	 * listening GET SSE stream or a request-specific POST SSE stream.
	 */
	public final class McpStreamableServerSessionStream implements McpLoggableSession {

		private final ConcurrentHashMap<String, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

		private final McpStreamableServerTransport connection;

		private final String transportId;

		private final Supplier<String> uuidGenerator;

		/**
		 * Constructor accepting the dedicated transport representing the SSE stream.
		 * @param connection request-specific SSE transport stream
		 */
		public McpStreamableServerSessionStream(McpStreamableServerTransport connection) {
			this.connection = connection;
			McpStreamableServerSession.this.openStreams.add(this);
			this.transportId = UUID.randomUUID().toString();
			// This ID design allows for a constant-time extraction of the history by
			// precisely identifying the SSE stream using the first component
			this.uuidGenerator = () -> this.transportId + "_" + UUID.randomUUID();
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

		@Override
		public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
			String requestId = McpStreamableServerSession.this.generateRequestId();

			McpStreamableServerSession.this.requestIdToStream.put(requestId, this);

			return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
				this.pendingResponses.put(requestId, sink);
				McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(method, requestId,
						requestParams);
				String messageId = this.uuidGenerator.get();
				// TODO: store message in history
				this.connection.sendMessage(jsonrpcRequest, messageId).subscribe(v -> {
				}, sink::error);
			}).timeout(requestTimeout).doFinally(signal -> {
				// Also on completion and cancellation: a resolved request keeps no state,
				// and a deadline imposed by the caller cancels rather than errors
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
						sink.next(this.connection.unmarshalFrom(jsonRpcResponse.result(), typeRef));
					}
				}
			});
		}

		@Override
		public Mono<Void> sendNotification(String method, Object params) {
			McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(method, params);
			String messageId = this.uuidGenerator.get();
			// TODO: store message in history
			return this.connection.sendMessage(jsonrpcNotification, messageId);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.defer(() -> {
				McpStreamableServerSession.this.openStreams.remove(this);
				this.pendingResponses.values().forEach(s -> s.error(new RuntimeException("Stream closed")));
				this.pendingResponses.clear();
				// If this was the generic stream, reset it
				McpStreamableServerSession.this.listeningStreamRef.compareAndExchange(this,
						McpStreamableServerSession.this.missingMcpTransportSession);
				McpStreamableServerSession.this.requestIdToStream.values().removeIf(this::equals);
				return this.connection.closeGracefully();
			});
		}

		@Override
		public void close() {
			McpStreamableServerSession.this.openStreams.remove(this);
			this.pendingResponses.values().forEach(s -> s.error(new RuntimeException("Stream closed")));
			this.pendingResponses.clear();
			// If this was the generic stream, reset it
			McpStreamableServerSession.this.listeningStreamRef.compareAndExchange(this,
					McpStreamableServerSession.this.missingMcpTransportSession);
			McpStreamableServerSession.this.requestIdToStream.values().removeIf(this::equals);
			this.connection.close();
		}

		/**
		 * Releases the connection carrying this stream, detaching the stream from the
		 * session, but keeps its pending server-initiated requests resolvable: the client
		 * answers those with a separate HTTP POST request, which outlives the SSE stream
		 * the request was sent on.
		 * <p>
		 * This is the counterpart of {@link #close()} for the end of a connection rather
		 * than the end of the session: an SSE stream going away, whether replaced,
		 * disconnected or timed out, does not invalidate the requests sent on it.
		 */
		public void releaseTransport() {
			McpStreamableServerSession.this.openStreams.remove(this);
			// If this was the generic stream, reset it
			McpStreamableServerSession.this.listeningStreamRef.compareAndExchange(this,
					McpStreamableServerSession.this.missingMcpTransportSession);
			this.connection.close();
		}

	}

}
