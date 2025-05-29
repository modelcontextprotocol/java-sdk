/*
 * Copyright 2024-2024 the original author or authors.
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Map;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;

import static io.modelcontextprotocol.spec.McpStreamableHttpClient.REQUIRED_ACCEPTED_CONTENT;
import static io.modelcontextprotocol.spec.McpStreamableHttpClient.REQUIRED_HEADERS;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Implementation of the MCP Streamable HTTP transport for clients. This implementation
 * follows the Streamable HTTP transport specification from protocol version 2024-11-05.
 *
 * <p>
 * The transport handles a single HTTP endpoint that supports both POST and GET methods:
 * <ul>
 * <li>POST - For sending client messages and optionally establishing SSE streams for
 * responses</li>
 * <li>GET - For establishing SSE streams for server-to-client communication</li>
 * <li>DELETE - For terminating sessions</li>
 * </ul>
 *
 * <p>
 * Features:
 * <ul>
 * <li>Session management with secure session IDs</li>
 * <li>Support for resumable SSE streams</li>
 * <li>Support for multiple concurrent client connections</li>
 * <li>Graceful shutdown support</li>
 * </ul>
 *
 */
public class StreamableHttpClientTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpClientTransport.class);

	/** SSE event type for JSON-RPC messages */
	private static final String MESSAGE_EVENT_TYPE = "message";

	/** Default endpoint path for MCP */
	private static final String DEFAULT_MCP_ENDPOINT = "/mcp";

	/** Session ID header name */
	private static final String SESSION_ID_HEADER = "Mcp-Session-Id";

	/** Last Event ID header name */
	private static final String LAST_EVENT_ID_HEADER = "Last-Event-Id";

	/** Base URI for the MCP server */
	private final URI baseUri;

	/** MCP endpoint path */
	private final String mcpEndpoint;

	/** SSE client for handling server-sent events */
	private final FlowSseClient sseClient;

	/** HTTP client for sending messages to the server */
	private final HttpClient httpClient;

	/** HTTP request builder for building requests to send messages to the server */
	private final HttpRequest.Builder requestBuilder;

	/** JSON object mapper for message serialization/deserialization */
	protected ObjectMapper objectMapper;

	/** Flag indicating if the transport is in closing state */
	private volatile boolean isClosing = false;

	/** Holds the session ID once established */
	private final AtomicReference<String> sessionId = new AtomicReference<>();

	/** Holds the SSE connection future */
	private final AtomicReference<CompletableFuture<Void>> connectionFuture = new AtomicReference<>();

	/** Latch for coordinating session establishment */
	private final CountDownLatch sessionLatch = new CountDownLatch(1);

	/** Holds the last event ID for resumability */
	private final AtomicReference<String> lastEventId = new AtomicReference<>();

	/** Stores the message handler for later use when setting up SSE connection */
	private final AtomicReference<Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>> messageHandler = new AtomicReference<>();

	private static final String ACCEPT = "Accept";

	private static final String CONTENT_TYPE = "Content-Type";

	private static final String APPLICATION_JSON = "application/json";

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private static final String APPLICATION_JSON_SEQ = "application/json-seq";

	/**
	 * Creates a new StreamableHttpClientTransport instance.
	 * @param httpClient The HTTP client to use
	 * @param requestBuilder The HTTP request builder to use
	 * @param baseUri The base URI of the MCP server
	 * @param mcpEndpoint The MCP endpoint path
	 * @param objectMapper The object mapper for JSON serialization/deserialization
	 */
	StreamableHttpClientTransport(HttpClient httpClient, HttpRequest.Builder requestBuilder, String baseUri,
			String mcpEndpoint, ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.hasText(baseUri, "baseUri must not be empty");
		Assert.hasText(mcpEndpoint, "mcpEndpoint must not be empty");
		Assert.notNull(httpClient, "httpClient must not be null");
		Assert.notNull(requestBuilder, "requestBuilder must not be null");

		this.baseUri = URI.create(baseUri);
		this.mcpEndpoint = mcpEndpoint;
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;

		this.sseClient = new FlowSseClient(this.httpClient, requestBuilder);
	}

	/**
	 * Creates a new builder for {@link StreamableHttpClientTransport}.
	 * @param baseUri the base URI of the MCP server
	 * @return a new builder instance
	 */
	public static Builder builder(String baseUri) {
		return new Builder().withBaseUri(baseUri);
	}

	/**
	 * Builder for {@link StreamableHttpClientTransport}.
	 */
	public static class Builder {

		private String baseUri;

		private String mcpEndpoint = DEFAULT_MCP_ENDPOINT;

		private ObjectMapper objectMapper = new ObjectMapper();

		// private Consumer<HttpClient.Builder> clientCustomization;

		// private Consumer<HttpRequest.Builder> requestCustomization;

		private HttpClient.Builder clientBuilder = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10));

		private HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();

		// .header("Content-Type", "application/json")
		// .header("Accept", "application/json, text/event-stream");

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
		public Builder withBaseUri(String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
			return this;
		}

		/**
		 * Sets the MCP endpoint path.
		 * @param mcpEndpoint the MCP endpoint path
		 * @return this builder
		 */
		public Builder withMcpEndpoint(String mcpEndpoint) {
			Assert.hasText(mcpEndpoint, "mcpEndpoint must not be empty");
			this.mcpEndpoint = mcpEndpoint;
			return this;
		}

		/**
		 * Sets the HTTP client builder.
		 * @param clientBuilder the HTTP client builder
		 * @return this builder
		 */
		public Builder withClientBuilder(HttpClient.Builder clientBuilder) {
			Assert.notNull(clientBuilder, "clientBuilder must not be null");
			this.clientBuilder = clientBuilder;
			return this;
		}

		/**
		 * Customizes the HTTP client builder.
		 * @param clientCustomizer the consumer to customize the HTTP client builder
		 * @return this builder
		 */
		public Builder withHttpClientCustomization(final Consumer<HttpClient.Builder> clientCustomization) {
			Assert.notNull(clientCustomization, "clientCustomizer must not be null");
			clientCustomization.accept(clientBuilder);
			// this.clientCustomization = clientCustomization;
			return this;
		}

		/**
		 * Customizes the HTTP client builder.
		 * @param requestCustomizer the consumer to customize the HTTP request builder
		 * @return this builder
		 */
		public Builder withRequestCustomization(final Consumer<HttpRequest.Builder> requestCustomization) {
			Assert.notNull(requestCustomization, "requestCustomizer must not be null");
			requestCustomization.accept(requestBuilder);
			// this.requestCustomization = requestCustomization;
			return this;
		}

		/**
		 * Sets the HTTP request builder.
		 * @param requestBuilder the HTTP request builder
		 * @return this builder
		 */
		public Builder withRequestBuilder(HttpRequest.Builder requestBuilder) {
			Assert.notNull(requestBuilder, "requestBuilder must not be null");
			this.requestBuilder = requestBuilder;
			Map<String, List<String>> headers = requestBuilder.build().headers().map();
			if (!headers.keySet().containsAll(REQUIRED_HEADERS)) {
				logger.warn(
						"Request builder does not contain all required headers. This may cause issues with the transport.");
			}
			else if (!headers.get("Accept").containsAll(REQUIRED_ACCEPTED_CONTENT)) {
				logger.warn(
						"Request builder 'Accept' header is missing required content. This may cause issues with the transport.");
			}
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
		 * Builds a new {@link StreamableHttpClientTransport} instance.
		 * @return a new transport instance
		 */
		public StreamableHttpClientTransport build() {
			return new StreamableHttpClientTransport(clientBuilder.build(), requestBuilder, baseUri, mcpEndpoint,
					objectMapper);
		}

	}

	/**
	 * Establishes the connection with the server and sets up message handling.
	 * @param handler the function to process received JSON-RPC messages
	 * @return a Mono that completes when the connection is established
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		connectionFuture.set(future);
		messageHandler.set(handler);

		// For Streamable HTTP, we don't need to establish a connection upfront
		// The connection will be established when sending the first message (typically
		// Initialize) and the SSE stream will be established after we have a session ID

		// Only set up SSE connection if we already have a session ID
		String sid = sessionId.get();
		if (sid != null) {
			setupSseConnection();
		}

		future.complete(null);
		return Mono.fromFuture(future);
	}

	/**
	 * Sets up the SSE connection after we have a valid session ID.
	 */
	private void setupSseConnection() {
		Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = messageHandler.get();
		if (handler == null) {
			logger.debug("No message handler available, skipping SSE connection setup");
			return;
		}

		// Set up a GET connection for receiving server-initiated messages
		URI getUri = Utils.resolveUri(this.baseUri, this.mcpEndpoint);
		HttpRequest.Builder getBuilder = HttpRequest.newBuilder(getUri)
			.GET()
			.header("Accept", "application/json, text/event-stream")
			.header("Content-Type", "application/json");

		// Add session ID - this should always be available at this point
		String sid = sessionId.get();
		if (sid == null) {
			logger.debug("No session ID available, skipping SSE connection setup");
			return;
		}
		getBuilder.header(SESSION_ID_HEADER, sid);

		// Add Last-Event-ID header for resumability if available
		String lastId = lastEventId.get();
		if (lastId != null) {
			getBuilder.header(LAST_EVENT_ID_HEADER, lastId);
		}

		logger.debug("Setting up SSE connection with session ID: {}", sid);

		// Subscribe to SSE events
		sseClient.subscribe(getUri.toString(), sid, new FlowSseClient.SseEventHandler() {
			@Override
			public void onEvent(SseEvent event) {
				if (isClosing) {
					return;
				}

				try {
					if (MESSAGE_EVENT_TYPE.equals(event.type())) {
						// Store the event ID for resumability
						String eventId = event.id();
						if (eventId != null) {
							lastEventId.set(eventId);
						}

						// Process the message
						JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, event.data());
						handler.apply(Mono.just(message))
							.doOnError(e -> logger.error("Error processing SSE message", e))
							.subscribe();
					}
					else {
						logger.debug("Received unrecognized SSE event type: {}", event.type());
					}
				}
				catch (IOException e) {
					logger.error("Error processing SSE event", e);
				}
			}

			@Override
			public void onError(Throwable error) {
				if (!isClosing) {
					logger.error("SSE connection error", error);
					// Don't fail the future as we might reconnect

					// Try to reconnect with the last event ID for resumability
					if (!isClosing) {
						logger.debug("Attempting to reconnect SSE stream");
						setupSseConnection();
					}
				}
			}
		});
	}

	/**
	 * Sends a JSON-RPC message to the server.
	 * @param message the JSON-RPC message to send
	 * @return a Mono that completes when the message is sent
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		if (isClosing) {
			return Mono.empty();
		}

		try {
			String jsonText = this.objectMapper.writeValueAsString(message);
			URI requestUri = Utils.resolveUri(baseUri, mcpEndpoint);

			HttpRequest.Builder builder = this.requestBuilder.copy()
				.uri(requestUri)
				.POST(HttpRequest.BodyPublishers.ofString(jsonText));
			if (!builder.build().headers().map().containsKey("Accept")) {
				builder.header("Accept", "application/json, text/event-stream");
			}
			if (!builder.build().headers().map().containsKey("Content-Type")) {
				builder.header("Content-Type", "application/json");
			}

			// Add session ID header if available
			String sid = sessionId.get();
			if (sid != null && !builder.build().headers().map().containsKey(SESSION_ID_HEADER)) {
				builder.header(SESSION_ID_HEADER, sid);
			}

			HttpRequest request = builder.build();

			return Mono
				.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
					int statusCode = response.statusCode();

					// Check for session ID in response headers
					String newSessionId = response.headers().firstValue(SESSION_ID_HEADER).orElse(null);
					if (newSessionId != null && sessionId.get() == null) {
						sessionId.set(newSessionId);
						sessionLatch.countDown();
						logger.debug("Session established with ID: {}", newSessionId);

						// Now that we have a session ID, set up the SSE connection
						setupSseConnection();
					}

					// Handle different response status codes according to spec
					if (statusCode == 202) {
						// 202 Accepted - For notifications and responses
						logger.debug("Server accepted the message");
					}
					else if (statusCode == 200) {
						String contentType = response.headers().firstValue("Content-Type").orElse("");
						if (contentType.contains("text/event-stream")) {
							// SSE stream for responses
							logger.debug("Server opened SSE stream for responses");

							// For SSE streams from POST requests, we need to process the
							// response body
							// The actual processing of the SSE stream is handled by the
							// FlowSseClient,
							// which will call our message handler for each event
						}
						else if (contentType.contains("application/json")) {
							// JSON response - for single responses to JSON-RPC requests
							logger.debug("Received JSON response");

							// Process the JSON response
							String responseBody = response.body();
							if (responseBody != null && !responseBody.isEmpty()) {
								try {
									JSONRPCMessage responseMessage = McpSchema.deserializeJsonRpcMessage(objectMapper,
											responseBody);
									Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = messageHandler.get();
									if (handler != null) {
										handler.apply(Mono.just(responseMessage))
											.doOnError(e -> logger.error("Error processing response", e))
											.subscribe(); // This breaks the reactive
															// chain
									}
								}
								catch (Exception e) {
									logger.error("Error processing JSON response", e);
								}
							}
						}
					}
					else if (statusCode == 404 && sid != null) {
						// 404 Not Found - Session expired
						logger.warn("Session {} expired, need to reinitialize", sid);
						sessionId.set(null);
						// Client should reinitialize
					}
					else if (statusCode >= 400) {
						logger.error("Error sending message: {} - {}", statusCode, response.body());
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
	 * @return a Mono that completes when the closing process is initiated
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			isClosing = true;
			lastEventId.set(null);
			CompletableFuture<Void> future = connectionFuture.get();
			if (future != null && !future.isDone()) {
				future.cancel(true);
			}

			// If we have a session ID, send a DELETE request to terminate the session
			// as specified in the Session Management section of the spec
			String sid = sessionId.get();
			if (sid != null) {
				try {
					URI requestUri = Utils.resolveUri(baseUri, mcpEndpoint);
					HttpRequest request = HttpRequest.newBuilder()
						.uri(requestUri)
						.header(SESSION_ID_HEADER, sid)
						.DELETE()
						.build();

					HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

					if (response.statusCode() == 405) { // (Method not allowed)
						logger.debug("Server does not allow clients to terminate sessions");
					}
					else if (response.statusCode() >= 200 && response.statusCode() < 300) {
						logger.debug("Session terminated successfully");
					}
					else {
						logger.warn("Failed to terminate session: HTTP {}", response.statusCode());
					}
				}
				catch (Exception e) {
					logger.warn("Failed to send session termination request", e);
				}
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
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

}