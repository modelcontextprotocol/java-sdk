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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

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
	private final String baseUri;

	/** SSE endpoint path */
	private final String sseEndpoint;

	/** SSE client for handling server-sent events. Uses the /sse endpoint */
	private final FlowSseClient sseClient;

	/**
	 * HTTP client for sending messages to the server. Uses HTTP POST over the message
	 * endpoint
	 */
	private final HttpClient httpClient;

	/** JSON object mapper for message serialization/deserialization */
	protected ObjectMapper objectMapper;

	/** Flag indicating if the transport is in closing state */
	private volatile boolean isClosing = false;

	/** Latch for coordinating endpoint discovery */
	private final CountDownLatch closeLatch = new CountDownLatch(1);

	/** Holds the discovered message endpoint URL */
	private final AtomicReference<String> messageEndpoint = new AtomicReference<>();

	/** Holds the SSE connection future */
	private final AtomicReference<CompletableFuture<Void>> connectionFuture = new AtomicReference<>();

	/**
	 * Creates a new transport instance with default HTTP client and object mapper.
	 * @param baseUri the base URI of the MCP server
	 */
	public HttpClientSseClientTransport(String baseUri) {
		this(HttpClient.newBuilder(), baseUri, new ObjectMapper());
	}

	/**
	 * Creates a new transport instance with custom HTTP client builder and object mapper.
	 * @param clientBuilder the HTTP client builder to use
	 * @param baseUri the base URI of the MCP server
	 * @param objectMapper the object mapper for JSON serialization/deserialization
	 * @throws IllegalArgumentException if objectMapper or clientBuilder is null
	 */
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
	 */
	public HttpClientSseClientTransport(HttpClient.Builder clientBuilder, String baseUri, String sseEndpoint,
			ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.hasText(baseUri, "baseUri must not be empty");
		Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
		Assert.notNull(clientBuilder, "clientBuilder must not be null");
		this.baseUri = baseUri;
		this.sseEndpoint = sseEndpoint;
		this.objectMapper = objectMapper;
		this.httpClient = clientBuilder.connectTimeout(Duration.ofSeconds(10)).build();
		this.sseClient = new FlowSseClient(this.httpClient);
	}

	/**
	 * Creates a new builder for {@link HttpClientSseClientTransport}.
	 * @param baseUri the base URI of the MCP server
	 * @return a new builder instance
	 */
	public static Builder builder(String baseUri) {
		return new Builder(baseUri);
	}

	/**
	 * Builder for {@link HttpClientSseClientTransport}.
	 */
	public static class Builder {

		private final String baseUri;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		private HttpClient.Builder clientBuilder = HttpClient.newBuilder();

		private ObjectMapper objectMapper = new ObjectMapper();

		/**
		 * Creates a new builder with the specified base URI.
		 * @param baseUri the base URI of the MCP server
		 */
		public Builder(String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
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
			return new HttpClientSseClientTransport(clientBuilder, baseUri, sseEndpoint, objectMapper);
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
		CompletableFuture<Void> future = new CompletableFuture<>();
		connectionFuture.set(future);

		sseClient.subscribe(this.baseUri + this.sseEndpoint, new FlowSseClient.SseEventHandler() {
			@Override
			public void onEvent(SseEvent event) {
				if (isClosing) {
					return;
				}

				try {
					if (ENDPOINT_EVENT_TYPE.equals(event.type())) {
						String endpoint = event.data();
						messageEndpoint.set(endpoint);
						closeLatch.countDown();
						future.complete(null);
					}
					else if (MESSAGE_EVENT_TYPE.equals(event.type())) {
						JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, event.data());
						handler.apply(Mono.just(message)).subscribe();
					}
					else {
						logger.error("Received unrecognized SSE event type: {}", event.type());
					}
				}
				catch (IOException e) {
					logger.error("Error processing SSE event", e);
					future.completeExceptionally(e);
				}
			}

			@Override
			public void onError(Throwable error) {
				if (!isClosing) {
					logger.error("SSE connection error", error);
					future.completeExceptionally(error);
				}
			}
		});

		return Mono.fromFuture(future);
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
		if (isClosing) {
			return Mono.empty();
		}

		try {
			if (!closeLatch.await(10, TimeUnit.SECONDS)) {
				return Mono.error(new McpError("Failed to wait for the message endpoint"));
			}
		}
		catch (InterruptedException e) {
			return Mono.error(new McpError("Failed to wait for the message endpoint"));
		}

		String endpoint = messageEndpoint.get();
		if (endpoint == null) {
			return Mono.error(new McpError("No message endpoint available"));
		}

		try {
			String jsonText = this.objectMapper.writeValueAsString(message);
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(this.baseUri + endpoint))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonText))
				.build();

			return Mono.fromFuture(
					httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
						if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 202
								&& response.statusCode() != 206) {
							logger.error("Error sending message: {}", response.statusCode());
						}
					}));
		}
		catch (IOException e) {
			if (!isClosing) {
				return Mono.error(new RuntimeException("Failed to serialize message", e));
			}
			return Mono.empty();
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
		return Mono.fromRunnable(() -> {
			isClosing = true;
			CompletableFuture<Void> future = connectionFuture.get();
			if (future != null && !future.isDone()) {
				future.cancel(true);
			}
		});
	}

	/**
	 * Unmarshals data to the specified type using the configured object mapper.
	 * @param data the data to unmarshal
	 * @param typeRef the type reference for the target type
	 * @param <T> the target type
	 * @return the unmarshalled object
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

}
