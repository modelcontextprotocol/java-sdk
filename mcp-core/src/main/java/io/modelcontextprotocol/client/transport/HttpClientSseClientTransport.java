/*
 * Copyright 2024 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.client.transport.customizer.DelegatingMcpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * @deprecated This SSE transport is deprecated. Use Streamable HTTP instead, with
 * {@link HttpClientStreamableHttpTransport}.
 * @see io.modelcontextprotocol.spec.McpTransport
 * @see io.modelcontextprotocol.spec.McpClientTransport
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#backwards-compatibility">Transports
 * backwards compatibility</a>
 */
@Deprecated
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

	/**
	 * Default maximum number of bytes read for a single inbound message.
	 */
	private static final int DEFAULT_MAX_RESPONSE_SIZE = 16 * 1024 * 1024; // 16MiB

	/** Base URI for the MCP server */
	private final URI baseUri;

	/** SSE endpoint path */
	private final String sseEndpoint;

	/**
	 * HTTP client for sending messages to the server. Uses HTTP POST over the message
	 * endpoint
	 */
	private final HttpClient httpClient;

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
	 * Validator for the message endpoint;
	 */
	private final SseMessageEndpointValidator messageEndpointValidator;

	/**
	 * Maximum number of bytes read for a single inbound message, whether it arrives on
	 * the SSE stream or as the response to a posted message.
	 */
	private final int maxResponseSize;

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
	 * @param messageEndpointValidator validator for the message endpoint
	 * @param maxResponseSize the maximum number of bytes read for a single inbound
	 * message
	 * @throws IllegalArgumentException if objectMapper, clientBuilder, or headers is null
	 */
	HttpClientSseClientTransport(HttpClient httpClient, HttpRequest.Builder requestBuilder, String baseUri,
			String sseEndpoint, McpJsonMapper jsonMapper, McpAsyncHttpClientRequestCustomizer httpRequestCustomizer,
			SseMessageEndpointValidator messageEndpointValidator, int maxResponseSize) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.hasText(baseUri, "baseUri must not be empty");
		Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
		Assert.notNull(httpClient, "httpClient must not be null");
		Assert.notNull(requestBuilder, "requestBuilder must not be null");
		Assert.notNull(httpRequestCustomizer, "httpRequestCustomizer must not be null");
		Assert.notNull(messageEndpointValidator, "messageEndpointValidator must not be null");
		Assert.isTrue(maxResponseSize > 0, "maxResponseSize must be positive");
		this.baseUri = URI.create(baseUri);
		this.sseEndpoint = sseEndpoint;
		this.jsonMapper = jsonMapper;
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;
		this.httpRequestCustomizer = httpRequestCustomizer;
		this.messageEndpointValidator = messageEndpointValidator;
		this.maxResponseSize = maxResponseSize;
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

		private McpJsonMapper jsonMapper;

		private HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();

		private final List<McpAsyncHttpClientRequestCustomizer> httpRequestCustomizers = new ArrayList<>();

		private Duration connectTimeout = Duration.ofSeconds(10);

		private SseMessageEndpointValidator messageEndpointValidator = new DefaultSseMessageEndpointValidator();

		private int maxResponseSize = DEFAULT_MAX_RESPONSE_SIZE;

		/**
		 * Creates a new builder instance.
		 */
		Builder() {
			// Default constructor
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
		 * executing them. The customizer is internally converted to
		 * {@link McpAsyncHttpClientRequestCustomizer}.
		 * <p>
		 * This replaces all customizers previously registered on this builder.
		 * <p>
		 * Do NOT use a blocking {@link McpSyncHttpClientRequestCustomizer} in a
		 * non-blocking context. Use
		 * {@link #asyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer)}
		 * instead.
		 * @param syncHttpRequestCustomizer the request customizer
		 * @return this builder
		 * @deprecated Use {@link #addHttpRequestCustomizer} instead.
		 */
		@Deprecated
		public Builder httpRequestCustomizer(McpSyncHttpClientRequestCustomizer syncHttpRequestCustomizer) {
			Assert.notNull(syncHttpRequestCustomizer, "syncHttpRequestCustomizer must not be null");
			this.httpRequestCustomizers.clear();
			return addHttpRequestCustomizer(syncHttpRequestCustomizer);
		}

		/**
		 * Sets the customizer for {@link HttpRequest.Builder}, to modify requests before
		 * executing them.
		 * <p>
		 * This replaces all customizers previously registered on this builder.
		 * <p>
		 * Do NOT use a blocking implementation in a non-blocking context.
		 * @param asyncHttpRequestCustomizer the request customizer
		 * @return this builder
		 * @deprecated Use {@link #addAsyncHttpRequestCustomizer} instead.
		 */
		@Deprecated
		public Builder asyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer asyncHttpRequestCustomizer) {
			Assert.notNull(asyncHttpRequestCustomizer, "asyncHttpRequestCustomizer must not be null");
			this.httpRequestCustomizers.clear();
			return addAsyncHttpRequestCustomizer(asyncHttpRequestCustomizer);
		}

		/**
		 * Adds a customizer for {@link HttpRequest.Builder}, to modify requests before
		 * executing them. Customizers are applied in the order they are added, after
		 * those already registered on this builder. The customizer is internally
		 * converted to {@link McpAsyncHttpClientRequestCustomizer}.
		 * <p>
		 * Do NOT use a blocking {@link McpSyncHttpClientRequestCustomizer} in a
		 * non-blocking context. Use
		 * {@link #addAsyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer)}
		 * instead.
		 * @param syncHttpRequestCustomizer the request customizer
		 * @return this builder
		 */
		public Builder addHttpRequestCustomizer(McpSyncHttpClientRequestCustomizer syncHttpRequestCustomizer) {
			Assert.notNull(syncHttpRequestCustomizer, "syncHttpRequestCustomizer must not be null");
			this.httpRequestCustomizers.add(McpAsyncHttpClientRequestCustomizer.fromSync(syncHttpRequestCustomizer));
			return this;
		}

		/**
		 * Adds a customizer for {@link HttpRequest.Builder}, to modify requests before
		 * executing them. Customizers are applied in the order they are added, after
		 * those already registered on this builder.
		 * <p>
		 * Do NOT use a blocking implementation in a non-blocking context.
		 * @param asyncHttpRequestCustomizer the request customizer
		 * @return this builder
		 */
		public Builder addAsyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer asyncHttpRequestCustomizer) {
			Assert.notNull(asyncHttpRequestCustomizer, "asyncHttpRequestCustomizer must not be null");
			this.httpRequestCustomizers.add(asyncHttpRequestCustomizer);
			return this;
		}

		/**
		 * Provides access to the mutable list of request customizers registered on this
		 * builder, so they can be inspected, reordered, added or removed. Customizers are
		 * applied in list order. Synchronous customizers registered through
		 * {@link #httpRequestCustomizer(McpSyncHttpClientRequestCustomizer)} or
		 * {@link #addHttpRequestCustomizer(McpSyncHttpClientRequestCustomizer)} appear in
		 * the list wrapped as {@link McpAsyncHttpClientRequestCustomizer}.
		 * @param customizersConsumer a consumer of the list of customizers
		 * @return this builder
		 */
		public Builder asyncHttpRequestCustomizers(
				Consumer<List<McpAsyncHttpClientRequestCustomizer>> customizersConsumer) {
			Assert.notNull(customizersConsumer, "customizersConsumer must not be null");
			customizersConsumer.accept(this.httpRequestCustomizers);
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
		 * Sets the validator that ensure the message endpoint returned over the SSE
		 * connection is valid.
		 * @param messageEndpointValidator the validator
		 * @return this builder
		 */
		public Builder messageEndpointValidator(SseMessageEndpointValidator messageEndpointValidator) {
			Assert.notNull(messageEndpointValidator, "messageEndpointValidator must not be null");
			this.messageEndpointValidator = messageEndpointValidator;
			return this;
		}

		/**
		 * Sets the maximum number of bytes read for a single inbound message, whether it
		 * arrives on the SSE stream or as the response to a posted message. A peer that
		 * sends a larger message (or never terminates one) has its stream aborted instead
		 * of forcing the transport to buffer it in memory. Defaults to 16MiB.
		 *
		 * <p>
		 * The bound applies per message, not to the stream as a whole: a long-lived SSE
		 * stream may deliver any number of messages, each up to this size. SSE field
		 * framing is allowed a small amount of headroom on top of this size, so a message
		 * of exactly this many bytes is still accepted.
		 * @param maxResponseSize the maximum inbound message size, in bytes
		 * @return this builder
		 */
		public Builder maxResponseSize(int maxResponseSize) {
			Assert.isTrue(maxResponseSize > 0, "maxResponseSize must be positive");
			this.maxResponseSize = maxResponseSize;
			return this;
		}

		/**
		 * Builds a new {@link HttpClientSseClientTransport} instance.
		 * @return a new transport instance
		 */
		public HttpClientSseClientTransport build() {
			HttpClient httpClient = this.clientBuilder.connectTimeout(this.connectTimeout).build();
			return new HttpClientSseClientTransport(httpClient, requestBuilder, baseUri, sseEndpoint,
					jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, httpRequestCustomizer(),
					messageEndpointValidator, maxResponseSize);
		}

		private McpAsyncHttpClientRequestCustomizer httpRequestCustomizer() {
			Assert.noNullElements(this.httpRequestCustomizers, "httpRequestCustomizers must not contain null elements");
			return switch (this.httpRequestCustomizers.size()) {
				case 0 -> McpAsyncHttpClientRequestCustomizer.NOOP;
				case 1 -> this.httpRequestCustomizers.get(0);
				default -> new DelegatingMcpAsyncHttpClientRequestCustomizer(List.copyOf(this.httpRequestCustomizers));
			};
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
			Disposable connection = ResponseBodyHandlers.sendAsync(this.httpClient, requestBuilder.build())
				.flatMapMany(response -> {
					if (isClosing) {
						// The body is handed over as a publisher and the connection is
						// only released once it is subscribed to. It is an SSE stream
						// that may never end, so it is cancelled rather than drained.
						return ResponseBodyHandlers.cancel(response.body());
					}

					int statusCode = response.statusCode();

					if (statusCode >= 200 && statusCode < 300) {
						Flux<String> lines = ResponseBodyHandlers.decodeLines(response.body(), this.maxResponseSize);
						return ResponseBodyHandlers.decodeSseResponse(lines, this.maxResponseSize);
					}
					else {
						return ResponseBodyHandlers.readThenError(response.body(), this.maxResponseSize,
								"Failed to connect to SSE stream: " + statusCode);
					}
				})
				// Every successfully processed event yields exactly one element, empty
				// when it carries no message, so that the first one can mark the
				// connection as established.
				.<Optional<JSONRPCMessage>>handle((sseEvent, events) -> {
					try {
						if (ENDPOINT_EVENT_TYPE.equals(sseEvent.event())) {
							String messageEndpointUri = sseEvent.data();
							try {
								messageEndpointValidator.validate(uri, messageEndpointUri);
							}
							catch (InvalidSseMessageEndpointException e) {
								this.messageEndpointSink.tryEmitError(e);
								events.error(e);
								return;
							}
							if (this.messageEndpointSink.tryEmitValue(messageEndpointUri).isSuccess()) {
								events.next(Optional.empty());
							}
							else {
								events.error(new McpTransportException("Failed to handle SSE endpoint event"));
							}
						}
						else if (MESSAGE_EVENT_TYPE.equals(sseEvent.event())) {
							String data = sseEvent.data();
							if (data == null || data.isBlank()) {
								logger.debug("Skipping SSE event with empty data (stream primer)");
								events.next(Optional.empty());
							}
							else {
								events.next(Optional.of(McpSchema.deserializeJsonRpcMessage(jsonMapper, data)));
							}
						}
						else {
							logger.debug("Received unrecognized SSE event type: {}", sseEvent);
							events.next(Optional.empty());
						}
					}
					catch (IOException e) {
						events.error(new McpTransportException("Error processing SSE event", e));
					}
				})
				// connect() is resolved by the first signal only: any later failure is
				// merely logged below, as connect() has already completed by then.
				.switchOnFirst((first, events) -> {
					if (first.hasValue()) {
						sink.success();
					}
					else if (first.isOnError()) {
						sink.error(first.getThrowable());
					}
					else if (first.isOnComplete()) {
						sink.error(new McpTransportException("SSE stream closed before any event was received"));
					}
					return events;
				})
				.<JSONRPCMessage>handle((message, messages) -> message.ifPresent(messages::next))
				.flatMap(message -> handler.apply(Mono.just(message)))
				.onErrorComplete(t -> {
					if (!isClosing) {
						logger.warn("SSE stream observed an error", t);
					}
					return true;
				})
				// A closeGracefully() before the first signal cancels the stream:
				// complete
				// connect() instead of leaving it pending. A no-op once it has resolved.
				.doOnCancel(sink::success)
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
				.flatMap(body -> sendHttpPost(messageEndpointUri, body))
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

	/**
	 * POSTs {@code body} to {@code endpoint} and consumes the response, failing if the
	 * server did not accept the message.
	 *
	 * <p>
	 * The response body is streamed rather than aggregated: it is only read as text when
	 * a non-OK status makes it part of the failure message, and discarded otherwise.
	 * Either way it has to be consumed, or the connection is never released.
	 */
	private Mono<Void> sendHttpPost(final String endpoint, final String body) {
		final URI requestUri = Utils.resolveUri(baseUri, endpoint);
		return Mono.deferContextual(ctx -> {
			var builder = this.requestBuilder.copy()
				.uri(requestUri)
				.header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
				.header(MCP_PROTOCOL_VERSION_HEADER_NAME, MCP_PROTOCOL_VERSION)
				.POST(HttpRequest.BodyPublishers.ofString(body));
			var transportContext = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
			return Mono.from(this.httpRequestCustomizer.customize(builder, "POST", requestUri, body, transportContext));
		}).flatMap(customizedBuilder -> {
			var request = customizedBuilder.build();
			return ResponseBodyHandlers.sendAsync(this.httpClient, request).flatMap(response -> {
				int statusCode = response.statusCode();
				if (statusCode == 200 || statusCode == 201 || statusCode == 202 || statusCode == 206) {
					return ResponseBodyHandlers.drain(response.body(), this.maxResponseSize).then();
				}
				return ResponseBodyHandlers.decodeAggregateResponse(response.body(), this.maxResponseSize)
					.flatMap(text -> Mono.error(new McpTransportException(
							"Sending message failed with a non-OK HTTP code: " + statusCode + " - " + text)));
			});
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
		});
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
