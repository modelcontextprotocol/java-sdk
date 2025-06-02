/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

/**
 * Streamable HTTP MCP server session that manages multiple transport streams and handles both
 * immediate and streaming responses through a unified interface.
 *
 * <p>
 * This session implementation provides:
 * <ul>
 * <li>Unified management of multiple transport streams per session</li>
 * <li>Support for both immediate JSON responses and SSE streaming</li>
 * <li>Automatic response type detection based on handler return types</li>
 * <li>Proper lifecycle management for all associated transports</li>
 * </ul>
 */
public class McpStreamableHttpServerSession implements McpSession {

	private static final Logger logger = LoggerFactory.getLogger(McpStreamableHttpServerSession.class);

	private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpServerTransport> transports = new ConcurrentHashMap<>();

	private final String id;

	private final Duration requestTimeout;

	private final AtomicLong requestCounter = new AtomicLong(0);

	private final InitRequestHandler initRequestHandler;

	private final InitNotificationHandler initNotificationHandler;

	private final Map<String, RequestHandler<?>> requestHandlers;

	private final Map<String, NotificationHandler> notificationHandlers;

	private final Sinks.One<McpAsyncServerExchange> exchangeSink = Sinks.one();

	private final AtomicReference<McpSchema.ClientCapabilities> clientCapabilities = new AtomicReference<>();

	private final AtomicReference<McpSchema.Implementation> clientInfo = new AtomicReference<>();

	private static final int STATE_UNINITIALIZED = 0;

	private static final int STATE_INITIALIZING = 1;

	private static final int STATE_INITIALIZED = 2;

	private final AtomicInteger state = new AtomicInteger(STATE_UNINITIALIZED);

	/**
	 * Creates a new Streamable HTTP MCP server session.
	 * @param id session id
	 * @param requestTimeout timeout for requests
	 * @param initHandler initialization request handler
	 * @param initNotificationHandler initialization notification handler
	 * @param requestHandlers map of request handlers
	 * @param notificationHandlers map of notification handlers
	 */
	public McpStreamableHttpServerSession(String id, Duration requestTimeout, InitRequestHandler initHandler,
			InitNotificationHandler initNotificationHandler, Map<String, RequestHandler<?>> requestHandlers,
			Map<String, NotificationHandler> notificationHandlers) {
		this.id = id;
		this.requestTimeout = requestTimeout;
		this.initRequestHandler = initHandler;
		this.initNotificationHandler = initNotificationHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	/**
	 * Registers a transport for this session.
	 * @param transportId unique identifier for the transport
	 * @param transport the transport instance
	 */
	public void registerTransport(String transportId, McpServerTransport transport) {
		transports.put(transportId, transport);
		logger.debug("Registered transport {} for session {}", transportId, id);
	}

	/**
	 * Unregisters a transport from this session.
	 * @param transportId the transport identifier to remove
	 */
	public void unregisterTransport(String transportId) {
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
		return transports.get(transportId);
	}

	/**
	 * Handles a message using the specified transport.
	 * @param message the JSON-RPC message
	 * @param transportId the transport to use for responses
	 * @return a Mono that completes when the message is processed
	 */
	public Mono<Void> handleMessage(McpSchema.JSONRPCMessage message, String transportId) {
		McpServerTransport transport = transports.get(transportId);
		if (transport == null) {
			return Mono.error(new RuntimeException("Transport not found: " + transportId));
		}

		return Mono.defer(() -> {
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
				return handleIncomingRequest(request, transport).onErrorResume(error -> {
					var errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
									error.getMessage(), null));
					return transport.sendMessage(errorResponse).then(Mono.empty());
				}).flatMap(transport::sendMessage);
			}
			else if (message instanceof McpSchema.JSONRPCNotification notification) {
				logger.debug("Received notification: {}", notification);
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
	 * Handles incoming JSON-RPC requests.
	 */
	private Mono<McpSchema.JSONRPCResponse> handleIncomingRequest(McpSchema.JSONRPCRequest request,
			McpServerTransport transport) {
		return Mono.defer(() -> {
			Mono<?> resultMono;
			if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
				logger.info("[INIT] Processing initialize request for session {}", id);
				McpSchema.InitializeRequest initializeRequest = transport.unmarshalFrom(request.params(),
						new TypeReference<McpSchema.InitializeRequest>() {
						});

				this.state.lazySet(STATE_INITIALIZING);
				logger.debug("[INIT] Session {} state set to INITIALIZING", id);
				this.init(initializeRequest.capabilities(), initializeRequest.clientInfo());
				logger.debug("[INIT] Session {} client info stored", id);
				resultMono = this.initRequestHandler.handle(initializeRequest);
			}
			else {
				var handler = this.requestHandlers.get(request.method());
				if (handler == null) {
					MethodNotFoundError error = getMethodNotFoundError(request.method());
					return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
									error.message(), error.data())));
				}

				// Wait for initialization to complete, then handle the request
				resultMono = this.exchangeSink.asMono().flatMap(exchange -> handler.handle(exchange, request.params()));
			}

			return resultMono
				.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
				.onErrorResume(error -> Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						null, new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
								error.getMessage(), null))));
		});
	}

	/**
	 * Handles incoming JSON-RPC notifications.
	 */
	private Mono<Void> handleIncomingNotification(McpSchema.JSONRPCNotification notification) {
		return Mono.defer(() -> {
			if (McpSchema.METHOD_NOTIFICATION_INITIALIZED.equals(notification.method())) {
				logger.info("[INIT] Received initialized notification for session {}", id);
				this.state.lazySet(STATE_INITIALIZED);
				logger.debug("[INIT] Session {} state set to INITIALIZED", id);
				McpAsyncServerExchange exchange = new McpAsyncServerExchange(this, clientCapabilities.get(),
						clientInfo.get());
				logger.debug("[INIT] Created exchange for session {}: {}", id, exchange);
				exchangeSink.tryEmitValue(exchange);
				logger.info("[INIT] Session {} initialization complete - exchange emitted", id);
				return this.initNotificationHandler.handle();
			}

			var handler = notificationHandlers.get(notification.method());
			if (handler == null) {
				logger.error("No handler registered for notification method: {}", notification.method());
				return Mono.empty();
			}
			return this.exchangeSink.asMono().flatMap(exchange -> handler.handle(exchange, notification.params()));
		});
	}

	/**
	 * Retrieve the session id.
	 */
	public String getId() {
		return this.id;
	}

	/**
	 * Called upon successful initialization sequence.
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

	record MethodNotFoundError(String method, String message, Object data) {
	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		return new MethodNotFoundError(method, "Method not found: " + method, null);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Flux.fromIterable(transports.values()).flatMap(McpServerTransport::closeGracefully).then();
	}

	@Override
	public void close() {
		transports.values().forEach(McpServerTransport::close);
		transports.clear();
	}

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
		String requestId = this.generateRequestId();

		return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
			this.pendingResponses.put(requestId, sink);
			McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
					requestId, requestParams);

			Flux.fromIterable(transports.values())
				.flatMap(transport -> transport.sendMessage(jsonrpcRequest))
				.subscribe(v -> {
				}, error -> {
					this.pendingResponses.remove(requestId);
					sink.error(error);
				});
		}).timeout(requestTimeout).handle((jsonRpcResponse, sink) -> {
			if (jsonRpcResponse.error() != null) {
				sink.error(new McpError(jsonRpcResponse.error()));
			}
			else {
				if (typeRef.getType().equals(Void.class)) {
					sink.complete();
				}
				else {
					McpServerTransport transport = transports.values().iterator().next();
					sink.next(transport.unmarshalFrom(jsonRpcResponse.result(), typeRef));
				}
			}
		});
	}

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				method, params);

		return Flux.fromIterable(transports.values())
			.flatMap(transport -> transport.sendMessage(jsonrpcNotification))
			.then();
	}

	/**
	 * Gets a request handler by method name.
	 */
	public RequestHandler<?> getRequestHandler(String method) {
		return requestHandlers.get(method);
	}

	private String generateRequestId() {
		return this.id + "-" + this.requestCounter.getAndIncrement();
	}

	/**
	 * Request handler for the initialization request.
	 */
	public interface InitRequestHandler {

		Mono<McpSchema.InitializeResult> handle(McpSchema.InitializeRequest initializeRequest);

	}

	/**
	 * Notification handler for the initialization notification from the client.
	 */
	public interface InitNotificationHandler {

		Mono<Void> handle();

	}

	/**
	 * A handler for client-initiated notifications.
	 */
	public interface NotificationHandler {

		Mono<Void> handle(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * A handler for client-initiated requests.
	 */
	public interface RequestHandler<T> {

		Mono<T> handle(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * A handler for streaming requests that return Flux.
	 */
	public interface StreamingRequestHandler<T> extends RequestHandler<T> {

		Flux<T> handleStreaming(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * Factory for creating Streamable HTTP MCP server sessions.
	 */
	@FunctionalInterface
	public interface Factory {

		McpStreamableHttpServerSession create(String sessionId);

	}

}