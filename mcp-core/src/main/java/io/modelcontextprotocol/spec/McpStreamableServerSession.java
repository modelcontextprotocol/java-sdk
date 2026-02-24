/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private volatile McpSchema.LoggingLevel minLoggingLevel = McpSchema.LoggingLevel.INFO;

	private McpEventStore eventStore;

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
			Map<String, McpRequestHandler<?>> requestHandlers, Map<String, McpNotificationHandler> notificationHandlers,
			McpEventStore eventStore) {
		this.id = id;
		this.missingMcpTransportSession = new MissingMcpTransportSession(id);
		this.listeningStreamRef = new AtomicReference<>(this.missingMcpTransportSession);
		this.clientCapabilities.lazySet(clientCapabilities);
		this.clientInfo.lazySet(clientInfo);
		this.requestTimeout = requestTimeout;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
		this.eventStore = eventStore;
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
		return listeningStream;
	}

	/**
	 * Replay message that were send after the given message ID on same stream. If no
	 * message store is configuraed or the message ID is (#code null}, returns on empty
	 * Flux.
	 * @param lastEventId the last message ID the client received
	 * @return a Flux of stored events to replay, each contains the message ID and message
	 */
	public Flux<StoredEvent> replay(String lastEventId) {
		if (this.eventStore == null || lastEventId == null) {
			return Flux.empty();
		}

		return this.eventStore.replayEventsAfter(MessageId.from(lastEventId));
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
				.flatMap(stream::storeAndSave)
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
			return listeningStream.closeGracefully();
			// TODO: Also close all the open streams
		});
	}

	@Override
	public void close() {
		McpLoggableSession listeningStream = this.listeningStreamRef.getAndSet(missingMcpTransportSession);
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

		private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

		private final McpStreamableServerTransport transport;

		private final String transportId;

		private final Supplier<MessageId> messageIdGenerator;

		/**
		 * Constructor accepting the dedicated transport representing the SSE stream.
		 * @param transport request-specific SSE transport stream
		 */
		public McpStreamableServerSessionStream(McpStreamableServerTransport transport) {
			this.transport = transport;
			this.transportId = UUID.randomUUID().toString();
			// This ID design allows for a constant-time extraction of the history by
			// precisely identifying the SSE stream using the first component
			this.messageIdGenerator = () -> MessageId.of(this.transportId, UUID.randomUUID().toString());
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
				McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
						method, requestId, requestParams);
				storeAndSave(jsonrpcRequest).subscribe(v -> {
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
			return storeAndSave(jsonrpcNotification);
		}

		Mono<Void> storeAndSave(McpSchema.JSONRPCMessage message) {
			McpEventStore store = McpStreamableServerSession.this.eventStore;
			MessageId messageId = this.messageIdGenerator.get();

			if (store != null) {
				return store.storeEvent(messageId, message)
					.doOnNext(v -> this.transport.sendMessage(message, messageId.value()));
			}

			return this.transport.sendMessage(message, messageId.value());
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

				return McpStreamableServerSession.this.eventStore.clearEventsOfTransportAfterTtl(transportId)
					.then(this.transport.closeGracefully());
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
