package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Aliaksei_Darafeyeu
 */
public class McpStatefulSession implements McpSession, McpLastEventId {

	/**
	 * Logger for this class
	 */
	private static final Logger logger = LoggerFactory.getLogger(McpStatefulSession.class);

	private static final int MAX_EVENT_HISTORY = 100;

	private final LinkedHashMap<String, McpSchema.JSONRPCMessage> eventHistory = new LinkedHashMap<>(MAX_EVENT_HISTORY,
			0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, McpSchema.JSONRPCMessage> eldest) {
			return size() > MAX_EVENT_HISTORY;
		}
	};

	private final Sinks.One<McpAsyncServerExchange> exchangeSink = Sinks.one();

	private final Map<String, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

	private final AtomicReference<McpSchema.ClientCapabilities> clientCapabilities = new AtomicReference<>();

	private final AtomicReference<McpSchema.Implementation> clientInfo = new AtomicReference<>();

	private final AtomicInteger state = new AtomicInteger(McpServerSession.STATE_UNINITIALIZED);

	private final String id;

	private final McpServerTransport transport;

	private final Map<String, McpServerSession.RequestHandler<?>> requestHandlers;

	private final Map<String, McpServerSession.NotificationHandler> notificationHandlers;

	private final McpServerSession.InitRequestHandler initRequestHandler;

	private final McpServerSession.InitNotificationHandler initNotificationHandler;

	public McpStatefulSession(final String id, final McpServerTransport transport,
			final Map<String, McpServerSession.RequestHandler<?>> requestHandlers,
			final Map<String, McpServerSession.NotificationHandler> notificationHandlers,
			final McpServerSession.InitRequestHandler initRequestHandler,
			final McpServerSession.InitNotificationHandler initNotificationHandler) {
		this.id = id;
		this.transport = transport;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
		this.initRequestHandler = initRequestHandler;
		this.initNotificationHandler = initNotificationHandler;
	}

	public void init(final McpSchema.ClientCapabilities clientCapabilities, final McpSchema.Implementation clientInfo) {
		this.clientCapabilities.lazySet(clientCapabilities);
		this.clientInfo.lazySet(clientInfo);
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public Mono<Void> handle(final McpSchema.JSONRPCMessage message) {
		if (message instanceof McpSchema.JSONRPCResponse response) {
			MonoSink<McpSchema.JSONRPCResponse> sink = pendingResponses.remove(response.id());
			if (sink != null) {
				sink.success(response);
			}
			return Mono.empty();
		}
		else if (message instanceof McpSchema.JSONRPCRequest request) {
			if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
				McpSchema.InitializeRequest initializeRequest = transport.unmarshalFrom(request.params(),
						new TypeReference<>() {
						});
				this.state.lazySet(McpServerSession.STATE_INITIALIZING);
				this.init(initializeRequest.capabilities(), initializeRequest.clientInfo());

				return this.initRequestHandler.handle(initializeRequest)
					.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
					.flatMap(this::storeAndSendMessage);
			}

			final McpServerSession.RequestHandler<?> handler = requestHandlers.get(request.method());
			if (handler == null) {
				McpSchema.JSONRPCResponse error = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						null, new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
								"Unknown method: " + request.method(), null));
				return transport.sendMessage(error);
			}
			return this.exchangeSink.asMono()
				.flatMap(exchange -> handler.handle(exchange, request.params()))
				.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
				.flatMap(this::storeAndSendMessage);
		}
		else if (message instanceof McpSchema.JSONRPCNotification notification) {
			if (McpSchema.METHOD_NOTIFICATION_INITIALIZED.equals(notification.method())) {
				this.state.lazySet(McpServerSession.STATE_INITIALIZED);
				exchangeSink.tryEmitValue(new McpAsyncServerExchange(this, clientCapabilities.get(), clientInfo.get()));
				return this.initNotificationHandler.handle();
			}

			final McpServerSession.NotificationHandler handler = notificationHandlers.get(notification.method());
			if (handler != null) {
				return this.exchangeSink.asMono().flatMap(exchange -> handler.handle(exchange, notification.params()));
			}
			return Mono.empty();
		}
		return Mono.empty();
	}

	@Override
	public void resumeFrom(final String lastEventId) {
		logger.info("session received Last-Event-ID: {}", lastEventId);
		if (lastEventId != null && !lastEventId.isBlank()) {
			return;
		}
		boolean resume = false;
		for (Map.Entry<String, McpSchema.JSONRPCMessage> entry : eventHistory.entrySet()) {
			if (entry.getKey().equals(lastEventId)) {
				resume = true;
			}
			if (resume) {
				transport.sendMessage(entry.getValue()).subscribe();
			}
		}

		// todo if resume false, replay all ...
	}

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		logger.debug("sendNotification: {}, {}", method, params);
		return transport.sendMessage(new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, method, params));
	}

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
		logger.debug("sendRequest: {}, {}, {}", method, requestParams, typeRef);

		// add requestId creations
		final String requestId = UUID.randomUUID().toString();
		return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
			pendingResponses.put(requestId, sink);
			McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
					requestId, requestParams);
			transport.sendMessage(request).subscribe();
		}).timeout(Duration.ofSeconds(10)).handle((response, sink) -> {
			if (response.error() != null) {
				sink.error(new RuntimeException(response.error().message()));
			}
			else {
				sink.next(transport.unmarshalFrom(response.result(), typeRef));
			}
		});
	}

	private Mono<Void> storeAndSendMessage(McpSchema.JSONRPCMessage message) {
		if (message instanceof McpSchema.JSONRPCRequest rq && rq.id() != null) {
			eventHistory.put(rq.id().toString(), message);
		}
		return transport.sendMessage(message);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return transport.closeGracefully();
	}

	@Override
	public void close() {
		closeGracefully().subscribe();
	}

}
