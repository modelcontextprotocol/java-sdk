/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.modelcontextprotocol.client.transport.ResponseBodyHandlers;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpHttp;
import io.modelcontextprotocol.modern.McpHttp.Headers;
import io.modelcontextprotocol.modern.McpHttp.MediaTypes;
import io.modelcontextprotocol.modern.client.McpResponseLostException;
import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Streamable HTTP transport of the 2026-07-28 revision, on the JDK
 * {@link HttpClient}. Every request is a POST to the MCP endpoint, answered with one JSON
 * response or an event stream scoped to the request. Waiting for an answer holds no
 * thread.
 * <p>
 * Each request carries the headers the revision requires, derived with
 * {@link McpHttpRequestHeaders#forRequest}, and then those the
 * {@link McpHttpRequestCustomizer}s add. A {@code 401} or {@code 403} answer is passed to
 * the {@link McpHttpAuthorizationErrorHandler}. An HTTP error whose body is a JSON-RPC
 * error, such as a {@code 400} with {@code -32022}, is emitted as that error response.
 * <p>
 * The transport mirrors tool arguments into {@code Mcp-Param-*} headers.
 * <p>
 * The {@link McpTransportRequest#roundTimeout() round timeout} bounds each HTTP attempt,
 * from sending the request until the response arrives; the customizers before an attempt
 * and the authorization error handler between attempts don't count.
 *
 * @author Dariusz Jędrzejczyk
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http">Streamable
 * HTTP</a>
 */
public final class HttpClientMcpTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(HttpClientMcpTransport.class);

	private static final int DEFAULT_MAX_RESPONSE_SIZE = 16 * 1024 * 1024;

	private final URI endpoint;

	private final HttpClient httpClient;

	private final McpJsonMapper jsonMapper;

	private final List<McpHttpRequestCustomizer> requestCustomizers;

	@Nullable private final McpHttpAuthorizationErrorHandler authorizationErrorHandler;

	private final int maxResponseSize;

	private HttpClientMcpTransport(Builder builder) {
		this.endpoint = builder.endpoint;
		this.httpClient = builder.clientBuilder.connectTimeout(builder.connectTimeout).build();
		this.jsonMapper = builder.jsonMapper != null ? builder.jsonMapper : McpJsonDefaults.getMapper();
		this.requestCustomizers = List.copyOf(builder.requestCustomizers);
		this.authorizationErrorHandler = builder.authorizationErrorHandler;
		this.maxResponseSize = builder.maxResponseSize;
	}

	/**
	 * A builder for a transport that posts to the given MCP endpoint, such as
	 * {@code https://example.com/mcp}.
	 * @param endpoint the MCP endpoint
	 * @return the builder
	 */
	public static Builder builder(String endpoint) {
		Assert.hasText(endpoint, "endpoint must not be empty");
		return new Builder(URI.create(endpoint));
	}

	/**
	 * A builder for a transport that posts to the given MCP endpoint.
	 * @param endpoint the MCP endpoint
	 * @return the builder
	 */
	public static Builder builder(URI endpoint) {
		Assert.notNull(endpoint, "endpoint must not be null");
		return new Builder(endpoint);
	}

	@Override
	public boolean mirrorsToolParams() {
		return true;
	}

	@Override
	public Flux<JSONRPCMessage> exchange(McpTransportRequest request) {
		Assert.notNull(request, "request must not be null");
		return Flux.deferContextual(ctx -> {
			McpTransportContext context = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
			String body;
			try {
				body = this.jsonMapper.writeValueAsString(request.message());
			}
			catch (IOException ex) {
				return Flux.error(new IllegalArgumentException("Request can't be serialized", ex));
			}
			return doExchange(request, body, context, 0);
		});
	}

	private Flux<JSONRPCMessage> doExchange(McpTransportRequest request, String body, McpTransportContext context,
			int retries) {
		McpHttpRequestHeaders headers = McpHttpRequestHeaders.forRequest(request);
		McpHttpRequest toCustomize = McpHttpRequest.builder(this.endpoint, request, body)
			.headers(headers.asHttpHeaders())
			.build();

		return Flux.fromIterable(this.requestCustomizers)
			.concatMap(customizer -> customizer.customize(toCustomize, headers, context))
			.then(Mono.fromCallable(() -> {
				HttpRequest.Builder builder = HttpRequest.newBuilder(this.endpoint)
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
				headers.forEach(builder::header);
				return builder.build();
			}))
			.flatMapMany(httpRequest -> {
				McpHttpRequest sent = McpHttpRequest.builder(this.endpoint, request, body)
					.headers(httpRequest.headers())
					.build();
				Flux<JSONRPCMessage> answer = ResponseBodyHandlers.sendAsync(this.httpClient, httpRequest)
					.onErrorMap(ex -> errorWhileSending(request.method(), ex))
					.flatMapMany(response -> {
						int status = response.statusCode();
						if (status == HttpURLConnection.HTTP_UNAUTHORIZED
								|| status == HttpURLConnection.HTTP_FORBIDDEN) {
							return errorBody(response)
								.flatMapMany(text -> Flux.error(new AuthorizationFailure(response, text)));
						}
						if (status < 200 || status >= 300) {
							return errorBody(response).flatMapMany(text -> statusError(response, text));
						}
						return messages(request.method(), response);
					});
				return request.withRoundTimeout(answer)
					.onErrorResume(AuthorizationFailure.class, failure -> authorizationError(request, body, context,
							retries, sent, failure.response, failure.body));
			});
	}

	private Flux<JSONRPCMessage> authorizationError(McpTransportRequest request, String body,
			McpTransportContext context, int retries, McpHttpRequest sent, HttpResponse<?> response, String text) {
		McpHttpAuthorizationErrorHandler handler = this.authorizationErrorHandler;
		McpHttpStatusException failure = new McpHttpStatusException(this.endpoint, response.statusCode(),
				response.headers(), text);
		if (handler == null || retries >= handler.maxRetries()) {
			return Flux.error(failure);
		}
		McpHttpResponseInfo info = McpHttpResponseInfo.of(response.statusCode(), response.headers());
		return handler.handle(sent, info, context).defaultIfEmpty(false).flatMapMany(retry -> {
			if (retry) {
				logger.debug("Retrying {} after HTTP {}", request.method(), response.statusCode());
				return doExchange(request, body, context, retries + 1);
			}
			return Flux.error(failure);
		});
	}

	private Flux<JSONRPCMessage> statusError(HttpResponse<?> response, String text) {
		// Modern servers answer protocol errors (unsupported version, header mismatch,
		// unknown method, ...) with an error status and a JSON-RPC error body.
		if (isContentType(response, MediaTypes.APPLICATION_JSON)) {
			try {
				if (JsonRpc.deserializeMessage(this.jsonMapper, text) instanceof JSONRPCResponse rpc
						&& rpc.error() != null) {
					return Flux.just(rpc);
				}
			}
			catch (IOException | IllegalArgumentException ex) {
				logger.debug("HTTP {} body is not a JSON-RPC error: {}", response.statusCode(), ex.getMessage());
			}
		}
		return Flux.error(new McpHttpStatusException(this.endpoint, response.statusCode(), response.headers(), text));
	}

	private Mono<String> errorBody(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
		return ResponseBodyHandlers.decodeAggregateResponse(response.body(), this.maxResponseSize).onErrorReturn("");
	}

	private Flux<JSONRPCMessage> messages(String method, HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
		Flux<JSONRPCMessage> messages;
		if (isContentType(response, MediaTypes.TEXT_EVENT_STREAM)) {
			messages = eventStream(response);
		}
		else if (isContentType(response, MediaTypes.APPLICATION_JSON)) {
			messages = ResponseBodyHandlers.decodeAggregateResponse(response.body(), this.maxResponseSize)
				.map(this::parse)
				.flux();
		}
		else {
			String contentType = response.headers().firstValue(Headers.CONTENT_TYPE).orElse("none");
			// Cancelled, not read: a body of unknown type might never end.
			messages = ResponseBodyHandlers.<JSONRPCMessage>cancel(response.body())
				.concatWith(Flux.error(new IOException("Unexpected Content-Type " + contentType + " for HTTP "
						+ response.statusCode() + " answering a request")));
		}
		return untilResponse(messages.filter(message -> {
			// Servers of this revision send no requests; input is asked for in results.
			if (message instanceof JSONRPCRequest rpc) {
				logger.warn("Ignoring request {} sent by the server", rpc.method());
				return false;
			}
			return true;
		})).onErrorMap(ex -> !(ex instanceof McpResponseLostException), ex -> new McpResponseLostException(method, ex));
	}

	private Flux<JSONRPCMessage> eventStream(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
		// An event with empty data primes the stream; it carries no message, and
		// decodeSseData skips it.
		return ResponseBodyHandlers.decodeSseData(response.body(), this.maxResponseSize, McpHttp.MESSAGE_EVENT)
			.map(this::parse);
	}

	// Emits the messages up to the response, then completes, which cancels the body and
	// releases the connection. Ending before the response loses it.
	private static Flux<JSONRPCMessage> untilResponse(Flux<JSONRPCMessage> messages) {
		return Flux.defer(() -> {
			AtomicBoolean answered = new AtomicBoolean();
			return messages.takeUntil(message -> {
				if (message instanceof JSONRPCResponse) {
					answered.set(true);
					return true;
				}
				return false;
			})
				.concatWith(Mono.defer(() -> answered.get() ? Mono.empty()
						: Mono.error(new IOException("The response stream ended without a response"))));
		});
	}

	private JSONRPCMessage parse(String json) {
		try {
			return JsonRpc.deserializeMessage(this.jsonMapper, json);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Malformed JSON from the server", ex);
		}
	}

	private static boolean isContentType(HttpResponse<?> response, String mediaType) {
		return response.headers()
			.firstValue(Headers.CONTENT_TYPE)
			.map(value -> value.toLowerCase(Locale.ROOT).contains(mediaType))
			.orElse(false);
	}

	// A connection that was never established didn't carry the request, so the request
	// wasn't processed. Any other I/O failure may have happened after the server
	// received it.
	private static Throwable errorWhileSending(String method, Throwable ex) {
		if (ex instanceof ConnectException || ex instanceof HttpConnectTimeoutException
				|| !(ex instanceof IOException)) {
			return ex;
		}
		return new McpResponseLostException(method, ex);
	}

	/**
	 * A {@code 401} or {@code 403} answer, carried out of the timed part of an attempt to
	 * the authorization error handler.
	 */
	private static final class AuthorizationFailure extends RuntimeException {

		private final transient HttpResponse<?> response;

		private final String body;

		AuthorizationFailure(HttpResponse<?> response, String body) {
			super("HTTP " + response.statusCode(), null, false, false);
			this.response = response;
			this.body = body;
		}

	}

	/**
	 * Builds an {@link HttpClientMcpTransport}. Building does no I/O.
	 */
	public static final class Builder {

		private final URI endpoint;

		private HttpClient.Builder clientBuilder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);

		private Duration connectTimeout = Duration.ofSeconds(10);

		private McpJsonMapper jsonMapper;

		private final List<McpHttpRequestCustomizer> requestCustomizers = new ArrayList<>();

		@Nullable private McpHttpAuthorizationErrorHandler authorizationErrorHandler;

		private int maxResponseSize = DEFAULT_MAX_RESPONSE_SIZE;

		private Builder(URI endpoint) {
			this.endpoint = endpoint;
		}

		/**
		 * Replaces the builder of the {@link HttpClient}. It uses HTTP/1.1 by default.
		 */
		public Builder clientBuilder(HttpClient.Builder clientBuilder) {
			Assert.notNull(clientBuilder, "clientBuilder must not be null");
			this.clientBuilder = clientBuilder;
			return this;
		}

		/**
		 * Customizes the builder of the {@link HttpClient}, for example its executor or
		 * SSL context.
		 */
		public Builder customizeClient(Consumer<HttpClient.Builder> clientCustomizer) {
			Assert.notNull(clientCustomizer, "clientCustomizer must not be null");
			clientCustomizer.accept(this.clientBuilder);
			return this;
		}

		/**
		 * How long establishing a connection may take; 10 seconds by default.
		 */
		public Builder connectTimeout(Duration connectTimeout) {
			Assert.notNull(connectTimeout, "connectTimeout must not be null");
			this.connectTimeout = connectTimeout;
			return this;
		}

		/**
		 * The JSON mapper; {@link McpJsonDefaults#getMapper()} by default.
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "jsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Adds a request customizer, called after those added before it.
		 */
		public Builder requestCustomizer(McpHttpRequestCustomizer requestCustomizer) {
			Assert.notNull(requestCustomizer, "requestCustomizer must not be null");
			this.requestCustomizers.add(requestCustomizer);
			return this;
		}

		/**
		 * Decides whether a request answered with {@code 401} or {@code 403} is sent
		 * again, replacing the handler set before. Without one, such an answer fails the
		 * exchange with {@link McpHttpStatusException}.
		 */
		public Builder authorizationErrorHandler(McpHttpAuthorizationErrorHandler authorizationErrorHandler) {
			Assert.notNull(authorizationErrorHandler, "authorizationErrorHandler must not be null");
			this.authorizationErrorHandler = authorizationErrorHandler;
			return this;
		}

		/**
		 * The maximum number of bytes read for one response body or one streamed event;
		 * 16 MiB by default.
		 */
		public Builder maxResponseSize(int maxResponseSize) {
			Assert.isTrue(maxResponseSize > 0, "maxResponseSize must be positive");
			this.maxResponseSize = maxResponseSize;
			return this;
		}

		public HttpClientMcpTransport build() {
			return new HttpClientMcpTransport(this);
		}

	}

}
