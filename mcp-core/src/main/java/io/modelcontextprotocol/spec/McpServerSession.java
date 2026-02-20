/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpInitRequestHandler;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

/**
 * Represents a Model Context Protocol (MCP) session on the server side. It manages
 * bidirectional JSON-RPC communication with the client.
 */
public class McpServerSession implements McpLoggableSession {

	private static final Logger logger = LoggerFactory.getLogger(McpServerSession.class);

	private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

	private final String id;

	/** Duration to wait for request responses before timing out */
	private final Duration requestTimeout;

	private final AtomicLong requestCounter = new AtomicLong(0);

	private final McpInitRequestHandler initRequestHandler;

	private final Map<String, McpRequestHandler<?>> requestHandlers;

	private final Map<String, McpNotificationHandler> notificationHandlers;

	private final McpServerTransport transport;

	private final Sinks.One<McpAsyncServerExchange> exchangeSink = Sinks.one();

	private final AtomicReference<McpSchema.ClientCapabilities> clientCapabilities = new AtomicReference<>();

	private final AtomicReference<McpSchema.Implementation> clientInfo = new AtomicReference<>();

	private static final int STATE_UNINITIALIZED = 0;

	private static final int STATE_INITIALIZING = 1;

	private static final int STATE_INITIALIZED = 2;

	private final AtomicInteger state = new AtomicInteger(STATE_UNINITIALIZED);

	private final ConcurrentHashMap<String, Disposable> inProgressInbound = new ConcurrentHashMap<>();

	private static final TypeRef<McpSchema.CancelledNotification> CANCELLED_NOTIFICATION_TYPE_REF = new TypeRef<>() {
	};

	private volatile McpSchema.LoggingLevel minLoggingLevel = McpSchema.LoggingLevel.INFO;

	/**
	 * Creates a new server session with the given parameters and the transport to use.
	 * @param id session id
	 * @param transport the transport to use
	 * @param initHandler called when a
	 * {@link io.modelcontextprotocol.spec.McpSchema.InitializeRequest} is received by the
	 * server
	 * @param requestHandlers map of request handlers to use
	 * @param notificationHandlers map of notification handlers to use
	 */
	public McpServerSession(String id, Duration requestTimeout, McpServerTransport transport,
			McpInitRequestHandler initHandler, Map<String, McpRequestHandler<?>> requestHandlers,
			Map<String, McpNotificationHandler> notificationHandlers) {
		this.id = id;
		this.requestTimeout = requestTimeout;
		this.transport = transport;
		this.initRequestHandler = initHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	/**
	 * Retrieve the session id.
	 * @return session id
	 */
	public String getId() {
		return this.id;
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

	private String generateRequestId() {
		return this.id + "-" + this.requestCounter.getAndIncrement();
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

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
		String requestId = this.generateRequestId();

		return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
			this.pendingResponses.put(requestId, sink);
			McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
					requestId, requestParams);
			this.transport.sendMessage(jsonrpcRequest).subscribe(v -> {
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
					sink.next(this.transport.unmarshalFrom(jsonRpcResponse.result(), typeRef));
				}
			}
		});
	}

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				method, params);
		return this.transport.sendMessage(jsonrpcNotification);
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
		return Mono.deferContextual(ctx -> {
			McpTransportContext transportContext = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);

			// TODO handle errors for communication to without initialization happening
			// first
			if (message instanceof McpSchema.JSONRPCResponse response) {
				logger.debug("Received response: {}", response);
				if (response.id() != null) {
					var sink = pendingResponses.remove(response.id());
					if (sink == null) {
						logger.warn("Unexpected response for unknown id {}", response.id());
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
			}
			else if (message instanceof McpSchema.JSONRPCRequest request) {
				logger.debug("Received request: {}", request);
				return handleIncomingRequest(request, transportContext).onErrorResume(error -> {
					McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = (error instanceof McpError mcpError
							&& mcpError.getJsonRpcError() != null) ? mcpError.getJsonRpcError()
									: new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
											error.getMessage(), McpError.aggregateExceptionMessages(error));
					var errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
							jsonRpcError);
					// TODO: Should the error go to SSE or back as POST return?
					return this.transport.sendMessage(errorResponse).then(Mono.empty());
				}).flatMap(this.transport::sendMessage).doOnSubscribe(sub -> {
					if (request.id() != null) {
						inProgressInbound.put(String.valueOf(request.id()), Disposables.composite(sub::cancel));
					}
				}).doFinally(signal -> {
					if (request.id() != null) {
						inProgressInbound.remove(String.valueOf(request.id()));
					}
				});
			}
			else if (message instanceof McpSchema.JSONRPCNotification notification) {
				// TODO handle errors for communication to without initialization
				// happening first
				logger.debug("Received notification: {}", notification);
				// TODO: in case of error, should the POST request be signalled?
				return handleIncomingNotification(notification, transportContext)
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
	 * @param transportContext
	 * @return A Mono containing the JSON-RPC response
	 */
	private Mono<McpSchema.JSONRPCResponse> handleIncomingRequest(McpSchema.JSONRPCRequest request,
			McpTransportContext transportContext) {
		return Mono.defer(() -> {
			Mono<?> resultMono;
			if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
				// TODO handle situation where already initialized!
				McpSchema.InitializeRequest initializeRequest = transport.unmarshalFrom(request.params(),
						new TypeRef<McpSchema.InitializeRequest>() {
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

				resultMono = this.exchangeSink.asMono()
					.flatMap(exchange -> handler.handle(copyExchange(exchange, transportContext), request.params()));
			}
			return resultMono
				.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
				.onErrorResume(error -> {
					McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = (error instanceof McpError mcpError
							&& mcpError.getJsonRpcError() != null) ? mcpError.getJsonRpcError()
									// TODO: add error message through the data field
									: new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
											error.getMessage(), McpError.aggregateExceptionMessages(error));
					return Mono.just(
							new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null, jsonRpcError));
				});
		});
	}

	/**
	 * Handles an incoming JSON-RPC notification by routing it to the appropriate handler.
	 * @param notification The incoming JSON-RPC notification
	 * @param transportContext
	 * @return A Mono that completes when the notification is processed
	 */
	private Mono<Void> handleIncomingNotification(McpSchema.JSONRPCNotification notification,
			McpTransportContext transportContext) {
		return Mono.defer(() -> {
			if (McpSchema.METHOD_NOTIFICATION_INITIALIZED.equals(notification.method())) {
				this.state.lazySet(STATE_INITIALIZED);
				// FIXME: The session ID passed here is not the same as the one in the
				// legacy SSE transport.
				exchangeSink.tryEmitValue(new McpAsyncServerExchange(this.id, this, clientCapabilities.get(),
						clientInfo.get(), transportContext));
			}

			if (McpSchema.METHOD_NOTIFICATION_CANCELLED.equals(notification.method())) {
				McpSchema.CancelledNotification cancelled = transport.unmarshalFrom(notification.params(),
						CANCELLED_NOTIFICATION_TYPE_REF);
				if (cancelled != null) {
					String normalizedId = String.valueOf(cancelled.requestId());
					logger.debug("Client cancelled request {}: {}", normalizedId, cancelled.reason());
					var inbound = this.inProgressInbound.remove(normalizedId);
					if (inbound != null && !inbound.isDisposed()) {
						logger.debug("Disposing in-progress request pipeline for {}", normalizedId);
						inbound.dispose();
					}
				}
			}

			var handler = notificationHandlers.get(notification.method());
			if (handler == null) {
				logger.warn("No handler registered for notification method: {}", notification);
				return Mono.empty();
			}
			return this.exchangeSink.asMono()
				.flatMap(exchange -> handler.handle(copyExchange(exchange, transportContext), notification.params()));
		});
	}

	/**
	 * This legacy implementation assumes an exchange is established upon the
	 * initialization phase see: exchangeSink.tryEmitValue(...), which creates a cached
	 * immutable exchange. Here, we create a new exchange and copy over everything from
	 * that cached exchange, and use it for a single HTTP request, with the transport
	 * context passed in.
	 */
	private McpAsyncServerExchange copyExchange(McpAsyncServerExchange exchange, McpTransportContext transportContext) {
		return new McpAsyncServerExchange(exchange.sessionId(), this, exchange.getClientCapabilities(),
				exchange.getClientInfo(), transportContext);
	}

	record MethodNotFoundError(String method, String message, Object data) {
	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		return new MethodNotFoundError(method, "Method not found: " + method, null);
	}

	/**
	 * Cancels a previously issued outbound request (server-to-client direction). The
	 * pending response is errored locally and a cancellation notification is sent to the
	 * client.
	 * @param requestId The ID of the request to cancel
	 * @param reason An optional human-readable reason
	 * @return A Mono that completes when the cancellation notification is sent
	 */
	public Mono<Void> sendCancellation(Object requestId, String reason) {
		return Mono.defer(() -> {
			var pending = this.pendingResponses.remove(requestId);
			if (pending != null) {
				pending.error(
						new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.REQUEST_CANCELLED,
								"Request cancelled locally" + (reason != null ? ": " + reason : ""), null)));
			}
			return this
				.sendNotification(McpSchema.METHOD_NOTIFICATION_CANCELLED,
						new McpSchema.CancelledNotification(requestId, reason))
				.onErrorResume(e -> {
					logger.warn("Failed to send cancellation notification for request {}", requestId, e);
					return Mono.empty();
				});
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		// TODO: clear pendingResponses and emit errors?
		return this.transport.closeGracefully();
	}

	@Override
	public void close() {
		// TODO: clear pendingResponses and emit errors?
		this.transport.close();
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

}
