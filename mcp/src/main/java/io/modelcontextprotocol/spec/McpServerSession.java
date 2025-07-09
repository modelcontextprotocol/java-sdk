package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.SseEvent;
import io.modelcontextprotocol.spec.McpSchema.McpId;
import io.modelcontextprotocol.spec.McpError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

/**
 * Represents a Model Control Protocol (MCP) session on the server side. It manages
 * bidirectional JSON-RPC communication with the client.
 */
public class McpServerSession implements McpSession {

	private static final Logger logger = LoggerFactory.getLogger(McpServerSession.class);

	private final ConcurrentHashMap<McpId, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpServerTransport> transports = new ConcurrentHashMap<>();

	private McpServerTransport listeningTransport;

	public static final String LISTENING_TRANSPORT = "listeningTransport";

	private final String id;

	/** Duration to wait for request responses before timing out */
	private final Duration requestTimeout;

	private final AtomicLong requestCounter = new AtomicLong(0);

	private final InitRequestHandler initRequestHandler;

	private final InitNotificationHandler initNotificationHandler;

	private final Map<String, RequestHandler<?>> requestHandlers;

	private final Map<String, NotificationHandler> notificationHandlers;

	private final AtomicReference<McpSchema.ClientCapabilities> clientCapabilities = new AtomicReference<>();

	private final AtomicReference<McpSchema.Implementation> clientInfo = new AtomicReference<>();

	public static final int STATE_UNINITIALIZED = 0;

	public static final int STATE_INITIALIZING = 1;

	public static final int STATE_INITIALIZED = 2;

	private final AtomicInteger state = new AtomicInteger(STATE_UNINITIALIZED);

	private final AtomicLong eventCounter = new AtomicLong(0);

	private final Map<String, String> eventTransports = new ConcurrentHashMap<>();

	private final Map<String, Map<String, SseEvent>> transportEventHistories = new ConcurrentHashMap<>();

	private volatile McpSchema.LoggingLevel minLoggingLevel = McpSchema.LoggingLevel.INFO;

	/**
	 * Creates a new server session with the given parameters and the transport to use.
	 * @param id session id
	 * @param initHandler called when a
	 * {@link io.modelcontextprotocol.spec.McpSchema.InitializeRequest} is received by the
	 * server
	 * @param initNotificationHandler called when a
	 * {@link io.modelcontextprotocol.spec.McpSchema#METHOD_NOTIFICATION_INITIALIZED} is
	 * received.
	 * @param requestHandlers map of request handlers to use
	 * @param notificationHandlers map of notification handlers to use
	 */
	public McpServerSession(String id, Duration requestTimeout, McpServerTransport listeningTransport,
			InitRequestHandler initHandler, InitNotificationHandler initNotificationHandler,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers) {
		this.id = id;
		this.requestTimeout = requestTimeout;
		this.listeningTransport = listeningTransport;
		this.initRequestHandler = initHandler;
		this.initNotificationHandler = initNotificationHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	// Alternate constructor used by StreamableHttp servers
	public McpServerSession(String id, Duration requestTimeout, InitRequestHandler initHandler,
			InitNotificationHandler initNotificationHandler, Map<String, RequestHandler<?>> requestHandlers,
			Map<String, NotificationHandler> notificationHandlers) {
		this(id, requestTimeout, null, initHandler, initNotificationHandler, requestHandlers, notificationHandlers);
	}

	/**
	 * Updates the session's minimum logging level for all future exchanges.
	 */
	public void setMinLoggingLevel(McpSchema.LoggingLevel level) {
		if (level != null) {
			this.minLoggingLevel = level;
			logger.debug("Updated session {} minimum logging level to {}", id, level);
		}
	}

	/**
	 * Retrieve the session initialization state
	 * @return session initialization state
	 */
	public int getState() {
		return state.intValue();
	}

	/**
	 * Retrieve the session id.
	 * @return session id
	 */
	public String getId() {
		return this.id;
	}

	/**
	 * Increments the session-specific event counter, maps it to the given transport ID
	 * for replayability support, then returns the event ID
	 * @param transportId
	 * @return an event ID unique to the session
	 */
	public String incrementAndGetEventId(String transportId) {
		final String eventId = String.valueOf(eventCounter.incrementAndGet());
		eventTransports.put(eventId, transportId);
		return eventId;
	}

	/**
	 * Used for replayability support to get the transport ID of a given event ID
	 * @param eventId
	 * @return The ID of the transport instance that the given event ID was sent over
	 */
	public String getTransportIdForEvent(String eventId) {
		return eventTransports.get(eventId);
	}

	/**
	 * Used for replayability support to set the event history of a given transport ID
	 * @param transportId
	 * @param eventHistory
	 */
	public void setTransportEventHistory(String transportId, Map<String, SseEvent> eventHistory) {
		transportEventHistories.put(transportId, eventHistory);
	}

	/**
	 * Used for replayability support to retrieve the entire event history for a given
	 * transport ID
	 * @param transportId
	 * @return Map of SseEvent objects, keyed by event ID
	 */
	public Map<String, SseEvent> getTransportEventHistory(String transportId) {
		return transportEventHistories.get(transportId);
	}

	/**
	 * Registers a transport for this session.
	 * @param transportId unique identifier for the transport
	 * @param transport the transport instance
	 */
	public void registerTransport(String transportId, McpServerTransport transport) {
		if (transportId.equals(LISTENING_TRANSPORT)) {
			this.listeningTransport = transport;
			logger.debug("Registered listening transport for session {}", id);
			return;
		}
		transports.put(transportId, transport);
		logger.debug("Registered transport {} for session {}", transportId, id);
	}

	/**
	 * Unregisters a transport from this session.
	 * @param transportId the transport identifier to remove
	 */
	public void unregisterTransport(String transportId) {
		if (transportId.equals(LISTENING_TRANSPORT)) {
			this.listeningTransport = null;
			logger.debug("Unregistered listening transport for session {}", id);
			return;
		}
		McpServerTransport removed = transports.remove(transportId);
		if (removed != null) {
			logger.debug("Unregistered transport {} from session {}", transportId, id);
		}
	}

	/**
	 * Gets a transport by its identifier.
	 * @param transportId the transport identifier
	 * @return the transport, or null if not found
	 */
	public McpServerTransport getTransport(String transportId) {
		if (transportId.equals(LISTENING_TRANSPORT)) {
			return this.listeningTransport;
		}
		logger.debug("Found transport {} in session {}", transportId, id);
		return transports.get(transportId);
	}

	/**
	 * Called upon successful initialization sequence between the client and the server
	 * with the client capabilities and information.
	 *
	 * <a href=
	 * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">Initialization
	 * Spec</a>
	 * @param clientCapabilities the capabilities the connected client provides
	 * @param clientInfo the information about the connected client
	 */
	public void init(McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo) {
		this.clientCapabilities.lazySet(clientCapabilities);
		this.clientInfo.lazySet(clientInfo);
	}

	public McpSchema.ClientCapabilities getClientCapabilities() {
		return this.clientCapabilities.get();
	}

	public McpSchema.Implementation getClientInfo() {
		return this.clientInfo.get();
	}

	private McpId generateRequestId() {
		return McpId.of(this.id + "-" + this.requestCounter.getAndIncrement());
	}

	/**
	 * Gets a request handler by method name.
	 */
	public RequestHandler<?> getRequestHandler(String method) {
		return requestHandlers.get(method);
	}

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
		return sendRequest(method, requestParams, typeRef, LISTENING_TRANSPORT);
	}

	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef, String transportId) {
		McpServerTransport transport = getTransport(transportId);
		if (transport == null) {
			// Fallback to listening transport if specific transport not found
			transport = getTransport(LISTENING_TRANSPORT);
			if (transport == null) {
				return Mono.error(new RuntimeException("Transport not found: " + transportId));
			}
		}

		final McpServerTransport finalTransport = transport;
		McpId requestId = this.generateRequestId();
		return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
			this.pendingResponses.put(requestId, sink);
			McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
					requestId, requestParams);
			Flux.from(finalTransport.sendMessage(jsonrpcRequest)).subscribe(v -> {
			}, error -> {
				this.pendingResponses.remove(requestId);
				sink.error(error);
			});
		}).timeout(requestTimeout).handle((jsonRpcResponse, sink) -> {
			if (jsonRpcResponse.error() != null) {
				sink.error(new McpError(jsonRpcResponse.error()));
			}
			else if (typeRef.getType().equals(Void.class)) {
				sink.complete();
			}
			else {
				T result = finalTransport.unmarshalFrom(jsonRpcResponse.result(), typeRef);
				sink.next(result);
			}
		});
	}

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		return sendNotification(method, params, LISTENING_TRANSPORT);
	}

	public Mono<Void> sendNotification(String method, Object params, String transportId) {
		McpServerTransport transport = getTransport(transportId);
		if (transport == null) {
			// Fallback to listening transport if specific transport not found
			transport = getTransport(LISTENING_TRANSPORT);
			if (transport == null) {
				return Mono.error(new RuntimeException("Transport not found: " + transportId));
			}
		}
		McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				method, params);
		return transport.sendMessage(jsonrpcNotification);
	}

	/**
	 * Called by the {@link McpServerTransportProvider} once the session is determined.
	 * The purpose of this method is to dispatch the message to an appropriate handler as
	 * specified by the MCP server implementation
	 * ({@link io.modelcontextprotocol.server.McpAsyncServer} or
	 * {@link io.modelcontextprotocol.server.McpSyncServer}) via
	 * {@link McpServerSession.Factory} that the server creates.
	 * @param message the incoming JSON-RPC message
	 * @return a Mono that completes when the message is processed
	 */
	public Mono<Void> handle(McpSchema.JSONRPCMessage message) {
		return Mono.defer(() -> {
			// TODO handle errors for communication to without initialization happening
			// first
			if (message instanceof McpSchema.JSONRPCResponse response) {
				logger.debug("Received Response: {}", response);
				var sink = pendingResponses.remove(response.id());
				if (sink == null) {
					logger.warn("Unexpected response for unknown id {}", response.id());
				}
				else {
					sink.success(response);
				}
				return Mono.empty();
			}
			else if (message instanceof McpSchema.JSONRPCRequest request) {
				logger.debug("Received request: {}", request);
				final String transportId = determineTransportId(request);
				return handleIncomingRequest(request).onErrorResume(error -> {
					var errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
									error.getMessage(), null));
					McpServerTransport transport = getTransportWithFallback(transportId);
					return transport != null ? transport.sendMessage(errorResponse).then(Mono.empty()) : Mono.empty();
				}).flatMap(response -> {
					McpServerTransport transport = getTransportWithFallback(transportId);
					if (transport != null) {
						return transport.sendMessage(response);
					}
					else {
						return Mono.error(new RuntimeException("No transport available"));
					}
				});
			}
			else if (message instanceof McpSchema.JSONRPCNotification notification) {
				// TODO handle errors for communication to without initialization
				// happening first
				logger.debug("Received notification: {}", notification);
				// TODO: in case of error, should the POST request be signalled?
				return handleIncomingNotification(notification)
					.doOnError(error -> logger.error("Error handling notification: {}", error.getMessage()));
			}
			else {
				logger.warn("Received unknown message type: {}", message);
				return Mono.empty();
			}
		});
	}

	/**
	 * Handles an incoming JSON-RPC request by routing it to the appropriate handler.
	 * @param request The incoming JSON-RPC request
	 * @return A Mono containing the JSON-RPC response
	 */
	private Mono<McpSchema.JSONRPCResponse> handleIncomingRequest(McpSchema.JSONRPCRequest request) {
		return Mono.defer(() -> {
			Mono<?> resultMono;
			if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
				// TODO handle situation where already initialized!
				McpSchema.InitializeRequest initializeRequest = transports.isEmpty() ? listeningTransport
					.unmarshalFrom(request.params(), new TypeReference<McpSchema.InitializeRequest>() {
					}) : transports.get(String.valueOf(request.id()))
						.unmarshalFrom(request.params(), new TypeReference<McpSchema.InitializeRequest>() {
						});

				this.state.lazySet(STATE_INITIALIZING);
				this.init(initializeRequest.capabilities(), initializeRequest.clientInfo());
				resultMono = this.initRequestHandler.handle(initializeRequest);
			}
			else {
				// TODO handle errors for communication to this session without
				// initialization happening first
				var handler = this.requestHandlers.get(request.method());
				if (handler == null) {
					MethodNotFoundError error = getMethodNotFoundError(request.method());
					return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
									error.message(), error.data())));
				}

				McpAsyncServerExchange requestExchange = new McpAsyncServerExchange(this, clientCapabilities.get(),
						clientInfo.get(), determineTransportId(request));
				requestExchange.setMinLoggingLevel(minLoggingLevel);
				resultMono = handler.handle(requestExchange, request.params());
			}
			return resultMono
				.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
				.onErrorResume(error -> Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						null, new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
								error.getMessage(), null)))); // TODO: add error message
																// through the data field
		});
	}

	/**
	 * Handles an incoming JSON-RPC notification by routing it to the appropriate handler.
	 * @param notification The incoming JSON-RPC notification
	 * @return A Mono that completes when the notification is processed
	 */
	private Mono<Void> handleIncomingNotification(McpSchema.JSONRPCNotification notification) {
		return Mono.defer(() -> {
			if (McpSchema.METHOD_NOTIFICATION_INITIALIZED.equals(notification.method())) {
				this.state.lazySet(STATE_INITIALIZED);
				return this.initNotificationHandler.handle();
			}

			var handler = notificationHandlers.get(notification.method());
			if (handler == null) {
				logger.error("No handler registered for notification method: {}", notification.method());
				return Mono.empty();
			}
			McpAsyncServerExchange notificationExchange = new McpAsyncServerExchange(this, clientCapabilities.get(),
					clientInfo.get(), LISTENING_TRANSPORT);
			notificationExchange.setMinLoggingLevel(minLoggingLevel);
			return handler.handle(notificationExchange, notification.params());
		});
	}

	record MethodNotFoundError(String method, String message, Object data) {
	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		return new MethodNotFoundError(method, "Method not found: " + method, null);
	}

	/**
	 * Determines the appropriate transport ID for a request. Uses request ID for
	 * per-request routing only if a transport with that ID exists, otherwise falls back
	 * to listening transport.
	 */
	private String determineTransportId(McpSchema.JSONRPCRequest request) {
		String requestTransportId = request.id().toString();
		// Check if a transport exists for this specific request ID
		if (getTransport(requestTransportId) != null) {
			return requestTransportId;
		}
		// Fallback to listening transport
		return LISTENING_TRANSPORT;
	}

	/**
	 * Gets a transport with fallback to listening transport.
	 */
	private McpServerTransport getTransportWithFallback(String transportId) {
		McpServerTransport transport = getTransport(transportId);
		if (transport == null) {
			transport = getTransport(LISTENING_TRANSPORT);
		}
		return transport;
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			List<Mono<Void>> closeTasks = new ArrayList<>();

			// Add listening transport if it exists
			if (listeningTransport != null) {
				closeTasks.add(listeningTransport.closeGracefully());
			}

			// Add all transports from the map
			closeTasks.addAll(transports.values().stream().map(McpServerTransport::closeGracefully).toList());

			return Mono.when(closeTasks);
		});
	}

	@Override
	public void close() {
		if (listeningTransport != null) {
			listeningTransport.close();
		}
		transports.values().forEach(McpServerTransport::close);
		transports.clear();
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
	 * Notification handler for the initialization notification from the client.
	 */
	public interface InitNotificationHandler {

		/**
		 * Specifies an action to take upon successful initialization.
		 * @return a Mono that will complete when the initialization is acted upon.
		 */
		Mono<Void> handle();

	}

	/**
	 * A handler for client-initiated notifications.
	 */
	public interface NotificationHandler {

		/**
		 * Handles a notification from the client.
		 * @param exchange the exchange associated with the client that allows calling
		 * back to the connected client or inspecting its capabilities.
		 * @param params the parameters of the notification.
		 * @return a Mono that completes once the notification is handled.
		 */
		Mono<Void> handle(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * A handler for client-initiated requests.
	 *
	 * @param <T> the type of the response that is expected as a result of handling the
	 * request.
	 */
	public interface RequestHandler<T> {

		/**
		 * Handles a request from the client.
		 * @param exchange the exchange associated with the client that allows calling
		 * back to the connected client or inspecting its capabilities.
		 * @param params the parameters of the request.
		 * @return a Mono that will emit the response to the request.
		 */
		Mono<T> handle(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * A handler for client-initiated requests return Flux.
	 *
	 * @param <T> the type of the response that is expected as a result of handling the
	 * request.
	 */
	public interface StreamingRequestHandler<T> extends RequestHandler<T> {

		/**
		 * Handles a request from the client which invokes a streamTool.
		 * @param exchange the exchange associated with the client that allows calling
		 * back to the connected client or inspecting its capabilities.
		 * @param params the parameters of the request.
		 * @return Flux that will emit the response to the request.
		 */
		Flux<T> handleStreaming(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * Factory for creating server sessions which delegate to a provided 1:1 transport
	 * with a connected client.
	 */
	@FunctionalInterface
	public interface Factory {

		/**
		 * Creates a new 1:1 representation of the client-server interaction.
		 * @param sessionTransport the transport to use for communication with the client.
		 * @return a new server session.
		 */
		McpServerSession create(McpServerTransport sessionTransport);

	}

	/**
	 * Factory for creating server sessions which delegate to a provided 1:1 transport
	 * with a connected client.
	 */
	@FunctionalInterface
	public interface StreamableHttpSessionFactory {

		/**
		 * Creates a new 1:1 representation of the client-server interaction.
		 * @param transportId ID of the JSONRPCRequest/JSONRPCResponse the transport is
		 * serving.
		 * @return a new server session.
		 */
		McpServerSession create(String transportId);

	}

}
