/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.util.retry.Retry;

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

	/** SSE client for handling server-sent events. Uses the /sse endpoint */
	private final FlowSseClient sseClient;

	/**
	 * HTTP client for sending messages to the server. Uses HTTP POST over the message
	 * endpoint
	 */
	private final HttpClient httpClient;

	/** HTTP request builder for building requests to send messages to the server */
	private final HttpRequest.Builder requestBuilder;

	/** JSON object mapper for message serialization/deserialization */
	protected ObjectMapper objectMapper;

	/** Enum indicating the transport state */
	private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.DISCONNECTED);

	/** Holds the discovered message endpoint URL */
	private final AtomicReference<String> messageEndpoint = new AtomicReference<>();

	/**
	 * Creates a new transport instance with default HTTP client and object mapper.
	 * @param baseUri the base URI of the MCP server
	 * @deprecated Use {@link HttpClientSseClientTransport#builder(String)} instead. This
	 * constructor will be removed in future versions.
	 */
	@Deprecated(forRemoval = true)
	public HttpClientSseClientTransport(String baseUri) {
		this(HttpClient.newBuilder(), baseUri, new ObjectMapper());
	}

	/**
	 * Creates a new transport instance with custom HTTP client builder and object mapper.
	 * @param clientBuilder the HTTP client builder to use
	 * @param baseUri the base URI of the MCP server
	 * @param objectMapper the object mapper for JSON serialization/deserialization
	 * @throws IllegalArgumentException if objectMapper or clientBuilder is null
	 * @deprecated Use {@link HttpClientSseClientTransport#builder(String)} instead. This
	 * constructor will be removed in future versions.
	 */
	@Deprecated(forRemoval = true)
	public HttpClientSseClientTransport(HttpClient.Builder clientBuilder, String baseUri, ObjectMapper objectMapper) {
		this(clientBuilder, baseUri, DEFAULT_SSE_ENDPOINT, objectMapper);
	}

	/**
	 * Creates a new transport instance with custom HTTP client builder and object mapper.
	 * @param clientBuilder the HTTP client builder to use
	 * @param baseUri the base URI of the MCP server
	 * @param sseEndpoint the SSE endpoint path
	 * @param objectMapper the object mapper for JSON serialization/deserialization
	 * @throws IllegalArgumentException if objectMapper or clientBuilder is null
	 * @deprecated Use {@link HttpClientSseClientTransport#builder(String)} instead. This
	 * constructor will be removed in future versions.
	 */
	@Deprecated(forRemoval = true)
	public HttpClientSseClientTransport(HttpClient.Builder clientBuilder, String baseUri, String sseEndpoint,
			ObjectMapper objectMapper) {
		this(clientBuilder, HttpRequest.newBuilder(), baseUri, sseEndpoint, objectMapper);
	}

	/**
	 * Creates a new transport instance with custom HTTP client builder, object mapper,
	 * and headers.
	 * @param clientBuilder the HTTP client builder to use
	 * @param requestBuilder the HTTP request builder to use
	 * @param baseUri the base URI of the MCP server
	 * @param sseEndpoint the SSE endpoint path
	 * @param objectMapper the object mapper for JSON serialization/deserialization
	 * @throws IllegalArgumentException if objectMapper, clientBuilder, or headers is null
	 * @deprecated Use {@link HttpClientSseClientTransport#builder(String)} instead. This
	 * constructor will be removed in future versions.
	 */
	@Deprecated(forRemoval = true)
	public HttpClientSseClientTransport(HttpClient.Builder clientBuilder, HttpRequest.Builder requestBuilder,
			String baseUri, String sseEndpoint, ObjectMapper objectMapper) {
		this(clientBuilder.connectTimeout(Duration.ofSeconds(10)).build(), requestBuilder, baseUri, sseEndpoint,
				objectMapper);
	}

	/**
	 * Creates a new transport instance with custom HTTP client builder, object mapper,
	 * and headers.
	 * @param httpClient the HTTP client to use
	 * @param requestBuilder the HTTP request builder to use
	 * @param baseUri the base URI of the MCP server
	 * @param sseEndpoint the SSE endpoint path
	 * @param objectMapper the object mapper for JSON serialization/deserialization
	 * @throws IllegalArgumentException if objectMapper, clientBuilder, or headers is null
	 */
	HttpClientSseClientTransport(HttpClient httpClient, HttpRequest.Builder requestBuilder, String baseUri,
			String sseEndpoint, ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.hasText(baseUri, "baseUri must not be empty");
		Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
		Assert.notNull(httpClient, "httpClient must not be null");
		Assert.notNull(requestBuilder, "requestBuilder must not be null");
		this.baseUri = URI.create(baseUri);
		this.sseEndpoint = sseEndpoint;
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;

		this.sseClient = new FlowSseClient(this.httpClient, requestBuilder);
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

		private HttpClient.Builder clientBuilder = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10));

		private ObjectMapper objectMapper = new ObjectMapper();

		private HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
			.header("Content-Type", "application/json");

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
		 * Sets the object mapper for JSON serialization/deserialization.
		 * @param objectMapper the object mapper
		 * @return this builder
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "objectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Builds a new {@link HttpClientSseClientTransport} instance.
		 * @return a new transport instance
		 */
		public HttpClientSseClientTransport build() {
			return new HttpClientSseClientTransport(clientBuilder.build(), requestBuilder, baseUri, sseEndpoint,
					objectMapper);
		}

	}

	/**
	 * Establishes the SSE connection with the server and sets up message handling.
	 *
	 * <p>
	 * This method:
	 * <ul>
	 * <li>Initiates the SSE connection</li>
	 * <li>Handles endpoint discovery events</li>
	 * <li>Processes incoming JSON-RPC messages</li>
	 * </ul>
	 * @param handler the function to process received JSON-RPC messages
	 * @return a Mono that completes when the connection is established
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		state.set(TransportState.CONNECTING);
		return Mono.<Void>create(sink -> subscribeSse(handler, sink))
			.doOnError(err -> logger.error("Error during connection", err));

	}

	private void subscribeSse(final Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler, MonoSink<Void> sink) {
		final URI clientUri = Utils.resolveUri(this.baseUri, this.sseEndpoint);
		sseClient.subscribe(clientUri.toString(), new FlowSseClient.SseEventHandler() {
			@Override
			public void onEvent(SseEvent event) {
				if (state.get() == TransportState.CLOSING || state.get() == TransportState.DISCONNECTED) {
					return;
				}
				sink.success();
				try {
					switch (event.type()) {
						case ENDPOINT_EVENT_TYPE -> {
							messageEndpoint.set(event.data());
							state.set(TransportState.CONNECTED);
						}
						case MESSAGE_EVENT_TYPE -> {
							JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, event.data());
							handler.apply(Mono.just(message)).subscribe();
						}
						default -> logger.error("Received unrecognized SSE event type: {}", event.type());
					}
				}
				catch (Exception e) {
					logger.error("Error processing SSE event", e);
					sink.error(new McpError("Error processing SSE event"));
				}
			}

			@Override
			public void onError(Throwable error) {
				if (state.get() != TransportState.CLOSING) {
					logger.error("SSE connection error", error);
					sink.error(error);
				}
			}
		});
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
		if (state.get() == TransportState.CLOSING || state.get() == TransportState.DISCONNECTED) {
			return Mono.empty();
		}
		return Mono.defer(() -> {
			if (messageEndpoint.get() == null) {
				return Mono.error(new McpError("No message endpoint available"));
			}

			return serializeMessage(message).flatMap(body -> sendHttpPost(messageEndpoint.get(), body))
				.doOnNext(this::logIfNotOk)
				.doOnError(err -> logger.error("Error sending message", err))
				.then();

		}).retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(3)).filter(err -> messageEndpoint.get() == null));
	}

	private Mono<String> serializeMessage(final JSONRPCMessage message) {
		try {
			return Mono.just(objectMapper.writeValueAsString(message));
		}
		catch (IOException e) {
			return Mono.error(new McpError("Failed to serialize message"));
		}
	}

	private Mono<HttpResponse<Void>> sendHttpPost(final String endpoint, final String body) {
		final URI requestUri = Utils.resolveUri(baseUri, endpoint);
		final HttpRequest request = requestBuilder.uri(requestUri)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();

		return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()));
	}

	private void logIfNotOk(final HttpResponse<?> response) {
		if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 202
				&& response.statusCode() != 206) {
			logger.error("Error sending message: {}", response.statusCode());
		}
	}

	/**
	 * Gracefully closes the transport connection.
	 *
	 * <p>
	 * Sets the closing flag and cancels any pending connection future. This prevents new
	 * messages from being sent and allows ongoing operations to complete.
	 * @return a Mono that completes when the closing process is initiated
	 */
	@Override
	public Mono<Void> closeGracefully() {
		state.set(TransportState.CLOSING);
		return Mono.fromRunnable(() -> {
			sseClient.close();
			state.set(TransportState.DISCONNECTED);
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
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	/**
	 * Get the current transport state.
	 * @return the current transport state
	 */
	public TransportState getState() {
		return state.get();
	}

	// Enum to manage transport states
	public enum TransportState {

		DISCONNECTED, CONNECTING, CONNECTED, CLOSING

	}

}
