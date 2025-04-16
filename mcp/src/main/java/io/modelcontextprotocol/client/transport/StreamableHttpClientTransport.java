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
import java.util.ArrayList;
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

	private static final String DEFAULT_MCP_ENDPOINT = "/mcp";

	private static final String MCP_SESSION_ID = "Mcp-Session-Id";

	private static final String LAST_EVENT_ID = "Last-Event-ID";

	private static final String ACCEPT = "Accept";

	private static final String CONTENT_TYPE = "Content-Type";

	private static final String APPLICATION_JSON = "application/json";

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private static final String APPLICATION_JSON_SEQ = "application/json-seq";

	private static final String DEFAULT_ACCEPT_VALUES = "%s, %s".formatted(APPLICATION_JSON, TEXT_EVENT_STREAM);

	private final HttpClientSseClientTransport sseClientTransport;

	private final HttpClient httpClient;

	private final HttpRequest.Builder requestBuilder;

	private final ObjectMapper objectMapper;

	private final URI uri;

	private final AtomicReference<String> lastEventId = new AtomicReference<>();

	private final AtomicReference<String> mcpSessionId = new AtomicReference<>();

	private final AtomicBoolean fallbackToSse = new AtomicBoolean(false);

	StreamableHttpClientTransport(final HttpClient httpClient, final HttpRequest.Builder requestBuilder,
			final ObjectMapper objectMapper, final String baseUri, final String endpoint,
			final HttpClientSseClientTransport sseClientTransport) {
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;
		this.objectMapper = objectMapper;
		this.uri = URI.create(baseUri + endpoint);
		this.sseClientTransport = sseClientTransport;
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
	 * A builder for creating instances of WebSocketClientTransport.
	 */
	public static class Builder {

		private final HttpClient.Builder clientBuilder = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10));

		private final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();

		private ObjectMapper objectMapper = new ObjectMapper();

		private String baseUri;

		private String endpoint = DEFAULT_MCP_ENDPOINT;

		private Consumer<HttpClient.Builder> clientCustomizer;

		private Consumer<HttpRequest.Builder> requestCustomizer;

		public Builder withCustomizeClient(final Consumer<HttpClient.Builder> clientCustomizer) {
			Assert.notNull(clientCustomizer, "clientCustomizer must not be null");
			clientCustomizer.accept(clientBuilder);
			this.clientCustomizer = clientCustomizer;
			return this;
		}

		public Builder withCustomizeRequest(final Consumer<HttpRequest.Builder> requestCustomizer) {
			Assert.notNull(requestCustomizer, "requestCustomizer must not be null");
			requestCustomizer.accept(requestBuilder);
			this.requestCustomizer = requestCustomizer;
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
			final HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(baseUri)
				.objectMapper(objectMapper);
			if (clientCustomizer != null) {
				builder.customizeClient(clientCustomizer);
			}

			if (requestCustomizer != null) {
				builder.customizeRequest(requestCustomizer);
			}

			if (!endpoint.equals(DEFAULT_MCP_ENDPOINT)) {
				builder.sseEndpoint(endpoint);
			}

			return new StreamableHttpClientTransport(clientBuilder.build(), requestBuilder, objectMapper, baseUri,
					endpoint, builder.build());
		}

	}

	@Override
	public Mono<Void> connect(final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		if (fallbackToSse.get()) {
			return sseClientTransport.connect(handler);
		}

		return Mono.defer(() -> Mono.fromFuture(() -> {
			final HttpRequest.Builder request = requestBuilder.copy().GET().header(ACCEPT, TEXT_EVENT_STREAM).uri(uri);
			final String lastId = lastEventId.get();
			if (lastId != null) {
				request.header(LAST_EVENT_ID, lastId);
			}
			if (mcpSessionId.get() != null) {
				request.header(MCP_SESSION_ID, mcpSessionId.get());
			}

			return httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
		}).flatMap(response -> {
			// must like server terminate session and the client need to start a
			// new session by sending a new `InitializeRequest` without a session
			// ID attached.
			if (mcpSessionId.get() != null && response.statusCode() == 404) {
				mcpSessionId.set(null);
			}

			if (response.statusCode() == 405 || response.statusCode() == 404) {
				LOGGER.warn("Operation not allowed, falling back to SSE");
				fallbackToSse.set(true);
				return sseClientTransport.connect(handler);
			}
			return handleStreamingResponse(response, handler);
		})
			.retryWhen(Retry.backoff(3, Duration.ofSeconds(3)).filter(err -> err instanceof IllegalStateException))
			.onErrorResume(e -> {
				LOGGER.error("Streamable transport connection error", e);
				return Mono.error(e);
			})).doOnTerminate(this::closeGracefully);
	}

	@Override
	public Mono<Void> sendMessage(final McpSchema.JSONRPCMessage message) {
		return sendMessage(message, msg -> msg);
	}

	public Mono<Void> sendMessage(final McpSchema.JSONRPCMessage message,
			final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		if (fallbackToSse.get()) {
			return fallbackToSse(message);
		}

		return serializeJson(message).flatMap(json -> {
			final HttpRequest.Builder request = requestBuilder.copy()
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.header(ACCEPT, DEFAULT_ACCEPT_VALUES)
				.header(CONTENT_TYPE, APPLICATION_JSON)
				.uri(uri);
			if (mcpSessionId.get() != null) {
				request.header(MCP_SESSION_ID, mcpSessionId.get());
			}

			return Mono.fromFuture(httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream()))
				.flatMap(response -> {

					// server may assign a session ID at initialization time, if yes we
					// have to use it for any subsequent requests
					if (message instanceof McpSchema.JSONRPCRequest
							&& ((McpSchema.JSONRPCRequest) message).method().equals(McpSchema.METHOD_INITIALIZE)) {
						response.headers()
							.firstValue(MCP_SESSION_ID)
							.map(String::trim)
							.ifPresent(this.mcpSessionId::set);
					}

					// If the response is 202 Accepted, there's no body to process
					if (response.statusCode() == 202) {
						return Mono.empty();
					}

					// must like server terminate session and the client need to start a
					// new session by sending a new `InitializeRequest` without a session
					// ID attached.
					if (mcpSessionId.get() != null && response.statusCode() == 404) {
						mcpSessionId.set(null);
					}

					if (response.statusCode() == 405 || response.statusCode() == 404) {
						LOGGER.warn("Operation not allowed, falling back to SSE");
						fallbackToSse.set(true);
						return fallbackToSse(message);
					}

					if (response.statusCode() >= 400) {
						return Mono
							.error(new IllegalArgumentException("Unexpected status code: " + response.statusCode()));
					}

					return handleStreamingResponse(response, handler);
				});
		}).onErrorResume(e -> {
			LOGGER.error("Streamable transport sendMessages error", e);
			return Mono.error(e);
		});

	}

	private Mono<Void> fallbackToSse(final McpSchema.JSONRPCMessage msg) {
		if (msg instanceof McpSchema.JSONRPCBatchRequest batch) {
			return Flux.fromIterable(batch.items()).flatMap(sseClientTransport::sendMessage).then();
		}

		if (msg instanceof McpSchema.JSONRPCBatchResponse batch) {
			return Flux.fromIterable(batch.items()).flatMap(sseClientTransport::sendMessage).then();
		}

		return sseClientTransport.sendMessage(msg);
	}

	private Mono<String> serializeJson(final McpSchema.JSONRPCMessage msg) {
		try {
			return Mono.just(objectMapper.writeValueAsString(msg));
		}
		catch (IOException e) {
			LOGGER.error("Error serializing JSON-RPC message", e);
			return Mono.error(e);
		}
	}

	private Mono<Void> handleStreamingResponse(final HttpResponse<InputStream> response,
			final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		final String contentType = response.headers().firstValue(CONTENT_TYPE).orElse("");
		if (contentType.contains(APPLICATION_JSON_SEQ)) {
			return handleJsonStream(response, handler);
		}
		else if (contentType.contains(TEXT_EVENT_STREAM)) {
			return handleSseStream(response, handler);
		}
		else if (contentType.contains(APPLICATION_JSON)) {
			return handleSingleJson(response, handler);
		}
		return Mono.error(new UnsupportedOperationException("Unsupported Content-Type: " + contentType));
	}

	private Mono<Void> handleSingleJson(final HttpResponse<InputStream> response,
			final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		return Mono.fromCallable(() -> {
			try {
				final McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(objectMapper,
						new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
				return handler.apply(Mono.just(msg));
			}
			catch (IOException e) {
				LOGGER.error("Error processing JSON response", e);
				return Mono.error(e);
			}
		}).flatMap(Function.identity()).then();
	}

	private Mono<Void> handleJsonStream(final HttpResponse<InputStream> response,
			final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		return Flux.fromStream(new BufferedReader(new InputStreamReader(response.body())).lines()).flatMap(jsonLine -> {
			try {
				final McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, jsonLine);
				return handler.apply(Mono.just(message));
			}
			catch (IOException e) {
				LOGGER.error("Error processing JSON line", e);
				return Mono.error(e);
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
						data += line.substring(6) + "\n";
					else if (line.startsWith("id: "))
						id = line.substring(4).trim();
				}

				if (data.endsWith("\n")) {
					data = data.substring(0, data.length() - 1);
				}

				return new FlowSseClient.SseEvent(id, event, data);
			})
			.filter(sseEvent -> "message".equals(sseEvent.type()))
			.concatMap(sseEvent -> {
				String rawData = sseEvent.data().trim();
				try {
					JsonNode node = objectMapper.readTree(rawData);
					List<McpSchema.JSONRPCMessage> messages = new ArrayList<>();
					if (node.isArray()) {
						for (JsonNode item : node) {
							messages.add(McpSchema.deserializeJsonRpcMessage(objectMapper, item.toString()));
						}
					}
					else if (node.isObject()) {
						messages.add(McpSchema.deserializeJsonRpcMessage(objectMapper, node.toString()));
					}
					else {
						String warning = "Unexpected JSON in SSE data: " + rawData;
						LOGGER.warn(warning);
						return Mono.error(new IllegalArgumentException(warning));
					}

					return Flux.fromIterable(messages)
						.concatMap(msg -> handler.apply(Mono.just(msg)))
						.then(Mono.fromRunnable(() -> {
							if (!sseEvent.id().isEmpty()) {
								lastEventId.set(sseEvent.id());
							}
						}));
				}
				catch (IOException e) {
					LOGGER.error("Error parsing SSE JSON: {}", rawData, e);
					return Mono.error(e);
				}
			})
			.then();
	}

	@Override
	public Mono<Void> closeGracefully() {
		mcpSessionId.set(null);
		lastEventId.set(null);
		if (fallbackToSse.get()) {
			return sseClientTransport.closeGracefully();
		}
		return Mono.empty();
	}

	@Override
	public <T> T unmarshalFrom(final Object data, final TypeReference<T> typeRef) {
		return objectMapper.convertValue(data, typeRef);
	}

}
