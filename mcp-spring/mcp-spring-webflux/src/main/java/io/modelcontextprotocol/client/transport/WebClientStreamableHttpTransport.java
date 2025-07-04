package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.DefaultMcpTransportSession;
import io.modelcontextprotocol.spec.DefaultMcpTransportStream;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSession;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.modelcontextprotocol.spec.McpTransportStream;
import io.modelcontextprotocol.util.Assert;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

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
 * <code>2024-11-05</code> protocol, use {@link HttpClientSseClientTransport} or
 * {@link WebFluxSseClientTransport}.
 * </p>
 *
 * @author Dariusz Jędrzejczyk
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http">Streamable
 * HTTP transport specification</a>
 */
public class WebClientStreamableHttpTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebClientStreamableHttpTransport.class);

	private static final String DEFAULT_ENDPOINT = "/mcp";

	/**
	 * Event type for JSON-RPC messages received through the SSE connection. The server
	 * sends messages with this event type to transmit JSON-RPC protocol data.
	 */
	private static final String MESSAGE_EVENT_TYPE = "message";

	private static final ParameterizedTypeReference<ServerSentEvent<String>> PARAMETERIZED_TYPE_REF = new ParameterizedTypeReference<>() {
	};

	private final ObjectMapper objectMapper;

	private final WebClient webClient;

	private final String endpoint;

	private final boolean openConnectionOnStartup;

	private final boolean resumableStreams;

	private final AtomicReference<DefaultMcpTransportSession> activeSession = new AtomicReference<>();

	private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handler = new AtomicReference<>();

	private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

	private WebClientStreamableHttpTransport(ObjectMapper objectMapper, WebClient.Builder webClientBuilder,
			String endpoint, boolean resumableStreams, boolean openConnectionOnStartup) {
		this.objectMapper = objectMapper;
		this.webClient = webClientBuilder.build();
		this.endpoint = endpoint;
		this.resumableStreams = resumableStreams;
		this.openConnectionOnStartup = openConnectionOnStartup;
		this.activeSession.set(createTransportSession());
	}

	/**
	 * Create a stateful builder for creating {@link WebClientStreamableHttpTransport}
	 * instances.
	 * @param webClientBuilder the {@link WebClient.Builder} to use
	 * @return a builder which will create an instance of
	 * {@link WebClientStreamableHttpTransport} once {@link Builder#build()} is called
	 */
	public static Builder builder(WebClient.Builder webClientBuilder) {
		return new Builder(webClientBuilder);
	}

	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		return Mono.deferContextual(ctx -> {
			this.handler.set(handler);
			if (openConnectionOnStartup) {
				logger.debug("Eagerly opening connection on startup");
				return this.reconnect(null).then();
			}
			return Mono.empty();
		});
	}

	private DefaultMcpTransportSession createTransportSession() {
		Function<String, Publisher<Void>> onClose = sessionId -> sessionId == null ? Mono.empty()
				: webClient.delete().uri(this.endpoint).headers(httpHeaders -> {
					httpHeaders.add("mcp-session-id", sessionId);
				}).retrieve().toBodilessEntity().onErrorComplete(e -> {
					logger.warn("Got error when closing transport", e);
					return true;
				}).then();
		return new DefaultMcpTransportSession(onClose);
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		logger.debug("Exception handler registered");
		this.exceptionHandler.set(handler);
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

			Disposable connection = webClient.get()
				.uri(this.endpoint)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.headers(httpHeaders -> {
					transportSession.sessionId().ifPresent(id -> httpHeaders.add("mcp-session-id", id));
					if (stream != null) {
						stream.lastId().ifPresent(id -> httpHeaders.add("last-event-id", id));
					}
				})
				.exchangeToFlux(response -> {
					if (isEventStream(response)) {
						logger.debug("Established SSE stream via GET");
						return eventStream(stream, response);
					}
					else if (isNotAllowed(response)) {
						logger.debug("The server does not support SSE streams, using request-response mode.");
						return Flux.empty();
					}
					else if (isNotFound(response)) {
						String sessionIdRepresentation = sessionIdOrPlaceholder(transportSession);
						return mcpSessionNotFoundError(sessionIdRepresentation);
					}
					else {
						return response.<McpSchema.JSONRPCMessage>createError().doOnError(e -> {
							logger.info("Opening an SSE stream failed. This can be safely ignored.", e);
						}).flux();
					}
				})
				.flatMap(jsonrpcMessage -> this.handler.get().apply(Mono.just(jsonrpcMessage)))
				.onErrorComplete(t -> {
					this.handleException(t);
					return true;
				})
				.doFinally(s -> {
					Disposable ref = disposableRef.getAndSet(null);
					if (ref != null) {
						transportSession.removeConnection(ref);
					}
				})
				.contextWrite(ctx)
				.subscribe();

			disposableRef.set(connection);
			transportSession.addConnection(connection);
			return Mono.just(connection);
		});
	}

	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		return Mono.create(sink -> {
			logger.debug("Sending message {}", message);
			// Here we attempt to initialize the client.
			// In case the server supports SSE, we will establish a long-running session
			// here and
			// listen for messages.
			// If it doesn't, nothing actually happens here, that's just the way it is...
			final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
			final McpTransportSession<Disposable> transportSession = this.activeSession.get();

			Disposable connection = webClient.post()
				.uri(this.endpoint)
				.accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
				.headers(httpHeaders -> {
					transportSession.sessionId().ifPresent(id -> httpHeaders.add("mcp-session-id", id));
				})
				.bodyValue(message)
				.exchangeToFlux(response -> {
					if (transportSession
						.markInitialized(response.headers().asHttpHeaders().getFirst("mcp-session-id"))) {
						// Once we have a session, we try to open an async stream for
						// the server to send notifications and requests out-of-band.
						reconnect(null).contextWrite(sink.contextView()).subscribe();
					}

					String sessionRepresentation = sessionIdOrPlaceholder(transportSession);

					// The spec mentions only ACCEPTED, but the existing SDKs can return
					// 200 OK for notifications
					if (response.statusCode().is2xxSuccessful()) {
						Optional<MediaType> contentType = response.headers().contentType();
						// Existing SDKs consume notifications with no response body nor
						// content type
						if (contentType.isEmpty()) {
							logger.trace("Message was successfully sent via POST for session {}",
									sessionRepresentation);
							// signal the caller that the message was successfully
							// delivered
							sink.success();
							// communicate to downstream there is no streamed data coming
							return Flux.empty();
						}
						else {
							MediaType mediaType = contentType.get();
							if (mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
								logger.debug("Established SSE stream via POST");
								// communicate to caller that the message was delivered
								sink.success();
								// starting a stream
								return newEventStream(response, sessionRepresentation);
							}
							else if (mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
								logger.trace("Received response to POST for session {}", sessionRepresentation);
								// communicate to caller the message was delivered
								sink.success();
								return responseFlux(response);
							}
							else {
								logger.warn("Unknown media type {} returned for POST in session {}", contentType,
										sessionRepresentation);
								return Flux.error(new RuntimeException("Unknown media type returned: " + contentType));
							}
						}
					}
					else {
						if (isNotFound(response)) {
							return mcpSessionNotFoundError(sessionRepresentation);
						}
						return extractError(response, sessionRepresentation);
					}
				})
				.flatMap(jsonRpcMessage -> this.handler.get().apply(Mono.just(jsonRpcMessage)))
				.onErrorComplete(t -> {
					// handle the error first
					this.handleException(t);
					// inform the caller of sendMessage
					sink.error(t);
					return true;
				})
				.doFinally(s -> {
					Disposable ref = disposableRef.getAndSet(null);
					if (ref != null) {
						transportSession.removeConnection(ref);
					}
				})
				.contextWrite(sink.contextView())
				.subscribe();
			disposableRef.set(connection);
			transportSession.addConnection(connection);
		});
	}

	private static Flux<McpSchema.JSONRPCMessage> mcpSessionNotFoundError(String sessionRepresentation) {
		logger.warn("Session {} was not found on the MCP server", sessionRepresentation);
		// inform the stream/connection subscriber
		return Flux.error(new McpTransportSessionNotFoundException(sessionRepresentation));
	}

	private Flux<McpSchema.JSONRPCMessage> extractError(ClientResponse response, String sessionRepresentation) {
		return response.<McpSchema.JSONRPCMessage>createError().onErrorResume(e -> {
			WebClientResponseException responseException = (WebClientResponseException) e;
			byte[] body = responseException.getResponseBodyAsByteArray();
			McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = null;
			Exception toPropagate;
			try {
				McpSchema.JSONRPCResponse jsonRpcResponse = objectMapper.readValue(body,
						McpSchema.JSONRPCResponse.class);
				jsonRpcError = jsonRpcResponse.error();
				toPropagate = new McpError(jsonRpcError);
			}
			catch (IOException ex) {
				toPropagate = new RuntimeException("Sending request failed", e);
				logger.debug("Received content together with {} HTTP code response: {}", response.statusCode(), body);
			}

			// Some implementations can return 400 when presented with a
			// session id that it doesn't know about, so we will
			// invalidate the session
			// https://github.com/modelcontextprotocol/typescript-sdk/issues/389
			if (responseException.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
				return Mono.error(new McpTransportSessionNotFoundException(sessionRepresentation, toPropagate));
			}
			return Mono.error(toPropagate);
		}).flux();
	}

	private Flux<McpSchema.JSONRPCMessage> eventStream(McpTransportStream<Disposable> stream, ClientResponse response) {
		McpTransportStream<Disposable> sessionStream = stream != null ? stream
				: new DefaultMcpTransportStream<>(this.resumableStreams, this::reconnect);
		logger.debug("Connected stream {}", sessionStream.streamId());

		var idWithMessages = response.bodyToFlux(PARAMETERIZED_TYPE_REF).map(this::parse);
		return Flux.from(sessionStream.consumeSseStream(idWithMessages));
	}

	private static boolean isNotFound(ClientResponse response) {
		return response.statusCode().isSameCodeAs(HttpStatus.NOT_FOUND);
	}

	private static boolean isNotAllowed(ClientResponse response) {
		return response.statusCode().isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED);
	}

	private static boolean isEventStream(ClientResponse response) {
		return response.statusCode().is2xxSuccessful() && response.headers().contentType().isPresent()
				&& response.headers().contentType().get().isCompatibleWith(MediaType.TEXT_EVENT_STREAM);
	}

	private static String sessionIdOrPlaceholder(McpTransportSession<?> transportSession) {
		return transportSession.sessionId().orElse("[missing_session_id]");
	}

	private Flux<McpSchema.JSONRPCMessage> responseFlux(ClientResponse response) {
		return response.bodyToMono(String.class).<Iterable<McpSchema.JSONRPCMessage>>handle((responseMessage, s) -> {
			try {
				McpSchema.JSONRPCMessage jsonRpcResponse = McpSchema.deserializeJsonRpcMessage(objectMapper,
						responseMessage);
				s.next(List.of(jsonRpcResponse));
			}
			catch (IOException e) {
				s.error(e);
			}
		}).flatMapIterable(Function.identity());
	}

	private Flux<McpSchema.JSONRPCMessage> newEventStream(ClientResponse response, String sessionRepresentation) {
		McpTransportStream<Disposable> sessionStream = new DefaultMcpTransportStream<>(this.resumableStreams,
				this::reconnect);
		logger.trace("Sent POST and opened a stream ({}) for session {}", sessionStream.streamId(),
				sessionRepresentation);
		return eventStream(sessionStream, response);
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	private Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>> parse(ServerSentEvent<String> event) {
		if (MESSAGE_EVENT_TYPE.equals(event.event())) {
			try {
				// We don't support batching ATM and probably won't since the next version
				// considers removing it.
				McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, event.data());
				return Tuples.of(Optional.ofNullable(event.id()), List.of(message));
			}
			catch (IOException ioException) {
				throw new McpError("Error parsing JSON-RPC message: " + event.data());
			}
		}
		else {
			throw new McpError("Received unrecognized SSE event type: " + event.event());
		}
	}

	/**
	 * Builder for {@link WebClientStreamableHttpTransport}.
	 */
	public static class Builder {

		private ObjectMapper objectMapper;

		private WebClient.Builder webClientBuilder;

		private String endpoint = DEFAULT_ENDPOINT;

		private boolean resumableStreams = true;

		private boolean openConnectionOnStartup = false;

		private Builder(WebClient.Builder webClientBuilder) {
			Assert.notNull(webClientBuilder, "WebClient.Builder must not be null");
			this.webClientBuilder = webClientBuilder;
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

		/**
		 * Configure the {@link WebClient.Builder} to construct the {@link WebClient}.
		 * @param webClientBuilder instance to use
		 * @return the builder instance
		 */
		public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
			Assert.notNull(webClientBuilder, "WebClient.Builder must not be null");
			this.webClientBuilder = webClientBuilder;
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
		 * Construct a fresh instance of {@link WebClientStreamableHttpTransport} using
		 * the current builder configuration.
		 * @return a new instance of {@link WebClientStreamableHttpTransport}
		 */
		public WebClientStreamableHttpTransport build() {
			ObjectMapper objectMapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper();

			return new WebClientStreamableHttpTransport(objectMapper, this.webClientBuilder, endpoint, resumableStreams,
					openConnectionOnStartup);
		}

	}

}
