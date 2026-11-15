/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MissingRequiredClientCapabilityData;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.modern.McpSchema.UnsupportedProtocolVersionData;
import io.modelcontextprotocol.modern.McpStdio;
import io.modelcontextprotocol.modern.client.transport.McpClientTransport;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sends one request of a client through its transport: a fresh id, a complete
 * {@code _meta}, the round timeout, progress, and the mapping of error answers. Shared by
 * every call and listen stream of a client.
 */
final class JsonRpcRequestSender {

	private static final Logger logger = LoggerFactory.getLogger(JsonRpcRequestSender.class);

	// Several clients may share one transport, and a transport correlates responses by
	// id and progress by token, so both are unique in the JVM rather than per client.
	private static final AtomicLong NEXT_ID = new AtomicLong();

	private static final AtomicLong NEXT_PROGRESS_TOKEN = new AtomicLong();

	private static final TypeRef<LinkedHashMap<String, Object>> PARAMS_TYPE = new TypeRef<>() {
	};

	private final McpClientTransport transport;

	private final McpJsonMapper jsonMapper;

	@Nullable private final Implementation clientInfo;

	private final List<String> protocolVersions;

	// The version the server accepts is a fact about the server, so it is kept once
	// renegotiated and used by every later call.
	private volatile String protocolVersion;

	JsonRpcRequestSender(McpClientTransport transport, McpJsonMapper jsonMapper, @Nullable Implementation clientInfo,
			List<String> protocolVersions) {
		this.transport = transport;
		this.jsonMapper = jsonMapper;
		this.clientInfo = clientInfo;
		this.protocolVersions = List.copyOf(protocolVersions);
		this.protocolVersion = this.protocolVersions.get(0);
	}

	McpJsonMapper jsonMapper() {
		return this.jsonMapper;
	}

	/**
	 * {@code params}, a schema record or a map, as the params of a request.
	 */
	Map<String, Object> params(Object params) {
		return Collections.unmodifiableMap(this.jsonMapper.convertValue(params, PARAMS_TYPE));
	}

	String protocolVersion() {
		return this.protocolVersion;
	}

	/**
	 * Sends one request and emits its raw result, or fails with the server's error as an
	 * {@link McpException}. A {@code -32022} answer is retried once with the newest
	 * version both sides support.
	 * @param name the name or URI the request targets, required for the named methods
	 * @param params the request's params without {@code _meta}
	 * @param applicationMeta entries added to the request's {@code _meta}
	 * @param paramHeaders the {@code Mcp-Param-{Name}} values of a {@code tools/call},
	 * keyed by {@code {Name}} and not encoded; empty for other methods
	 * @param roundTimeout the timeout the transport applies to each attempt, or
	 * {@code null} for none
	 * @param progress receives the request's progress, one notification at a time and in
	 * order, before the result is emitted; a progress token is sent exactly when it is
	 * present
	 */
	Mono<Object> send(String method, @Nullable String name, Map<String, Object> params,
			Map<String, Object> applicationMeta, Map<String, String> paramHeaders, ClientCapabilities capabilities,
			@Nullable Duration roundTimeout, @Nullable Function<ProgressParams, Mono<Void>> progress) {
		return renegotiated(version -> exchange(method, name, params, applicationMeta, paramHeaders, capabilities,
				roundTimeout, progress, version))
			.next();
	}

	private Flux<Object> exchange(String method, @Nullable String name, Map<String, Object> params,
			Map<String, Object> applicationMeta, Map<String, String> paramHeaders, ClientCapabilities capabilities,
			@Nullable Duration roundTimeout, @Nullable Function<ProgressParams, Mono<Void>> progress, String version) {
		return Flux.defer(() -> {
			long progressToken = NEXT_PROGRESS_TOKEN.incrementAndGet();
			JSONRPCRequest message = message(method, params, applicationMeta, capabilities,
					progress == null ? null : progressToken, version);
			McpTransportRequest request = McpTransportRequest.builder(message, version)
				.name(name)
				.paramHeaders(paramHeaders)
				.roundTimeout(roundTimeout)
				.build();
			Flux<JSONRPCMessage> received = this.transport.exchange(request);
			if (progress != null) {
				// concatMap waits for each listener to finish, so the notifications reach
				// it in order, and the response is read only after all of them.
				received = received.concatMap(m -> deliverProgress(m, progressToken, progress));
			}
			return received.ofType(JSONRPCResponse.class)
				.next()
				.switchIfEmpty(Mono.error(() -> new McpResponseLostException(method,
						new IllegalStateException("The exchange completed without a response"))))
				.flatMap(response -> response.error() == null ? Mono.just(response.result())
						: Mono.error(toException(response.error())));
		});
	}

	/**
	 * Opens a {@code subscriptions/listen} stream and emits its notifications. It
	 * completes when the server answers with a result and fails with the server's error
	 * as an {@link McpException}; there is no timeout. A stream that breaks or ends
	 * without an answer fails with {@link McpSubscriptionLostException}. A {@code -32022}
	 * answer is retried once, as by {@link #send}.
	 * @param params the request's params without {@code _meta}
	 * @param applicationMeta entries added to the request's {@code _meta}
	 */
	Flux<JSONRPCNotification> listen(Map<String, Object> params, Map<String, Object> applicationMeta,
			ClientCapabilities capabilities) {
		return renegotiated(version -> listen(params, applicationMeta, capabilities, version));
	}

	/**
	 * Runs {@code exchange} with the current version; a {@code -32022} answer is retried
	 * once with the newest version both sides support, which is kept for later requests.
	 */
	private <T> Flux<T> renegotiated(Function<String, Flux<T>> exchange) {
		return Flux.defer(() -> exchange.apply(this.protocolVersion).onErrorResume(UnsupportedVersion.class, ex -> {
			String retryVersion = negotiate(ex.error);
			if (retryVersion == null) {
				return Flux.error(new McpException(ex.error));
			}
			this.protocolVersion = retryVersion;
			return exchange.apply(retryVersion)
				.onErrorMap(UnsupportedVersion.class, again -> new McpException(again.error));
		}));
	}

	private Flux<JSONRPCNotification> listen(Map<String, Object> params, Map<String, Object> applicationMeta,
			ClientCapabilities capabilities, String version) {
		return Flux.defer(() -> {
			JSONRPCRequest message = message(McpSchema.METHOD_SUBSCRIPTIONS_LISTEN, params, applicationMeta,
					capabilities, null, version);
			return this.transport.exchange(McpTransportRequest.builder(message, version).build())
				// Reached only when the exchange completes before the answer; the answer
				// itself completes the stream below and cancels the exchange.
				.concatWith(Flux.error(() -> new McpSubscriptionLostException(
						new IllegalStateException("The stream ended without a response"))))
				.onErrorMap(McpResponseLostException.class, McpSubscriptionLostException::new)
				.<JSONRPCNotification>handle((received, sink) -> {
					if (received instanceof JSONRPCNotification notification) {
						sink.next(notification);
					}
					else if (received instanceof JSONRPCResponse response) {
						if (response.error() == null) {
							sink.complete();
						}
						else {
							sink.error(toException(response.error()));
						}
					}
				});
		});
	}

	private JSONRPCRequest message(String method, Map<String, Object> params, Map<String, Object> applicationMeta,
			ClientCapabilities capabilities, @Nullable Long progressToken, String version) {
		Map<String, Object> meta = RequestMeta.merged(applicationMeta,
				RequestMeta.of(version, capabilities, this.clientInfo, progressToken));
		return new JSONRPCRequest(method, NEXT_ID.incrementAndGet(), withMeta(params, meta));
	}

	private static Map<String, Object> withMeta(Map<String, Object> params, Map<String, Object> meta) {
		Map<String, Object> withMeta = new LinkedHashMap<>(params);
		withMeta.put("_meta", meta);
		return Collections.unmodifiableMap(withMeta);
	}

	/**
	 * Passes {@code message} to {@code progress} when it is progress for
	 * {@code progressToken} and emits nothing then; emits any other message.
	 */
	private Mono<JSONRPCMessage> deliverProgress(JSONRPCMessage message, long progressToken,
			Function<ProgressParams, Mono<Void>> progress) {
		if (!(message instanceof JSONRPCNotification notification)
				|| !McpSchema.METHOD_NOTIFICATION_PROGRESS.equals(notification.method())) {
			return Mono.just(message);
		}
		ProgressParams params;
		try {
			params = this.jsonMapper.convertValue(notification.params(), ProgressParams.class);
		}
		catch (RuntimeException ex) {
			logger.debug("Ignoring malformed progress notification: {}", notification.params(), ex);
			return Mono.empty();
		}
		if (!McpStdio.idKey(progressToken).equals(McpStdio.idKey(params.progressToken()))) {
			logger.debug("Ignoring progress for another token: {}", params.progressToken());
			return Mono.empty();
		}
		return Mono.defer(() -> progress.apply(params)).onErrorResume(ex -> {
			logger.warn("Progress listener failed", ex);
			return Mono.empty();
		}).then(Mono.empty());
	}

	private RuntimeException toException(JSONRPCError error) {
		if (error.code() == ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION) {
			return new UnsupportedVersion(error);
		}
		if (error.code() == ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY) {
			return new McpMissingClientCapabilityException(error, requiredCapabilities(error));
		}
		return new McpException(error);
	}

	@Nullable private ClientCapabilities requiredCapabilities(JSONRPCError error) {
		if (error.data() == null) {
			return null;
		}
		try {
			return this.jsonMapper.convertValue(error.data(), MissingRequiredClientCapabilityData.class)
				.requiredCapabilities();
		}
		catch (RuntimeException ex) {
			logger.debug("Ignoring malformed data of a -32021 error: {}", error.data(), ex);
			return null;
		}
	}

	/**
	 * The newest of the client's versions the server lists as supported, or {@code null}
	 * if there is none. It may be the version the server rejected; the request is retried
	 * once either way.
	 */
	@Nullable private String negotiate(JSONRPCError error) {
		List<String> supported;
		try {
			UnsupportedProtocolVersionData data = this.jsonMapper.convertValue(error.data(),
					UnsupportedProtocolVersionData.class);
			supported = data == null ? null : data.supported();
		}
		catch (RuntimeException ex) {
			logger.debug("Ignoring malformed data of a -32022 error: {}", error.data(), ex);
			return null;
		}
		if (supported == null) {
			return null;
		}
		return this.protocolVersions.stream().filter(supported::contains).findFirst().orElse(null);
	}

	/**
	 * A {@code -32022} answer, caught by {@link #send} to renegotiate the version.
	 */
	private static final class UnsupportedVersion extends RuntimeException {

		private final transient JSONRPCError error;

		UnsupportedVersion(JSONRPCError error) {
			super(error.message(), null, false, false);
			this.error = error;
		}

	}

}
