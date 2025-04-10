/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

/**
 * The WebSocket (WS) implementation of the
 * {@link io.modelcontextprotocol.spec.McpTransport} that follows the MCP HTTP with WS
 * transport specification, using Java's HttpClient.
 *
 * @author Aliaksei Darafeyeu
 */
public class WebSocketClientTransport implements McpClientTransport {

	private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketClientTransport.class);

	private final HttpClient httpClient;

	private final ObjectMapper objectMapper;

	private final URI uri;

	private final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();

	private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.DISCONNECTED);

	private final Sinks.Many<Throwable> errorSink = Sinks.many().multicast().onBackpressureBuffer();

	/**
	 * The constructor for the WebSocketClientTransport.
	 * @param uri the URI to connect to
	 * @param clientBuilder the HttpClient builder
	 * @param objectMapper the ObjectMapper for JSON serialization/deserialization
	 */
	WebSocketClientTransport(final URI uri, final HttpClient.Builder clientBuilder, final ObjectMapper objectMapper) {
		this.uri = uri;
		this.httpClient = clientBuilder.build();
		this.objectMapper = objectMapper;
	}

	/**
	 * Creates a new WebSocketClientTransport instance with the specified URI.
	 * @param uri the URI to connect to
	 * @return a new Builder instance
	 */
	public static Builder builder(final URI uri) {
		return new Builder().uri(uri);
	}

	/**
	 * The state of the Transport connection.
	 */
	public enum TransportState {

		DISCONNECTED, CONNECTING, CONNECTED, CLOSED

	}

	/**
	 * A builder for creating instances of WebSocketClientTransport.
	 */
	public static class Builder {

		private URI uri;

		private final HttpClient.Builder clientBuilder = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10));

		private ObjectMapper objectMapper = new ObjectMapper();

		public Builder uri(final URI uri) {
			this.uri = uri;
			return this;
		}

		public Builder customizeClient(final Consumer<HttpClient.Builder> clientCustomizer) {
			Assert.notNull(clientCustomizer, "clientCustomizer must not be null");
			clientCustomizer.accept(clientBuilder);
			return this;
		}

		public Builder objectMapper(final ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
			return this;
		}

		public WebSocketClientTransport build() {
			return new WebSocketClientTransport(uri, clientBuilder, objectMapper);
		}

	}

	public Mono<Void> connect(final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		if (!state.compareAndSet(TransportState.DISCONNECTED, TransportState.CONNECTING)) {
			return Mono.error(new IllegalStateException("WebSocket is already connecting or connected"));
		}

		return Mono.fromFuture(httpClient.newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {
			private final StringBuilder messageBuffer = new StringBuilder();

			@Override
			public void onOpen(WebSocket webSocket) {
				webSocketRef.set(webSocket);
				state.set(TransportState.CONNECTED);
			}

			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				messageBuffer.append(data);
				if (last) {
					final String fullMessage = messageBuffer.toString();
					messageBuffer.setLength(0);
					try {
						final McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(objectMapper,
								fullMessage);
						handler.apply(Mono.just(msg)).subscribe();
					}
					catch (Exception e) {
						errorSink.tryEmitNext(e);
						LOGGER.error("Error processing WS event", e);
					}
				}

				webSocket.request(1);
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				errorSink.tryEmitNext(error);
				state.set(TransportState.CLOSED);
				LOGGER.error("WS connection error", error);
			}

			@Override
			public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				state.set(TransportState.CLOSED);
				return CompletableFuture.completedFuture(null);
			}

		})).then();
	}

	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {

		return Mono.defer(() -> {
			WebSocket ws = webSocketRef.get();
			if (ws == null && state.get() == TransportState.CONNECTING) {
				return Mono.error(new IllegalStateException("WebSocket is connecting."));
			}

			if (ws == null || state.get() == TransportState.DISCONNECTED || state.get() == TransportState.CLOSED) {
				return Mono.error(new IllegalStateException("WebSocket is closed."));
			}

			try {
				String json = objectMapper.writeValueAsString(message);
				return Mono.fromFuture(ws.sendText(json, true)).then();
			}
			catch (Exception e) {
				return Mono.error(e);
			}
		}).retryWhen(Retry.backoff(3, Duration.ofSeconds(3)).filter(err -> {
			if (err instanceof IllegalStateException) {
				return err.getMessage().equals("WebSocket is connecting.");
			}
			return true;
		})).onErrorResume(e -> {
			LOGGER.error("Failed to send message after retries", e);
			errorSink.tryEmitNext(e);
			return Mono.error(new IllegalStateException("WebSocket send failed after retries", e));
		});

	}

	@Override
	public Mono<Void> closeGracefully() {
		WebSocket webSocket = webSocketRef.getAndSet(null);
		if (webSocket != null && state.get() == TransportState.CONNECTED) {
			state.set(TransportState.CLOSED);
			return Mono.fromFuture(webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing")).then();
		}
		return Mono.empty();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return objectMapper.convertValue(data, typeRef);
	}

	public TransportState getState() {
		return state.get();
	}

}
