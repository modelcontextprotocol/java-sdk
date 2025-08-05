/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.util.Assert;

import org.springframework.web.reactive.function.client.WebClient;

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
 * This implementation uses {@link WebClient} for HTTP communications and supports JSON
 * serialization/deserialization of messages.
 *
 * @author Christian Tzolov
 * @author Yanming Zhou
 * @see <a href=
 * "https://spec.modelcontextprotocol.io/specification/basic/transports/#http-with-sse">MCP
 * HTTP with SSE Transport Specification</a>
 */
public class WebClientSseClientTransport extends InternalWebClientSseClientTransport {

	/**
	 * Constructs a new SseClientTransport with the specified WebClient builder. Uses a
	 * default ObjectMapper instance for JSON processing.
	 * @param webClientBuilder the WebClient.Builder to use for creating the WebClient
	 * instance
	 * @throws IllegalArgumentException if webClientBuilder is null
	 */
	public WebClientSseClientTransport(WebClient.Builder webClientBuilder) {
		super(webClientBuilder);
	}

	/**
	 * Constructs a new SseClientTransport with the specified WebClient builder and
	 * ObjectMapper. Initializes both inbound and outbound message processing pipelines.
	 * @param webClientBuilder the WebClient.Builder to use for creating the WebClient
	 * instance
	 * @param objectMapper the ObjectMapper to use for JSON processing
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebClientSseClientTransport(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
		super(webClientBuilder, objectMapper);
	}

	/**
	 * Constructs a new SseClientTransport with the specified WebClient builder and
	 * ObjectMapper. Initializes both inbound and outbound message processing pipelines.
	 * @param webClientBuilder the WebClient.Builder to use for creating the WebClient
	 * instance
	 * @param objectMapper the ObjectMapper to use for JSON processing
	 * @param sseEndpoint the SSE endpoint URI to use for establishing the connection
	 * @throws IllegalArgumentException if either parameter is null
	 */
	public WebClientSseClientTransport(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
			String sseEndpoint) {
		super(webClientBuilder, objectMapper, sseEndpoint);
	}

	/**
	 * Creates a new builder for {@link WebClientSseClientTransport}.
	 * @param webClientBuilder the WebClient.Builder to use for creating the WebClient
	 * instance
	 * @return a new builder instance
	 */
	public static Builder builder(WebClient.Builder webClientBuilder) {
		return new Builder(webClientBuilder);
	}

	/**
	 * Builder for {@link WebClientSseClientTransport}.
	 */
	public static class Builder {

		private final WebClient.Builder webClientBuilder;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		private ObjectMapper objectMapper = new ObjectMapper();

		/**
		 * Creates a new builder with the specified WebClient.Builder.
		 * @param webClientBuilder the WebClient.Builder to use
		 */
		public Builder(WebClient.Builder webClientBuilder) {
			Assert.notNull(webClientBuilder, "WebClient.Builder must not be null");
			this.webClientBuilder = webClientBuilder;
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
		 * Builds a new {@link WebClientSseClientTransport} instance.
		 * @return a new transport instance
		 */
		public WebClientSseClientTransport build() {
			return new WebClientSseClientTransport(webClientBuilder, objectMapper, sseEndpoint);
		}

	}

}
