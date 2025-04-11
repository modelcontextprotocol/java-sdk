/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * A transport implementation for the Model Context Protocol (MCP) using JSON streaming.
 *
 * @author Aliaksei Darafeyeu
 */
public class StreamableHttpClientTransport implements McpClientTransport {

	private static final Logger LOGGER = LoggerFactory.getLogger(StreamableHttpClientTransport.class);

	private final HttpClientSseClientTransport sseClientTransport;

	private final HttpClient httpClient;

	private final HttpRequest.Builder requestBuilder;

	private final ObjectMapper objectMapper;

	private final URI uri;

	private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.DISCONNECTED);

	private final AtomicReference<String> lastEventId = new AtomicReference<>();

	private final AtomicBoolean fallbackToSse = new AtomicBoolean(false);

	StreamableHttpClientTransport(final HttpClient httpClient, final HttpRequest.Builder requestBuilder,
			final ObjectMapper objectMapper, final String baseUri, final String endpoint) {
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;
		this.objectMapper = objectMapper;
		this.uri = URI.create(baseUri + endpoint);
		this.sseClientTransport = HttpClientSseClientTransport.builder(baseUri).build();
	}

	/**
	 * Creates a new StreamableHttpClientTransport instance with the specified URI.
	 * @param uri the URI to connect to
	 * @return a new Builder instance
	 */
	public static Builder builder(final String uri) {
		return new Builder().withBaseUri(uri);
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

		private final HttpClient.Builder clientBuilder = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10));

		private final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
			.header("Accept", "application/json, text/event-stream");

		private ObjectMapper objectMapper;

		private String baseUri;

		private String endpoint = "/mcp";

		public Builder withCustomizeClient(final Consumer<HttpClient.Builder> clientCustomizer) {
			Assert.notNull(clientCustomizer, "clientCustomizer must not be null");
			clientCustomizer.accept(clientBuilder);
			return this;
		}

		public Builder withCustomizeRequest(final Consumer<HttpRequest.Builder> requestCustomizer) {
			Assert.notNull(requestCustomizer, "requestCustomizer must not be null");
			requestCustomizer.accept(requestBuilder);
			return this;
		}

		public Builder withObjectMapper(final ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "objectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		public Builder withBaseUri(final String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
			return this;
		}

		public Builder withEndpoint(final String endpoint) {
			Assert.hasText(endpoint, "endpoint must not be empty");
			this.endpoint = endpoint;
			return this;
		}

		public StreamableHttpClientTransport build() {
			return new StreamableHttpClientTransport(clientBuilder.build(), requestBuilder, objectMapper, baseUri,
					endpoint);
		}

	}

	@Override
	public Mono<Void> connect(final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		if (fallbackToSse.get()) {
			return sseClientTransport.connect(handler);
		}

		if (!state.compareAndSet(TransportState.DISCONNECTED, TransportState.CONNECTING)) {
			return Mono.error(new IllegalStateException("Already connected or connecting"));
		}

		return sendInitialHandshake().then(Mono.defer(() -> Mono
			.fromFuture(() -> httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream()))
			.flatMap(response -> handleStreamingResponse(handler, response))
			.retryWhen(Retry.backoff(3, Duration.ofSeconds(3)).filter(err -> err instanceof IllegalStateException))
			.doOnSuccess(v -> state.set(TransportState.CONNECTED))
			.doOnTerminate(() -> state.set(TransportState.CLOSED))
			.onErrorResume(e -> {
				state.set(TransportState.DISCONNECTED);
				LOGGER.error("Failed to connect", e);
				return Mono.error(e);
			}))).onErrorResume(e -> {
				if (e instanceof UnsupportedOperationException) {
					LOGGER.warn("Streamable transport failed, falling back to SSE.", e);
					fallbackToSse.set(true);
					return sseClientTransport.connect(handler);
				}
				return Mono.error(e);
			});

	}

	@Override
	public Mono<Void> sendMessage(final McpSchema.JSONRPCMessage message) {
		if (state.get() == TransportState.CLOSED) {
			return Mono.empty();
		}

		if (fallbackToSse.get()) {
			return sseClientTransport.sendMessage(message);
		}

		if (state.get() == TransportState.DISCONNECTED) {
			state.set(TransportState.CONNECTING);

			return sendInitialHandshake().doOnSuccess(v -> state.set(TransportState.CONNECTED)).onErrorResume(e -> {
				if (e instanceof UnsupportedOperationException) {
					LOGGER.warn("Streamable transport failed, falling back to SSE.", e);
					fallbackToSse.set(true);
					return Mono.empty();
				}
				return Mono.error(e);
			}).then(sendMessage(message));
		}

		try {
			String json = objectMapper.writeValueAsString(message);
			return sentPost(json);
		}
		catch (Exception e) {
			return Mono.error(e);
		}
	}

	/**
	 * Sends a list of messages to the server.
	 * @param messages the list of messages to send
	 * @return a Mono that completes when all messages have been sent
	 */
	public Mono<Void> sendMessages(final List<McpSchema.JSONRPCMessage> messages) {
		if (state.get() == TransportState.CLOSED) {
			return Mono.empty();
		}

		if (fallbackToSse.get()) {
			return Flux.fromIterable(messages).flatMap(this::sendMessage).then();
		}

		if (state.get() == TransportState.DISCONNECTED) {
			state.set(TransportState.CONNECTING);

			return sendInitialHandshake().doOnSuccess(v -> state.set(TransportState.CONNECTED)).onErrorResume(e -> {
				if (e instanceof UnsupportedOperationException) {
					LOGGER.warn("Streamable transport failed, falling back to SSE.", e);
					fallbackToSse.set(true);
					return Mono.empty();
				}
				return Mono.error(e);
			}).then(sendMessages(messages));
		}

		try {
			String json = objectMapper.writeValueAsString(messages);
			return sentPost(json);
		}
		catch (Exception e) {
			return Mono.error(e);
		}
	}

	private Mono<Void> sendInitialHandshake() {
		try {
			String json = objectMapper.writeValueAsString(new McpSchema.InitializeRequest("2025-03-26", null, null));
			HttpRequest req = requestBuilder.copy().uri(uri).POST(HttpRequest.BodyPublishers.ofString(json)).build();
			return Mono.fromFuture(httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding()))
				.flatMap(response -> {
					int code = response.statusCode();
					if (code == 200) {
						return Mono.empty();
					}
					else if (code >= 400 && code < 500) {
						return Mono.error(new UnsupportedOperationException("Client error: " + code));
					}
					else {
						return Mono.error(new IOException("Unexpected status code: " + code));
					}
				})
				.then();
		}
		catch (IOException e) {
			return Mono.error(e);
		}
	}

	private Mono<Void> sentPost(String json) {
		HttpRequest request = requestBuilder.copy().POST(HttpRequest.BodyPublishers.ofString(json)).build();
		return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()))
			.flatMap(response -> handleStreamingResponse(msg -> msg, response))
			.then();
	}

	private Mono<Void> handleStreamingResponse(
			final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler,
			final HttpResponse<InputStream> response) {
		String contentType = response.headers().firstValue("Content-Type").orElse("");
		if (contentType.contains("application/json-seq")) {
			return handleJsonStream(response, handler);
		}
		else if (contentType.contains("text/event-stream")) {
			return handleSseStream(response, handler);
		}
		else if (contentType.contains("application/json")) {
			return handleSingleJson(response, handler);
		}
		else {
			return Mono.error(new UnsupportedOperationException("Unsupported Content-Type: " + contentType));
		}
	}

	private Mono<Void> handleSingleJson(final HttpResponse<InputStream> response,
			final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		return Mono.fromCallable(() -> {
			McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(objectMapper,
					new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
			return handler.apply(Mono.just(msg));
		}).flatMap(Function.identity()).then();
	}

	private Mono<Void> handleJsonStream(final HttpResponse<InputStream> response,
			final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		return Flux.fromStream(new BufferedReader(new InputStreamReader(response.body())).lines()).flatMap(jsonLine -> {
			try {
				McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, jsonLine);
				return handler.apply(Mono.just(message));
			}
			catch (IOException e) {
				LOGGER.error("Error processing JSON line", e);
				return Mono.empty();
			}
		}).then();
	}

	private Mono<Void> handleSseStream(final HttpResponse<InputStream> response,
			final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		return Flux.fromStream(new BufferedReader(new InputStreamReader(response.body())).lines())
			.map(String::trim)
			.bufferUntil(String::isEmpty)
			.map(eventLines -> {
				String event = "";
				String data = "";
				String id = "";

				for (String line : eventLines) {
					if (line.startsWith("event: "))
						event = line.substring(7).trim();
					else if (line.startsWith("data: "))
						data += line.substring(6).trim() + "\n";
					else if (line.startsWith("id: "))
						id = line.substring(4).trim();
				}

				if (data.endsWith("\n")) {
					data = data.substring(0, data.length() - 1);
				}

				return new FlowSseClient.SseEvent(event, data, id);
			})
			.filter(sseEvent -> "message".equals(sseEvent.type()))
			.doOnNext(sseEvent -> {
				lastEventId.set(sseEvent.id());
				try {
					String rawData = sseEvent.data().trim();
					JsonNode node = objectMapper.readTree(rawData);

					if (node.isArray()) {
						for (JsonNode item : node) {
							String rawMessage = objectMapper.writeValueAsString(item);
							McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(objectMapper,
									rawMessage);
							handler.apply(Mono.just(msg)).subscribe();
						}
					}
					else if (node.isObject()) {
						String rawMessage = objectMapper.writeValueAsString(node);
						McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(objectMapper, rawMessage);
						handler.apply(Mono.just(msg)).subscribe();
					}
					else {
						LOGGER.warn("Unexpected JSON in SSE data: {}", rawData);
					}
				}
				catch (IOException e) {
					LOGGER.error("Error processing SSE event: {}", sseEvent.data(), e);
				}
			})
			.then();
	}

	@Override
	public Mono<Void> closeGracefully() {
		state.set(TransportState.CLOSED);
		return Mono.empty();
	}

	@Override
	public <T> T unmarshalFrom(final Object data, final TypeReference<T> typeRef) {
		return objectMapper.convertValue(data, typeRef);
	}

	public TransportState getState() {
		return state.get();
	}

}
