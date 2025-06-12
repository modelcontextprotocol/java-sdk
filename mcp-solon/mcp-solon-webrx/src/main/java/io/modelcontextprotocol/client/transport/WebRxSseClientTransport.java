/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtilsBuilder;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.noear.solon.rx.SimpleSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Server-Sent Events (SSE) implementation of the
 * {@link io.modelcontextprotocol.spec.McpTransport} that follows the MCP HTTP with SSE
 * transport specification.
 *
 * <p>
 * This transport establishes a bidirectional communication channel where:
 * <ul>
 * <li>Inbound messages are received through an SSE connection from the server</li>
 * <li>Outbound messages are sent via HTTP POST requests to a server-provided
 * endpoint</li>
 * </ul>
 *
 * <p>
 * The message flow follows these steps:
 * <ol>
 * <li>The client establishes an SSE connection to the server's /sse endpoint</li>
 * <li>The server sends an 'endpoint' event containing the URI for sending messages</li>
 * </ol>
 *
 * This implementation uses {@link HttpUtilsBuilder} for HTTP communications and supports JSON. and base JDK8
 * serialization/deserialization of messages.
 *
 * @author Christian Tzolov
 * @author noear
 * @see <a href=
 * "https://spec.modelcontextprotocol.io/specification/basic/transports/#http-with-sse">MCP
 * HTTP with SSE Transport Specification</a>
 */
public class WebRxSseClientTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebRxSseClientTransport.class);

	/** SSE event type for JSON-RPC messages */
	private static final String MESSAGE_EVENT_TYPE = "message";

	/** SSE event type for endpoint discovery */
	private static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/** Default SSE endpoint path */
	private static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/** HttpUtils instance builder */
	private final HttpUtilsBuilder webBuilder;

	/** SSE endpoint path */
	private final String sseEndpoint;

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
	 * @param webBuilder the HttpUtilsBuilder to use for creating the HttpUtils instance
	 */
	public WebRxSseClientTransport(HttpUtilsBuilder webBuilder) {
		this(webBuilder, new ObjectMapper());
	}

	/**
	 * Creates a new transport instance with custom HTTP client builder and object mapper.
	 * @param webBuilder the HttpUtilsBuilder to use for creating the HttpUtils instance
	 * @param objectMapper the object mapper for JSON serialization/deserialization
	 * @throws IllegalArgumentException if objectMapper or clientBuilder is null
	 */
	public WebRxSseClientTransport(HttpUtilsBuilder webBuilder, ObjectMapper objectMapper) {
		this(webBuilder, DEFAULT_SSE_ENDPOINT, objectMapper);
	}

	/**
	 * Creates a new transport instance with custom HTTP client builder and object mapper.
	 * @param webBuilder the HttpUtilsBuilder to use for creating the HttpUtils instance
	 * @param sseEndpoint the SSE endpoint path
	 * @param objectMapper the object mapper for JSON serialization/deserialization
	 * @throws IllegalArgumentException if objectMapper or clientBuilder is null
	 */
	public WebRxSseClientTransport(HttpUtilsBuilder webBuilder, String sseEndpoint,
								   ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(webBuilder, "baseUri must not be empty");
		Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
		this.webBuilder = webBuilder;
		this.sseEndpoint = sseEndpoint;
		this.objectMapper = objectMapper;
	}

	/**
	 * Creates a new builder for {@link WebRxSseClientTransport}.
	 * @param webBuilder the HttpUtilsBuilder to use for creating the HttpUtils instance
	 * @return a new builder instance
	 */
	public static Builder builder(HttpUtilsBuilder webBuilder) {
		return new Builder(webBuilder);
	}

	/**
	 * Builder for {@link WebRxSseClientTransport}.
	 */
	public static class Builder {

		private final HttpUtilsBuilder webBuilder;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		private ObjectMapper objectMapper = new ObjectMapper();

		/**
		 * Creates a new builder with the specified base URI.
		 * @param webBuilder the HttpUtilsBuilder to use for creating the HttpUtils instance
		 */
		public Builder(HttpUtilsBuilder webBuilder) {
			Assert.notNull(webBuilder, "webBuilder must not be empty");
			this.webBuilder = webBuilder;
		}

		/**
		 * Sets the SSE endpoint path.
		 * @param sseEndpoint the SSE endpoint path
		 * @return this builder
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.hasText(sseEndpoint, "sseEndpoint must not be null");
			this.sseEndpoint = sseEndpoint;
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
		 * Builds a new {@link WebRxSseClientTransport} instance.
		 * @return a new transport instance
		 */
		public WebRxSseClientTransport build() {
			return new WebRxSseClientTransport(webBuilder, sseEndpoint, objectMapper);
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

		webBuilder.build(this.sseEndpoint)
				.execAsSseStream("GET")
				.subscribe(new SimpleSubscriber<ServerSentEvent>()
						.doOnNext(event -> {
							if (isClosing) {
								return;
							}

							try {
								if (ENDPOINT_EVENT_TYPE.equals(event.getEvent())) {
									String endpoint = event.data();
									messageEndpoint.set(endpoint);
									closeLatch.countDown();
									future.complete(null);
								} else if (MESSAGE_EVENT_TYPE.equals(event.getEvent())) {
									JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, event.data());
									handler.apply(Mono.just(message)).subscribe();
								} else {
									logger.error("Received unrecognized SSE event type: {}", event.getEvent());
								}
							} catch (IOException e) {
								logger.error("Error processing SSE event", e);
								future.completeExceptionally(e);
							}
						}).doOnError(error -> {
							if (!isClosing) {
								logger.warn("SSE connection error", error);
								future.completeExceptionally(error);
							}
						}));

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
		} catch (InterruptedException e) {
			return Mono.error(new McpError("Failed to wait for the message endpoint"));
		}

		String endpoint = messageEndpoint.get();
		if (endpoint == null) {
			return Mono.error(new McpError("No message endpoint available"));
		}

		try {
			String jsonText = this.objectMapper.writeValueAsString(message);
			CompletableFuture<HttpResponse> future = webBuilder.build(endpoint)
					.header("Content-Type", "application/json")
					.bodyOfJson(jsonText)
					.execAsync("POST");

			return Mono.fromFuture(future.thenAccept(response -> {
				if (response.code() != 200 && response.code() != 201 && response.code() != 202
						&& response.code() != 206) {
					logger.error("Error sending message: {}", response.code());
				}
			}));
		} catch (IOException e) {
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