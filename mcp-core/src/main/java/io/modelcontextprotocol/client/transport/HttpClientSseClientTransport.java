/*
 * Copyright 2024 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.ResponseEvent;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Server-Sent Events (SSE) implementation of the
 * {@link io.modelcontextprotocol.spec.McpTransport} that follows the MCP HTTP with SSE
 * transport specification, using Java's HttpClient.
 *
 * <p>
 * This transport implementation establishes a bidirectional communication channel between
 * client and server using SSE for server-to-client messages and HTTP POST requests for
 * client-to-server messages. The transport:
 * <ul>
 * <li>Establishes an SSE connection to receive server messages</li>
 * <li>Handles endpoint discovery through SSE events</li>
 * <li>Manages message serialization/deserialization using Jackson</li>
 * <li>Provides graceful connection termination</li>
 * </ul>
 *
 * <p>
 * The transport supports two types of SSE events:
 * <ul>
 * <li>'endpoint' - Contains the URL for sending client messages</li>
 * <li>'message' - Contains JSON-RPC message payload</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @see io.modelcontextprotocol.spec.McpTransport
 * @see io.modelcontextprotocol.spec.McpClientTransport
 */
public class HttpClientSseClientTransport implements McpClientTransport {

	private static final String MCP_PROTOCOL_VERSION = ProtocolVersions.MCP_2024_11_05;

	private static final String MCP_PROTOCOL_VERSION_HEADER_NAME = "MCP-Protocol-Version";

	private static final Logger logger = LoggerFactory.getLogger(HttpClientSseClientTransport.class);

	/** SSE event type for JSON-RPC messages */
	private static final String MESSAGE_EVENT_TYPE = "message";

	/** SSE event type for endpoint discovery */
	private static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/** Default SSE endpoint path */
	private static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/** Base URI for the MCP server */
	private final URI baseUri;

	/** SSE endpoint path */
	private final String sseEndpoint;

	/**
	 * HTTP client for sending messages to the server. Uses HTTP POST over the message
	 * endpoint
	 */
	private final HttpClient httpClient;

	/**
	 * Flag indicating whether this transport should close the HttpClient when closing
	 * gracefully. Set to true when the HttpClient is created internally by the builder,
	 * false when provided externally.
	 */
	private final boolean shouldCloseHttpClient;

	/** HTTP request builder for building requests to send messages to the server */
	private final HttpRequest.Builder requestBuilder;

	/** JSON mapper for message serialization/deserialization */
	protected McpJsonMapper jsonMapper;

	/** Flag indicating if the transport is in closing state */
	private volatile boolean isClosing = false;

	/** Holds the SSE subscription disposable */
	private final AtomicReference<Disposable> sseSubscription = new AtomicReference<>();

	/**
	 * Sink for managing the message endpoint URI provided by the server. Stores the most
	 * recent endpoint URI and makes it available for outbound message processing.
	 */
	protected final Sinks.One<String> messageEndpointSink = Sinks.one();

	/**
	 * Customizer to modify requests before they are executed.
	 */
	private final McpAsyncHttpClientRequestCustomizer httpRequestCustomizer;

	/**
	 * Creates a new transport instance with custom HTTP client builder, object mapper,
	 * and headers.
	 * @param httpClient the HTTP client to use
	 * @param requestBuilder the HTTP request builder to use
	 * @param baseUri the base URI of the MCP server
	 * @param sseEndpoint the SSE endpoint path
	 * @param jsonMapper the object mapper for JSON serialization/deserialization
	 * @param httpRequestCustomizer customizer for the requestBuilder before executing
	 * requests
	 * @throws IllegalArgumentException if objectMapper, clientBuilder, or headers is null
	 */
	HttpClientSseClientTransport(HttpClient httpClient, HttpRequest.Builder requestBuilder, String baseUri,
			String sseEndpoint, McpJsonMapper jsonMapper, McpAsyncHttpClientRequestCustomizer httpRequestCustomizer,
			boolean shouldCloseHttpClient) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.hasText(baseUri, "baseUri must not be empty");
		Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
		Assert.notNull(httpClient, "httpClient must not be null");
		Assert.notNull(requestBuilder, "requestBuilder must not be null");
		Assert.notNull(httpRequestCustomizer, "httpRequestCustomizer must not be null");
		this.baseUri = URI.create(baseUri);
		this.sseEndpoint = sseEndpoint;
		this.jsonMapper = jsonMapper;
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;
		this.httpRequestCustomizer = httpRequestCustomizer;
		this.shouldCloseHttpClient = shouldCloseHttpClient;
	}

	@Override
	public List<String> protocolVersions() {
		return List.of(ProtocolVersions.MCP_2024_11_05);
	}

	/**
	 * Creates a new builder for {@link HttpClientSseClientTransport}.
	 * @param baseUri the base URI of the MCP server
	 * @return a new builder instance
	 */
	public static Builder builder(String baseUri) {
		return new Builder().baseUri(baseUri);
	}

	/**
	 * Builder for {@link HttpClientSseClientTransport}.
	 */
	public static class Builder {

		private String baseUri;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		private HttpClient.Builder clientBuilder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);

		private HttpClient externalHttpClient;

		private McpJsonMapper jsonMapper;

		private HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();

		private McpAsyncHttpClientRequestCustomizer httpRequestCustomizer = McpAsyncHttpClientRequestCustomizer.NOOP;

		private Duration connectTimeout = Duration.ofSeconds(10);

		/**
		 * Creates a new builder instance.
		 */
		Builder() {
			// Default constructor
		}

		/**
		 * Creates a new builder with the specified base URI.
		 * @param baseUri the base URI of the MCP server
		 * @deprecated Use {@link HttpClientSseClientTransport#builder(String)} instead.
		 * This constructor is deprecated and will be removed or made {@code protected} or
		 * {@code private} in a future release.
		 */
		@Deprecated(forRemoval = true)
		public Builder(String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
		}

		/**
		 * Sets the base URI.
		 * @param baseUri the base URI
		 * @return this builder
		 */
		Builder baseUri(String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
			return this;
		}

		/**
		 * Sets the SSE endpoint path.
		 * @param sseEndpoint the SSE endpoint path
		 * @return this builder
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
			this.sseEndpoint = sseEndpoint;
			return this;
		}

		/**
		 * Sets the HTTP client builder.
		 * @param clientBuilder the HTTP client builder
		 * @return this builder
		 */
		public Builder clientBuilder(HttpClient.Builder clientBuilder) {
			Assert.notNull(clientBuilder, "clientBuilder must not be null");
			this.clientBuilder = clientBuilder;
			this.externalHttpClient = null; // Clear external client if builder is set
			return this;
		}

		/**
		 * Sets an external HttpClient instance to use instead of creating a new one. When
		 * an external HttpClient is provided, the transport will not attempt to close it
		 * during graceful shutdown, leaving resource management to the caller.
		 * @param httpClient the HttpClient instance to use
		 * @return this builder
		 */
		public Builder httpClient(HttpClient httpClient) {
			Assert.notNull(httpClient, "httpClient must not be null");
			this.externalHttpClient = httpClient;
			return this;
		}

		/**
		 * Customizes the HTTP client builder.
		 * @param clientCustomizer the consumer to customize the HTTP client builder
		 * @return this builder
		 */
		public Builder customizeClient(final Consumer<HttpClient.Builder> clientCustomizer) {
			Assert.notNull(clientCustomizer, "clientCustomizer must not be null");
			clientCustomizer.accept(clientBuilder);
			return this;
		}

		/**
		 * Sets the HTTP request builder.
		 * @param requestBuilder the HTTP request builder
		 * @return this builder
		 */
		public Builder requestBuilder(HttpRequest.Builder requestBuilder) {
			Assert.notNull(requestBuilder, "requestBuilder must not be null");
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
		 * Sets the JSON mapper implementation to use for serialization/deserialization.
		 * @param jsonMapper the JSON mapper
		 * @return this builder
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "jsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the customizer for {@link HttpRequest.Builder}, to modify requests before
		 * executing them.
		 * <p>
		 * This overrides the customizer from
		 * {@link #asyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer)}.
		 * <p>
		 * Do NOT use a blocking {@link McpSyncHttpClientRequestCustomizer} in a
		 * non-blocking context. Use
		 * {@link #asyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer)}
		 * instead.
		 * @param syncHttpRequestCustomizer the request customizer
		 * @return this builder
		 */
		public Builder httpRequestCustomizer(McpSyncHttpClientRequestCustomizer syncHttpRequestCustomizer) {
			this.httpRequestCustomizer = McpAsyncHttpClientRequestCustomizer.fromSync(syncHttpRequestCustomizer);
			return this;
		}

		/**
		 * Sets the customizer for {@link HttpRequest.Builder}, to modify requests before
		 * executing them.
		 * <p>
		 * This overrides the customizer from
		 * {@link #httpRequestCustomizer(McpSyncHttpClientRequestCustomizer)}.
		 * <p>
		 * Do NOT use a blocking implementation in a non-blocking context.
		 * @param asyncHttpRequestCustomizer the request customizer
		 * @return this builder
		 */
		public Builder asyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer asyncHttpRequestCustomizer) {
			this.httpRequestCustomizer = asyncHttpRequestCustomizer;
			return this;
		}

		/**
		 * Sets the connection timeout for the HTTP client.
		 * @param connectTimeout the connection timeout duration
		 * @return this builder
		 */
		public Builder connectTimeout(Duration connectTimeout) {
			Assert.notNull(connectTimeout, "connectTimeout must not be null");
			this.connectTimeout = connectTimeout;
			return this;
		}

		/**
		 * Builds a new {@link HttpClientSseClientTransport} instance.
		 * @return a new transport instance
		 */
		public HttpClientSseClientTransport build() {
			HttpClient httpClient;
			boolean shouldCloseHttpClient;

			if (externalHttpClient != null) {
				// Use external HttpClient, don't close it
				httpClient = externalHttpClient;
				shouldCloseHttpClient = false;
			}
			else {
				// Create new HttpClient, should close it
				httpClient = this.clientBuilder.connectTimeout(this.connectTimeout).build();
				shouldCloseHttpClient = true;
			}

			return new HttpClientSseClientTransport(httpClient, requestBuilder, baseUri, sseEndpoint,
					jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper, httpRequestCustomizer,
					shouldCloseHttpClient);
		}

	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		var uri = Utils.resolveUri(this.baseUri, this.sseEndpoint);

		return Mono.deferContextual(ctx -> {
			var builder = requestBuilder.copy()
				.uri(uri)
				.header("Accept", "text/event-stream")
				.header("Cache-Control", "no-cache")
				.header(MCP_PROTOCOL_VERSION_HEADER_NAME, MCP_PROTOCOL_VERSION)
				.GET();
			var transportContext = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
			return Mono.from(this.httpRequestCustomizer.customize(builder, "GET", uri, null, transportContext));
		}).flatMap(requestBuilder -> Mono.create(sink -> {
			Disposable connection = Flux.<ResponseEvent>create(sseSink -> this.httpClient
				.sendAsync(requestBuilder.build(),
						responseInfo -> ResponseSubscribers.sseToBodySubscriber(responseInfo, sseSink))
				.exceptionallyCompose(e -> {
					sseSink.error(e);
					return CompletableFuture.failedFuture(e);
				}))
				.map(responseEvent -> (ResponseSubscribers.SseResponseEvent) responseEvent)
				.flatMap(responseEvent -> {
					if (isClosing) {
						return Mono.empty();
					}

					int statusCode = responseEvent.responseInfo().statusCode();

					if (statusCode >= 200 && statusCode < 300) {
						try {
							if (ENDPOINT_EVENT_TYPE.equals(responseEvent.sseEvent().event())) {
								String messageEndpointUri = responseEvent.sseEvent().data();
								if (this.messageEndpointSink.tryEmitValue(messageEndpointUri).isSuccess()) {
									sink.success();
									return Flux.empty(); // No further processing needed
								}
								else {
									sink.error(new RuntimeException("Failed to handle SSE endpoint event"));
								}
							}
							else if (MESSAGE_EVENT_TYPE.equals(responseEvent.sseEvent().event())) {
								JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper,
										responseEvent.sseEvent().data());
								sink.success();
								return Flux.just(message);
							}
							else {
								logger.debug("Received unrecognized SSE event type: {}", responseEvent.sseEvent());
								sink.success();
							}
						}
						catch (IOException e) {
							sink.error(new McpTransportException("Error processing SSE event", e));
						}
					}
					return Flux.<McpSchema.JSONRPCMessage>error(
							new RuntimeException("Failed to send message: " + responseEvent));

				})
				.flatMap(jsonRpcMessage -> handler.apply(Mono.just(jsonRpcMessage)))
				.onErrorComplete(t -> {
					if (!isClosing) {
						logger.warn("SSE stream observed an error", t);
						sink.error(t);
					}
					return true;
				})
				.doFinally(s -> {
					Disposable ref = this.sseSubscription.getAndSet(null);
					if (ref != null && !ref.isDisposed()) {
						ref.dispose();
					}
				})
				.contextWrite(sink.contextView())
				.subscribe();

			this.sseSubscription.set(connection);
		}));
	}

	/**
	 * Sends a JSON-RPC message to the server.
	 *
	 * <p>
	 * This method waits for the message endpoint to be discovered before sending the
	 * message. The message is serialized to JSON and sent as an HTTP POST request.
	 * @param message the JSON-RPC message to send
	 * @return a Mono that completes when the message is sent
	 * @throws McpError if the message endpoint is not available or the wait times out
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {

		return this.messageEndpointSink.asMono().flatMap(messageEndpointUri -> {
			if (isClosing) {
				return Mono.empty();
			}

			return this.serializeMessage(message)
				.flatMap(body -> sendHttpPost(messageEndpointUri, body).handle((response, sink) -> {
					if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 202
							&& response.statusCode() != 206) {
						sink.error(new RuntimeException("Sending message failed with a non-OK HTTP code: "
								+ response.statusCode() + " - " + response.body()));
					}
					else {
						sink.next(response);
						sink.complete();
					}
				}))
				.doOnError(error -> {
					if (!isClosing) {
						logger.error("Error sending message: {}", error.getMessage());
					}
				});
		}).then();

	}

	private Mono<String> serializeMessage(final JSONRPCMessage message) {
		return Mono.defer(() -> {
			try {
				return Mono.just(jsonMapper.writeValueAsString(message));
			}
			catch (IOException e) {
				return Mono.error(new McpTransportException("Failed to serialize message", e));
			}
		});
	}

	private Mono<HttpResponse<String>> sendHttpPost(final String endpoint, final String body) {
		final URI requestUri = Utils.resolveUri(baseUri, endpoint);
		return Mono.deferContextual(ctx -> {
			var builder = this.requestBuilder.copy()
				.uri(requestUri)
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.header(MCP_PROTOCOL_VERSION_HEADER_NAME, MCP_PROTOCOL_VERSION)
				.POST(HttpRequest.BodyPublishers.ofString(body));
			var transportContext = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
			return Mono.from(this.httpRequestCustomizer.customize(builder, "POST", requestUri, body, transportContext));
		}).flatMap(customizedBuilder -> {
			var request = customizedBuilder.build();
			return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
		});
	}

	/**
	 * Gracefully closes the transport connection.
	 *
	 * <p>
	 * Sets the closing flag and disposes of the SSE subscription. This prevents new
	 * messages from being sent and allows ongoing operations to complete.
	 * @return a Mono that completes when the closing process is initiated
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			isClosing = true;
			Disposable subscription = sseSubscription.get();
			if (subscription != null && !subscription.isDisposed()) {
				subscription.dispose();
			}
		}).then(shouldCloseHttpClient ? Mono.fromRunnable(this::closeHttpClientResources) : Mono.empty());
	}

	/**
	 * Closes HttpClient resources including connection pools and selector threads. This
	 * method uses reflection to access internal HttpClient implementation details.
	 */
	private void closeHttpClientResources() {
		try {
			// Access HttpClientImpl internal fields via reflection
			Class<?> httpClientClass = httpClient.getClass();

			// Close SelectorManager if present
			try {
				java.lang.reflect.Field selectorManagerField = httpClientClass.getDeclaredField("selectorManager");
				selectorManagerField.setAccessible(true);
				Object selectorManager = selectorManagerField.get(httpClient);

				if (selectorManager != null) {
					java.lang.reflect.Method shutdownMethod = selectorManager.getClass().getMethod("shutdown");
					shutdownMethod.invoke(selectorManager);
					logger.debug("HttpClient SelectorManager shutdown completed");
				}
			}
			catch (NoSuchFieldException | NoSuchMethodException e) {
				// Field/method might not exist in different JDK versions, continue with
				// other cleanup
				logger.debug("SelectorManager field/method not found, skipping: {}", e.getMessage());
			}

		}
		catch (Exception e) {
			logger.warn("Failed to close HttpClient resources cleanly: {}", e.getMessage());
		}
	}

	/**
	 * Unmarshal data to the specified type using the configured object mapper.
	 * @param data the data to unmarshal
	 * @param typeRef the type reference for the target type
	 * @param <T> the target type
	 * @return the unmarshalled object
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.jsonMapper.convertValue(data, typeRef);
	}

}
