package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class McpStreamableServerSession implements McpSession {

	private static final Logger logger = LoggerFactory.getLogger(McpStreamableServerSession.class);

	private final ConcurrentHashMap<Object, McpStreamableServerSessionStream> requestIdToStream = new ConcurrentHashMap<>();

	private final String id;

	private final Duration requestTimeout;

	private final AtomicLong requestCounter = new AtomicLong(0);

	private final InitNotificationHandler initNotificationHandler;

	private final Map<String, McpRequestHandler<?>> requestHandlers;

	private final Map<String, McpNotificationHandler> notificationHandlers;

	private final AtomicReference<McpSchema.ClientCapabilities> clientCapabilities = new AtomicReference<>();

	private final AtomicReference<McpSchema.Implementation> clientInfo = new AtomicReference<>();

	private static final int STATE_UNINITIALIZED = 0;

	private static final int STATE_INITIALIZING = 1;

	private static final int STATE_INITIALIZED = 2;

	private final AtomicInteger state = new AtomicInteger(STATE_UNINITIALIZED);

	private final AtomicReference<McpStreamableServerSessionStream> listeningStreamRef = new AtomicReference<>();

	public McpStreamableServerSession(String id, McpSchema.ClientCapabilities clientCapabilities,
			McpSchema.Implementation clientInfo, Duration requestTimeout,
			InitNotificationHandler initNotificationHandler, Map<String, McpRequestHandler<?>> requestHandlers,
			Map<String, McpNotificationHandler> notificationHandlers) {
		this.id = id;
		this.clientCapabilities.lazySet(clientCapabilities);
		this.clientInfo.lazySet(clientInfo);
		this.requestTimeout = requestTimeout;
		this.initNotificationHandler = initNotificationHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	public String getId() {
		return this.id;
	}

	private String generateRequestId() {
		return this.id + "-" + this.requestCounter.getAndIncrement();
	}

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
		return Mono.defer(() -> {
			McpStreamableServerSessionStream listeningStream = this.listeningStreamRef.get();
			return listeningStream != null ? listeningStream.sendRequest(method, requestParams, typeRef)
					: Mono.error(new RuntimeException("Generic stream is unavailable for session " + this.id));
		});
	}

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		return Mono.defer(() -> {
			McpStreamableServerSessionStream listeningStream = this.listeningStreamRef.get();
			return listeningStream != null ? listeningStream.sendNotification(method, params)
					: Mono.error(new RuntimeException("Generic stream is unavailable for session " + this.id));
		});
	}

	public McpStreamableServerSessionStream listeningStream(McpServerTransport transport) {
		McpStreamableServerSessionStream listeningStream = new McpStreamableServerSessionStream(transport);
		this.listeningStreamRef.set(listeningStream);
		return listeningStream;
	}

	// TODO: keep track of history by keeping a map from eventId to stream and then
	// iterate over the events using the lastEventId
	public Flux<McpSchema.JSONRPCMessage> replay(Object lastEventId) {
		return Flux.empty();
	}

	public Mono<Void> responseStream(McpSchema.JSONRPCRequest jsonrpcRequest, McpServerTransport transport) {
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
				.handle(new McpAsyncServerExchange(stream, clientCapabilities.get(), clientInfo.get(),
						transportContext), jsonrpcRequest.params())
				.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, jsonrpcRequest.id(), result,
						null))
				.flatMap(transport::sendMessage)
				.then(transport.closeGracefully());
		});
	}

	public Mono<Void> accept(McpSchema.JSONRPCNotification notification) {
		return Mono.deferContextual(ctx -> {
			McpTransportContext transportContext = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
			McpNotificationHandler notificationHandler = this.notificationHandlers.get(notification.method());
			if (notificationHandler == null) {
				logger.error("No handler registered for notification method: {}", notification.method());
				return Mono.empty();
			}
			McpStreamableServerSessionStream listeningStream = this.listeningStreamRef.get();
			return notificationHandler.handle(
					new McpAsyncServerExchange(
							listeningStream != null ? listeningStream : MissingMcpTransportSession.INSTANCE,
							this.clientCapabilities.get(), this.clientInfo.get(), transportContext),
					notification.params());
		});

	}

	public Mono<Void> accept(McpSchema.JSONRPCResponse response) {
		return Mono.defer(() -> {
			var stream = this.requestIdToStream.get(response.id());
			if (stream == null) {
				return Mono.error(new McpError("Unexpected response for unknown id " + response.id())); // TODO
																										// JSONize
			}
			var sink = stream.pendingResponses.remove(response.id());
			if (sink == null) {
				return Mono.error(new McpError("Unexpected response for unknown id " + response.id())); // TODO
																										// JSONize
			}
			else {
				sink.success(response);
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
			McpStreamableServerSessionStream listeningStream = this.listeningStreamRef.get();
			return listeningStream != null ? listeningStream.closeGracefully() : Mono.empty(); // TODO:
																								// Also
																								// close
																								// all
																								// the
																								// open
																								// streams
		});
	}

	@Override
	public void close() {
		McpStreamableServerSessionStream listeningStream = this.listeningStreamRef.get();
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
	 * Notification handler for the initialization notification from the client.
	 */
	public interface InitNotificationHandler {

		/**
		 * Specifies an action to take upon successful initialization.
		 * @return a Mono that will complete when the initialization is acted upon.
		 */
		Mono<Void> handle();

	}

	public interface Factory {

		McpStreamableServerSessionInit startSession(McpSchema.InitializeRequest initializeRequest);

	}

	public record McpStreamableServerSessionInit(McpStreamableServerSession session,
			Mono<McpSchema.InitializeResult> initResult) {
	}

	public final class McpStreamableServerSessionStream implements McpSession {

		private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

		private final McpServerTransport transport;

		public McpStreamableServerSessionStream(McpServerTransport transport) {
			this.transport = transport;
		}

		@Override
		public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
			String requestId = McpStreamableServerSession.this.generateRequestId();

			McpStreamableServerSession.this.requestIdToStream.put(requestId, this);

			return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
				this.pendingResponses.put(requestId, sink);
				McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
						method, requestId, requestParams);
				this.transport.sendMessage(jsonrpcRequest).subscribe(v -> {
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
			return this.transport.sendMessage(jsonrpcNotification);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.defer(() -> {
				this.pendingResponses.values().forEach(s -> s.error(new RuntimeException("Stream closed")));
				this.pendingResponses.clear();
				// If this was the generic stream, reset it
				McpStreamableServerSession.this.listeningStreamRef.compareAndExchange(this, null);
				McpStreamableServerSession.this.requestIdToStream.values().removeIf(this::equals);
				return this.transport.closeGracefully();
			});
		}

		@Override
		public void close() {
			this.pendingResponses.values().forEach(s -> s.error(new RuntimeException("Stream closed")));
			this.pendingResponses.clear();
			// If this was the generic stream, reset it
			McpStreamableServerSession.this.listeningStreamRef.compareAndExchange(this, null);
			McpStreamableServerSession.this.requestIdToStream.values().removeIf(this::equals);
			this.transport.close();
		}

	}

}
