package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent;
import io.modelcontextprotocol.client.transport.FlowSseClient.SseEventHandler;
import io.modelcontextprotocol.client.transport.FlowSseClient.SseEventSubscriber;
import io.modelcontextprotocol.spec.DefaultMcpTransportSession;
import io.modelcontextprotocol.spec.DefaultMcpTransportStream;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.spec.McpTransportSession;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.modelcontextprotocol.spec.McpTransportStream;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An implementation of the Streamable HTTP protocol as defined by the
 * <code>2025-03-26</code> version of the MCP specification.
 *
 * <p>
 * The transport is capable of resumability and reconnects. It reacts to transport-level
 * session invalidation and will propagate {@link McpTransportSessionNotFoundException
 * appropriate exceptions} to the higher level abstraction layer when needed in order to
 * allow proper state management. The implementation handles servers that are stateful and
 * provide session meta information, but can also communicate with stateless servers that
 * do not provide a session identifier and do not support SSE streams.
 * </p>
 * <p>
 * This implementation does not handle backwards compatibility with the <a href=
 * "https://modelcontextprotocol.io/specification/2024-11-05/basic/transports#http-with-sse">"HTTP
 * with SSE" transport</a>. In order to communicate over the phased-out
 * <code>2024-11-05</code> protocol, use {@link HttpClientSseClientTransport}
 * </p>
 *
 * @author taobaorun
 */
public class HttpClientStreamableHttpTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(HttpClientStreamableHttpTransport.class);

	private static final String DEFAULT_ENDPOINT = "/mcp";

	/**
	 * Event type for JSON-RPC messages received through the SSE connection. The server
	 * sends messages with this event type to transmit JSON-RPC protocol data.
	 */
	private static final String MESSAGE_EVENT_TYPE = "message";

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private static final String APPLICATION_JSON = "application/json";

	private static final String CONTENT_TYPE = "Content-Type";

	private static final String ACCEPT = "ACCEPT";

	private static final String MCP_SESSION_ID = "mcp-session-id";

	private static final String LAST_EVENT_ID = "last-event-id";

	/**
	 * HTTP client for sending messages to the server. Uses HTTP POST over the message
	 * endpoint
	 */
	private final HttpClient httpClient;

	/**
	 * HTTP request builder for building requests to send messages to the server
	 */
	private final HttpRequest.Builder requestBuilder;

	private final ObjectMapper objectMapper;

	/**
	 * Base URI for the MCP server
	 */
	private final URI baseUri;

	private final String endpoint;

	private final boolean openConnectionOnStartup;

	private final boolean resumableStreams;

	private final AtomicReference<DefaultMcpTransportSession> activeSession = new AtomicReference<>();

	private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handler = new AtomicReference<>();

	private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

	HttpClientStreamableHttpTransport(HttpClient.Builder clientBuilder, HttpRequest.Builder requestBuilder,
			String baseUri, String endpoint, ObjectMapper objectMapper, boolean openConnectionOnStartup,
			boolean resumableStreams) {

		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.hasText(baseUri, "baseUri must not be empty");
		Assert.notNull(clientBuilder, "clientBuilder must not be null");
		Assert.notNull(requestBuilder, "requestBuilder must not be null");
		this.baseUri = URI.create(baseUri);
		this.endpoint = endpoint;
		this.objectMapper = objectMapper;
		this.httpClient = clientBuilder.build();
		this.requestBuilder = requestBuilder;
		this.openConnectionOnStartup = openConnectionOnStartup;
		this.resumableStreams = resumableStreams;
		this.activeSession.set(createTransportSession());
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return Mono.deferContextual(ctx -> {
			this.handler.set(handler);
			if (openConnectionOnStartup) {
				logger.debug("Eagerly opening connection on startup");
				return this.reconnect(null).then();
			}
			return Mono.empty();
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			logger.debug("Graceful close triggered");
			DefaultMcpTransportSession currentSession = this.activeSession.getAndSet(createTransportSession());
			if (currentSession != null) {
				return currentSession.closeGracefully();
			}
			return Mono.empty();
		});
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return Mono.create(sink -> {
			logger.debug("Sending message {}", message);
			// Here we attempt to initialize the client.
			// In case the server supports SSE, we will establish a long-running session
			// here and
			// listen for messages.
			// If it doesn't, nothing actually happens here, that's just the way it is...
			final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
			final McpTransportSession<Disposable> transportSession = this.activeSession.get();

			URI clientUri = Utils.resolveUri(this.baseUri, this.endpoint);

			String body = null;
			try {
				body = objectMapper.writeValueAsString(message);
			}
			catch (JsonProcessingException e) {
				logger.error("Failed to serialize message", e);
				sink.error(new RuntimeException(e));
				return;
			}
			HttpRequest.Builder requestBuilder = this.requestBuilder.uri(clientUri)
				.header(CONTENT_TYPE, APPLICATION_JSON)
				.header(ACCEPT, String.format("%s, %s", TEXT_EVENT_STREAM, APPLICATION_JSON))
				.POST(BodyPublishers.ofString(body));
			transportSession.sessionId().ifPresent(id -> requestBuilder.setHeader(MCP_SESSION_ID, id));

			CompletableFuture<HttpResponse<Flux<McpSchema.JSONRPCMessage>>> future = this.httpClient
				.sendAsync(requestBuilder.build(), responseInfo -> {
					if (transportSession
						.markInitialized(responseInfo.headers().firstValue(MCP_SESSION_ID).orElse(null))) {
						// Once we have a session, we try to open an async stream for
						// the server to send notifications and requests out-of-band.
						reconnect(null).contextWrite(sink.contextView()).subscribe();
					}
					String sessionRepresentation = sessionIdOrPlaceholder(transportSession);

					// The spec mentions only ACCEPTED, but the existing SDKs can return
					// 200 OK for notifications
					if (is2xxSuccessful(responseInfo)) {
						Optional<String> contentType = responseInfo.headers().firstValue("content-type");
						// Existing SDKs consume notifications with no response body nor
						// content type
						if (contentType.isEmpty()) {
							logger.trace("Message was successfully sent via POST for session {}",
									sessionRepresentation);
							// signal the caller that the message was successfully
							// delivered
							sink.success();
							// communicate to downstream there is no streamed data coming
							return BodySubscribers.replacing(Flux.empty());
						}
						else {
							String mediaType = contentType.get();
							if (TEXT_EVENT_STREAM.equalsIgnoreCase(mediaType)) {
								// communicate to caller that the message was delivered
								sink.success();
								// starting a stream
								return newEventStream(responseInfo, sessionRepresentation);
							}
							else if (APPLICATION_JSON.equalsIgnoreCase(mediaType)) {
								logger.trace("Received response to POST for session {}", sessionRepresentation);
								// communicate to caller the message was delivered
								sink.success();
								return responseFlux();
							}
							else {
								logger.warn("Unknown media type {} returned for POST in session {}", contentType,
										sessionRepresentation);
								return BodySubscribers.replacing(Flux
									.error(new RuntimeException("Unknown media type returned: " + contentType)));
							}
						}
					}
					else {
						if (isNotFound(responseInfo)) {
							return BodySubscribers.replacing(mcpSessionNotFoundError(sessionRepresentation));
						}
						return BodySubscribers.mapping(BodySubscribers.ofByteArray(),
								bytes -> extractError(responseInfo, bytes, sessionRepresentation));
					}
				});
			CompletableFuture<Flux<JSONRPCMessage>> responseFuture = future.thenApply(HttpResponse::body)
				.thenApply(bodyFlux -> bodyFlux
					.flatMap(jsonRpcMessage -> this.handler.get().apply(Mono.just(jsonRpcMessage))))
				.exceptionally(t -> {
					this.handleException(t);
					sink.error(t);
					return Flux.empty();
				});
			Disposable connection = Mono.fromFuture(responseFuture).doFinally(signal -> {
				Disposable ref = disposableRef.getAndSet(null);
				if (ref != null) {
					transportSession.removeConnection(ref);
				}
			}).subscribe(f -> f.subscribe(jsonRpcMessage -> {
			}, sink::error));
			disposableRef.set(connection);
			transportSession.addConnection(connection);
		});
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	public static Builder builder(HttpClient.Builder clientBuilder) {
		return new Builder(clientBuilder);
	}

	private Mono<Disposable> reconnect(McpTransportStream<Disposable> stream) {
		return Mono.deferContextual(ctx -> {
			if (stream != null) {
				logger.debug("Reconnecting stream {} with lastId {}", stream.streamId(), stream.lastId());
			}
			else {
				logger.debug("Reconnecting with no prior stream");
			}
			// Here we attempt to initialize the client. In case the server supports SSE,
			// we will establish a long-running
			// session here and listen for messages. If it doesn't, that's ok, the server
			// is a simple, stateless one.
			final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
			final McpTransportSession<Disposable> transportSession = this.activeSession.get();

			URI clientUri = Utils.resolveUri(this.baseUri, this.endpoint);
			HttpRequest.Builder requestBuilder = this.requestBuilder.uri(clientUri)
				.GET()
				.header(ACCEPT, TEXT_EVENT_STREAM);
			transportSession.sessionId().ifPresent(id -> requestBuilder.setHeader(MCP_SESSION_ID, id));
			if (stream != null) {
				stream.lastId().ifPresent(id -> requestBuilder.header(LAST_EVENT_ID, id));
			}
			CompletableFuture<HttpResponse<Flux<McpSchema.JSONRPCMessage>>> future = this.httpClient
				.sendAsync(requestBuilder.build(), responseInfo -> {
					if (isEventStream(responseInfo)) {
						return eventStream(stream, responseInfo);
					}
					else if (isNotAllowed(responseInfo)) {
						logger.debug("The server does not support SSE streams, using request-response mode.");
						return BodySubscribers.replacing(Flux.empty());
					}
					else if (isNotFound(responseInfo)) {
						String sessionIdRepresentation = sessionIdOrPlaceholder(transportSession);
						return BodySubscribers.replacing(mcpSessionNotFoundError(sessionIdRepresentation));
					}
					else {
						String sessionIdRepresentation = sessionIdOrPlaceholder(transportSession);
						return BodySubscribers.mapping(BodySubscribers.ofByteArray(),
								bytes -> extractError(responseInfo, bytes, sessionIdRepresentation));
					}
				});
			CompletableFuture<Flux<JSONRPCMessage>> responseFuture = future.thenApply(HttpResponse::body)
				.exceptionally(t -> {
					this.handleException(t);
					return Flux.empty();
				});

			Disposable connection = Mono.fromFuture(responseFuture).doFinally(signal -> {
				Disposable ref = disposableRef.getAndSet(null);
				if (ref != null) {
					transportSession.removeConnection(ref);
				}
			}).subscribe();

			disposableRef.set(connection);
			transportSession.addConnection(connection);
			return Mono.just(connection);
		});
	}

	private static boolean isEventStream(ResponseInfo responseInfo) {
		return is2xxSuccessful(responseInfo) && responseInfo.headers()
			.firstValue("Content-Type")
			.map(type -> type.equals("text/event-stream"))
			.orElse(false);
	}

	private static boolean is2xxSuccessful(ResponseInfo responseInfo) {
		return responseInfo.statusCode() / 100 == 2;
	}

	private static boolean isNotAllowed(ResponseInfo responseInfo) {
		return responseInfo.statusCode() == 405;
	}

	private static boolean isNotFound(ResponseInfo responseInfo) {
		return responseInfo.statusCode() == 404;
	}

	private BodySubscriber<Flux<JSONRPCMessage>> eventStream(McpTransportStream<Disposable> stream,
			ResponseInfo responseInfo) {
		McpTransportStream<Disposable> sessionStream = stream != null ? stream
				: new DefaultMcpTransportStream<>(this.resumableStreams, this::reconnect);
		logger.debug("Connected stream {}", sessionStream.streamId());
		Sinks.Many<JSONRPCMessage> sink = Sinks.many().multicast().onBackpressureBuffer();
		return BodySubscribers.mapping(BodySubscribers.fromLineSubscriber(new SseEventSubscriber(new SseEventHandler() {
			@Override
			public void onEvent(SseEvent event) {
				Tuple2<Optional<String>, Iterable<JSONRPCMessage>> idWithMessages = parse(event);
				Mono.from(sessionStream.consumeSseStream(Flux.just(idWithMessages))).subscribe(sink::tryEmitNext);
			}

			@Override
			public void onError(Throwable error) {
				sink.tryEmitError(error);
			}
		})), o -> sink.asFlux());

	}

	private static String sessionIdOrPlaceholder(McpTransportSession<?> transportSession) {
		return transportSession.sessionId().orElse("[missing_session_id]");
	}

	private void handleException(Throwable t) {
		logger.debug("Handling exception for session {}", sessionIdOrPlaceholder(this.activeSession.get()), t);
		if (t instanceof McpTransportSessionNotFoundException) {
			McpTransportSession<?> invalidSession = this.activeSession.getAndSet(createTransportSession());
			logger.warn("Server does not recognize session {}. Invalidating.", invalidSession.sessionId());
			invalidSession.close();
		}
		Consumer<Throwable> handler = this.exceptionHandler.get();
		if (handler != null) {
			handler.accept(t);
		}
	}

	private DefaultMcpTransportSession createTransportSession() {
		Supplier<Publisher<Void>> onClose = () -> {
			DefaultMcpTransportSession transportSession = this.activeSession.get();
			URI clientUri = Utils.resolveUri(this.baseUri, this.endpoint);
			requestBuilder.uri(clientUri).DELETE();
			if (transportSession.sessionId().isEmpty()) {
				return Mono.empty();
			}
			else {
				try {
					requestBuilder.header("mcp-session-id", transportSession.sessionId().get());
					httpClient.send(requestBuilder.build(), BodyHandlers.discarding());
				}
				catch (IOException | InterruptedException e) {
					logger.error("delete mcp session:{} error", transportSession.sessionId().get(), e);
				}
				return Mono.empty().then();
			}
		};
		return new DefaultMcpTransportSession(onClose);
	}

	private Tuple2<Optional<String>, Iterable<JSONRPCMessage>> parse(SseEvent event) {
		if (MESSAGE_EVENT_TYPE.equals(event.type())) {
			try {
				// We don't support batching ATM and probably won't since the next version
				// considers removing it.
				System.out.println(event.data());
				McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, event.data());
				return Tuples.of(Optional.ofNullable(event.id()), List.of(message));
			}
			catch (IOException ioException) {
				throw new McpError("Error parsing JSON-RPC message: " + event.data());
			}
		}
		else {
			throw new McpError("Received unrecognized SSE event type: " + event.type());
		}
	}

	private static Flux<McpSchema.JSONRPCMessage> mcpSessionNotFoundError(String sessionRepresentation) {
		logger.warn("Session {} was not found on the MCP server", sessionRepresentation);
		// inform the stream/connection subscriber
		return Flux.error(new McpTransportSessionNotFoundException(sessionRepresentation));
	}

	private Flux<McpSchema.JSONRPCMessage> extractError(ResponseInfo responseInfo, byte[] body,
			String sessionRepresentation) {
		return Flux.create(sink -> {
			JSONRPCError jsonRpcError = null;
			Exception toPropagate;
			try {
				JSONRPCResponse jsonRpcResponse = objectMapper.readValue(body, McpSchema.JSONRPCResponse.class);
				jsonRpcError = jsonRpcResponse.error();
				toPropagate = new McpError(jsonRpcError);
			}
			catch (IOException ex) {
				toPropagate = new RuntimeException("Sending request failed", ex);
				logger.debug("Received content together with {} HTTP code response: {}", responseInfo.statusCode(),
						body);
			}

			// Some implementations can return 400 when presented with a
			// session id that it doesn't know about, so we will
			// invalidate the session
			// https://github.com/modelcontextprotocol/typescript-sdk/issues/389
			if (responseInfo.statusCode() == 400) {
				sink.error(new McpTransportSessionNotFoundException(sessionRepresentation, toPropagate));
			}
		});
	}

	private BodySubscriber<Flux<JSONRPCMessage>> newEventStream(ResponseInfo responseInfo,
			String sessionRepresentation) {
		McpTransportStream<Disposable> sessionStream = new DefaultMcpTransportStream<>(this.resumableStreams,
				this::reconnect);
		logger.trace("Sent POST and opened a stream ({}) for session {}", sessionStream.streamId(),
				sessionRepresentation);

		return eventStream(sessionStream, responseInfo);
	}

	private BodySubscriber<Flux<JSONRPCMessage>> responseFlux() {
		return BodySubscribers.mapping(BodySubscribers.ofString(StandardCharsets.UTF_8), bodyStr -> {
			try {
				JSONRPCMessage jsonRpcResponse = McpSchema.deserializeJsonRpcMessage(objectMapper, bodyStr);
				return Flux.just(jsonRpcResponse);
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * Builder for {@link HttpClientStreamableHttpTransport}.
	 */
	public static class Builder {

		private ObjectMapper objectMapper;

		private HttpClient.Builder clientBuilder;

		private HttpRequest.Builder requestBuilder;

		private String baseUrl;

		private String endpoint = DEFAULT_ENDPOINT;

		private boolean resumableStreams = true;

		private boolean openConnectionOnStartup = false;

		private Builder(HttpClient.Builder clientBuilder) {
			Assert.notNull(clientBuilder, "HttpClient.Builder must not be null");
			this.clientBuilder = clientBuilder;
		}

		/**
		 * Configure the {@link ObjectMapper} to use.
		 * @param objectMapper instance to use
		 * @return the builder instance
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		public Builder clientBuilder(HttpClient.Builder clientBuilder) {
			Assert.notNull(clientBuilder, "HttpClient.Builder must not be null");
			this.clientBuilder = clientBuilder;
			return this;
		}

		public Builder requestBuilder(HttpRequest.Builder requestBuilder) {
			Assert.notNull(clientBuilder, "HttpRequest.Builder must not be null");
			this.requestBuilder = requestBuilder;
			return this;
		}

		/**
		 * Customizes the HTTP client builder.
		 * @param requestCustomizer the consumer to customize the HTTP request builder
		 * @return this builder
		 */
		public Builder customizeRequest(final Consumer<HttpRequest.Builder> requestCustomizer) {
			Assert.notNull(requestCustomizer, "requestCustomizer must not be null");
			requestCustomizer.accept(requestBuilder);
			return this;
		}

		/**
		 * Configure the endpoint to make HTTP requests against.
		 * @param endpoint endpoint to use
		 * @return the builder instance
		 */
		public Builder endpoint(String endpoint) {
			Assert.hasText(endpoint, "endpoint must be a non-empty String");
			this.endpoint = endpoint;
			return this;
		}

		public Builder baseUrl(String baseUrl) {
			Assert.hasText(baseUrl, "baseUrl must be a non-empty String");
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * Configure whether to use the stream resumability feature by keeping track of
		 * SSE event ids.
		 * @param resumableStreams if {@code true} event ids will be tracked and upon
		 * disconnection, the last seen id will be used upon reconnection as a header to
		 * resume consuming messages.
		 * @return the builder instance
		 */
		public Builder resumableStreams(boolean resumableStreams) {
			this.resumableStreams = resumableStreams;
			return this;
		}

		/**
		 * Configure whether the client should open an SSE connection upon startup. Not
		 * all servers support this (although it is in theory possible with the current
		 * specification), so use with caution. By default, this value is {@code false}.
		 * @param openConnectionOnStartup if {@code true} the {@link #connect(Function)}
		 * method call will try to open an SSE connection before sending any JSON-RPC
		 * request
		 * @return the builder instance
		 */
		public Builder openConnectionOnStartup(boolean openConnectionOnStartup) {
			this.openConnectionOnStartup = openConnectionOnStartup;
			return this;
		}

		/**
		 * Construct a fresh instance of {@link HttpClientStreamableHttpTransport} using
		 * the current builder configuration.
		 * @return a new instance of {@link HttpClientStreamableHttpTransport}
		 */
		public HttpClientStreamableHttpTransport build() {
			ObjectMapper objectMapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper();
			HttpRequest.Builder requestBuilder = this.requestBuilder != null ? this.requestBuilder
					: HttpRequest.newBuilder();

			return new HttpClientStreamableHttpTransport(this.clientBuilder, requestBuilder, this.baseUrl,
					this.endpoint, objectMapper, this.openConnectionOnStartup, this.resumableStreams);
		}

	}

}
