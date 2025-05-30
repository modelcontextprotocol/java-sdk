package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSessionNotFoundException;
import io.modelcontextprotocol.spec.McpTransportSession;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public class WebClientStreamableHttpTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebClientStreamableHttpTransport.class);

	/**
	 * Event type for JSON-RPC messages received through the SSE connection. The server
	 * sends messages with this event type to transmit JSON-RPC protocol data.
	 */
	private static final String MESSAGE_EVENT_TYPE = "message";

	private final ObjectMapper objectMapper;

	private final WebClient webClient;

	private final String endpoint;

	private final boolean openConnectionOnStartup;

	private final boolean resumableStreams;

	private final AtomicReference<McpTransportSession> activeSession = new AtomicReference<>();

	private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handler = new AtomicReference<>();

	private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

	public WebClientStreamableHttpTransport(ObjectMapper objectMapper, WebClient.Builder webClientBuilder,
			String endpoint, boolean resumableStreams, boolean openConnectionOnStartup) {
		this.objectMapper = objectMapper;
		this.webClient = webClientBuilder.build();
		this.endpoint = endpoint;
		this.resumableStreams = resumableStreams;
		this.openConnectionOnStartup = openConnectionOnStartup;
		this.activeSession.set(new McpTransportSession());
	}

	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		return Mono.deferContextual(ctx -> {
			this.handler.set(handler);
			if (openConnectionOnStartup) {
				logger.debug("Eagerly opening connection on startup");
				this.reconnect(null, ctx);
			}
			return Mono.empty();
		});
	}

	@Override
	public void registerExceptionHandler(Consumer<Throwable> handler) {
		logger.debug("Exception handler registered");
		this.exceptionHandler.set(handler);
	}

	private void handleException(Throwable t) {
		logger.debug("Handling exception for session {}", activeSession.get().sessionId(), t);
		if (t instanceof McpSessionNotFoundException) {
			McpTransportSession invalidSession = this.activeSession.getAndSet(new McpTransportSession());
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
			McpTransportSession currentSession = this.activeSession.get();
			if (currentSession != null) {
				return currentSession.closeGracefully();
			}
			return Mono.empty();
		});
	}

	// FIXME: Avoid passing the ContextView - add hook allowing the Reactor Context to be
	// attached to the chain?
	private void reconnect(McpStream stream, ContextView ctx) {
		if (stream != null) {
			logger.debug("Reconnecting stream {} with lastId {}", stream.streamId(), stream.lastId());
		}
		else {
			logger.debug("Reconnecting with no prior stream");
		}
		// Here we attempt to initialize the client.
		// In case the server supports SSE, we will establish a long-running session
		// here and
		// listen for messages.
		// If it doesn't, nothing actually happens here, that's just the way it is...
		final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
		final McpTransportSession transportSession = this.activeSession.get();
		Disposable connection = webClient.get()
			.uri(this.endpoint)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.headers(httpHeaders -> {
				if (transportSession.sessionId() != null) {
					httpHeaders.add("mcp-session-id", transportSession.sessionId());
				}
				if (stream != null && stream.lastId() != null) {
					httpHeaders.add("last-event-id", stream.lastId());
				}
			})
			.exchangeToFlux(response -> {
				// Per spec, we are not checking whether it's 2xx, but only if the
				// Accept header is proper.
				if (response.statusCode().is2xxSuccessful() && response.headers().contentType().isPresent()
						&& response.headers().contentType().get().isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {

					McpStream sessionStream = stream != null ? stream : new McpStream(this.resumableStreams);
					logger.debug("Established stream {}", sessionStream.streamId());

					Flux<Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>>> idWithMessages = response
						.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
						})
						.map(this::parse);

					return sessionStream.consumeSseStream(idWithMessages);
				}
				else if (response.statusCode().isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
					logger.debug("The server does not support SSE streams, using request-response mode.");
					return Flux.empty();
				}
				else if (response.statusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
					logger.warn("Session {} was not found on the MCP server", transportSession.sessionId());

					McpSessionNotFoundException notFoundException = new McpSessionNotFoundException(
							transportSession.sessionId());
					// inform the stream/connection subscriber
					return Flux.error(notFoundException);
				}
				else {
					return response.<McpSchema.JSONRPCMessage>createError().doOnError(e -> {
						logger.info("Opening an SSE stream failed. This can be safely ignored.", e);
					}).flux();
				}
			})
			.onErrorResume(t -> {
				this.handleException(t);
				return Flux.empty();
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
			final McpTransportSession transportSession = this.activeSession.get();

			Disposable connection = webClient.post()
				.uri(this.endpoint)
				.accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
				.headers(httpHeaders -> {
					if (transportSession.sessionId() != null) {
						httpHeaders.add("mcp-session-id", transportSession.sessionId());
					}
				})
				.bodyValue(message)
				.exchangeToFlux(response -> {
					if (transportSession.markInitialized()) {
						if (!response.headers().header("mcp-session-id").isEmpty()) {
							String sessionId = response.headers().asHttpHeaders().getFirst("mcp-session-id");
							logger.debug("Established session with id {}", sessionId);
							transportSession.setSessionId(sessionId);
							// Once we have a session, we try to open an async stream for
							// the server to send notifications and requests out-of-band.
							reconnect(null, sink.contextView());
						}
					}

					// The spec mentions only ACCEPTED, but the existing SDKs can return
					// 200 OK for notifications
					// if (!response.statusCode().isSameCodeAs(HttpStatus.ACCEPTED)) {
					if (response.statusCode().is2xxSuccessful()) {
						// Existing SDKs consume notifications with no response body nor
						// content type
						if (response.headers().contentType().isEmpty()) {
							logger.trace("Message was successfuly sent via POST for session {}",
									transportSession.sessionId());
							// signal the caller that the message was successfully
							// delivered
							sink.success();
							// communicate to downstream there is no streamed data coming
							return Flux.empty();
						}

						MediaType contentType = response.headers().contentType().get();

						if (contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
							// communicate to caller that the message was delivered
							sink.success();

							// starting a stream
							McpStream sessionStream = new McpStream(this.resumableStreams);

							logger.trace("Sent POST and opened a stream ({}) for session {}", sessionStream.streamId(),
									transportSession.sessionId());

							Flux<Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>>> idWithMessages = response
								.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
								})
								.map(this::parse);

							return sessionStream.consumeSseStream(idWithMessages);
						}
						else if (contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
							logger.trace("Received response to POST for session {}", transportSession.sessionId());

							// communicate to caller the message was delivered
							sink.success();

							// provide the response body as a stream of a single response
							// to consume
							return response.bodyToMono(
									String.class).<Iterable<McpSchema.JSONRPCMessage>>handle((responseMessage, s) -> {
										try {
											McpSchema.JSONRPCMessage jsonRpcResponse = McpSchema
												.deserializeJsonRpcMessage(objectMapper, responseMessage);
											s.next(List.of(jsonRpcResponse));
										}
										catch (IOException e) {
											s.error(e);
										}
									})
								.flatMapIterable(Function.identity());
						}
						else {
							logger.warn("Unknown media type {} returned for POST in session {}", contentType,
									transportSession.sessionId());
							return Flux.error(new RuntimeException("Unknown media type returned: " + contentType));
						}
					}
					else {
						if (response.statusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
							logger.warn("Session {} was not found on the MCP server", transportSession.sessionId());

							McpSessionNotFoundException notFoundException = new McpSessionNotFoundException(
									transportSession.sessionId());
							// inform the stream/connection subscriber
							return Flux.error(notFoundException);
						}
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
								logger.debug("Received content together with {} HTTP code response: {}",
										response.statusCode(), body);
							}

							// Some implementations can return 400 when presented with a
							// session id that it doesn't know about, so we will
							// invalidate the session
							// https://github.com/modelcontextprotocol/typescript-sdk/issues/389
							if (responseException.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
								return Mono.error(new McpSessionNotFoundException(this.activeSession.get().sessionId(),
										toPropagate));
							}
							return Mono.empty();
						}).flux();
					}
				})
				.map(Mono::just)
				.flatMap(this.handler.get())
				.onErrorResume(t -> {
					// handle the error first
					this.handleException(t);

					// inform the caller of sendMessage
					sink.error(t);
					return Flux.empty();
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

	private class McpStream {

		private static final AtomicLong counter = new AtomicLong();

		private final AtomicReference<String> lastId = new AtomicReference<>();

		// Used only for internal accounting
		private final long streamId;

		private final boolean resumable;

		McpStream(boolean resumable) {
			this.streamId = counter.getAndIncrement();
			this.resumable = resumable;
		}

		String lastId() {
			return this.lastId.get();
		}

		long streamId() {
			return this.streamId;
		}

		Flux<McpSchema.JSONRPCMessage> consumeSseStream(
				Publisher<Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>>> eventStream) {
			return Flux.deferContextual(ctx -> Flux.from(eventStream).doOnError(e -> {
				if (resumable && !(e instanceof McpSessionNotFoundException)) {
					reconnect(this, ctx);
				}
			})
				.doOnNext(idAndMessage -> idAndMessage.getT1().ifPresent(this.lastId::set))
				.flatMapIterable(Tuple2::getT2));
		}

	}

}
