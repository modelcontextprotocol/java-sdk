/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CacheableResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.InputRequired;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.McpSchema.UnsupportedProtocolVersionData;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A stateless, immutable dispatcher that hands each request to the {@link McpFeature}
 * serving its method. It validates {@code _meta}, negotiates the version, answers
 * {@code server/discover}, stamps {@code serverInfo} and seals MRTR {@code requestState}.
 * An {@link McpException} is answered with its error; any other exception with an
 * internal error.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpServer implements McpRequestManager {

	private static final Logger logger = LoggerFactory.getLogger(McpServer.class);

	private static final TypeRef<Map<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
	};

	private final Implementation serverInfo;

	private final List<String> supportedVersions;

	private final McpJsonMapper jsonMapper;

	private final Map<String, McpFeature> features;

	private final Set<String> inputRequiredMethods;

	private final RequestStateCodec requestStateCodec;

	private final SubscriptionsFeature subscriptionsFeature;

	private McpServer(Implementation serverInfo, List<String> supportedVersions, McpJsonMapper jsonMapper,
			Map<String, McpFeature> features, Set<String> inputRequiredMethods, RequestStateCodec requestStateCodec,
			SubscriptionsFeature subscriptionsFeature) {
		this.serverInfo = serverInfo;
		this.supportedVersions = supportedVersions;
		this.jsonMapper = jsonMapper;
		this.features = features;
		this.inputRequiredMethods = inputRequiredMethods;
		this.requestStateCodec = requestStateCodec;
		this.subscriptionsFeature = subscriptionsFeature;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Ends every active {@code subscriptions/listen} stream with a graceful
	 * {@code complete} result; streams opened afterwards end right after their
	 * acknowledgment. A no-op if no {@link McpChangePublisher} was registered.
	 */
	public void closeGracefully() {
		if (this.subscriptionsFeature != null) {
			this.subscriptionsFeature.closeGracefully();
		}
	}

	@Override
	public Mono<McpTransportResponse> handle(McpTransportContext transportContext, JSONRPCRequest request) {
		return handle(transportContext, request, false);
	}

	@Override
	public Mono<McpTransportResponse> handleBlocking(McpTransportContext transportContext, JSONRPCRequest request) {
		return handle(transportContext, request, true);
	}

	@Override
	public Mono<Void> handleNotification(McpTransportContext transportContext, JSONRPCNotification notification) {
		// The only modern client notification is notifications/cancelled, which
		// transports handle directly against their own in-flight bookkeeping.
		return Mono.empty();
	}

	private Mono<McpTransportResponse> handle(McpTransportContext transportContext, JSONRPCRequest request,
			boolean blocking) {
		return Mono.defer(() -> dispatch(transportContext, request, blocking))
			.onErrorResume(err -> Mono.just(McpTransportResponse.error(errorResponse(request.id(), err))));
	}

	private Mono<McpTransportResponse> dispatch(McpTransportContext transportContext, JSONRPCRequest request,
			boolean blocking) {
		Object id = request.id();
		if (request.params() == null) {
			return invalidParams(id, "params is required");
		}
		Map<String, Object> paramsMap = convert(request.params(), MAP_TYPE_REF);
		if (paramsMap == null) {
			return invalidParams(id, "params is malformed");
		}
		Map<String, Object> meta = asMetaMap(paramsMap.get("_meta"));
		if (meta == null) {
			return invalidParams(id, "params._meta is required");
		}
		Object versionRaw = meta.get(MetaKeys.PROTOCOL_VERSION);
		if (!(versionRaw instanceof String protocolVersion) || protocolVersion.isBlank()) {
			return invalidParams(id, "_meta['" + MetaKeys.PROTOCOL_VERSION + "'] is required");
		}
		// Checked before the rest of _meta: a client on another revision may shape it
		// differently, and must still learn which versions this server supports.
		if (!this.supportedVersions.contains(protocolVersion)) {
			return error(id, new JSONRPCError(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version",
					new UnsupportedProtocolVersionData(this.supportedVersions, protocolVersion)));
		}
		Object capabilitiesRaw = meta.get(MetaKeys.CLIENT_CAPABILITIES);
		if (capabilitiesRaw == null) {
			return invalidParams(id, "_meta['" + MetaKeys.CLIENT_CAPABILITIES + "'] is required");
		}
		McpFeature feature = this.features.get(request.method());
		if (feature == null) {
			return error(id, new JSONRPCError(ErrorCodes.METHOD_NOT_FOUND, "Method not found: " + request.method()));
		}
		ClientCapabilities clientCapabilities = convert(capabilitiesRaw, ClientCapabilities.class);
		if (clientCapabilities == null) {
			return invalidParams(id, "_meta['" + MetaKeys.CLIENT_CAPABILITIES + "'] is malformed");
		}
		Object clientInfoRaw = meta.get(MetaKeys.CLIENT_INFO);
		Implementation clientInfo = clientInfoRaw == null ? null : convert(clientInfoRaw, Implementation.class);
		if (clientInfoRaw != null && clientInfo == null) {
			return invalidParams(id, "_meta['" + MetaKeys.CLIENT_INFO + "'] is malformed");
		}
		Object progressToken = meta.get(MetaKeys.PROGRESS_TOKEN);
		boolean retry = paramsMap.get("inputResponses") != null || paramsMap.get("requestState") != null;

		McpRequestContext ctx = new McpRequestContext(id, request.method(), protocolVersion, clientCapabilities,
				clientInfo, progressToken, extractPrimitiveName(request.method(), paramsMap), meta, transportContext,
				retry, blocking);

		Map<String, Object> params = paramsMap;
		Object inputResponses = paramsMap.get("inputResponses");
		if (inputResponses != null && this.inputRequiredMethods.contains(request.method())) {
			// Each value is the client's result for one input request, so it must be an
			// object. Typed parsing is left to the handler (see InputResponses).
			if (!(inputResponses instanceof Map<?, ?> responses)) {
				return invalidParams(id, "inputResponses must be an object");
			}
			for (Map.Entry<?, ?> entry : responses.entrySet()) {
				if (!(entry.getValue() instanceof Map<?, ?>)) {
					return invalidParams(id, "inputResponses['" + entry.getKey() + "'] must be an object");
				}
			}
		}
		Object requestState = paramsMap.get("requestState");
		if (requestState != null && this.inputRequiredMethods.contains(request.method())) {
			// Anything but a verified sealed string must not reach the handler.
			if (!(requestState instanceof String sealed)) {
				return invalidParams(id, "requestState must be a string");
			}
			Optional<String> opened = this.requestStateCodec.open(ctx, sealed);
			if (opened.isEmpty()) {
				return invalidParams(id, "Invalid or expired requestState");
			}
			params = new LinkedHashMap<>(paramsMap);
			params.put("requestState", opened.get());
		}
		return feature.handle(ctx, params)
			.map(response -> toTransportResponse(ctx, response))
			.switchIfEmpty(Mono
				.fromSupplier(() -> McpTransportResponse.error(bug(ctx, "Feature completed without a response"))));
	}

	private McpTransportResponse toTransportResponse(McpRequestContext ctx,
			McpAsyncResponse<? extends Result> response) {
		if (response instanceof McpAsyncResponse.Streaming<? extends Result> streaming) {
			return McpTransportResponse.streaming(stream(ctx, streaming.body()));
		}
		JSONRPCResponse jsonRpcResponse = resultResponse(ctx,
				((McpAsyncResponse.Result<? extends Result>) response).result());
		return jsonRpcResponse.error() != null ? McpTransportResponse.error(jsonRpcResponse)
				: McpTransportResponse.result(jsonRpcResponse);
	}

	private Flux<JSONRPCMessage> stream(McpRequestContext ctx, McpAsyncResponse.Body<? extends Result> body) {
		// Deferred: the body must not run before the transport has started the stream.
		return Flux.defer(() -> {
			Sinks.Many<JSONRPCNotification> sink = Sinks.many().unicast().onBackpressureBuffer();
			DefaultAsyncNotifier notifier = new DefaultAsyncNotifier(ctx, sink);
			Mono<JSONRPCMessage> terminal = Mono.defer(() -> body.run(notifier))
				.map(result -> (JSONRPCMessage) resultResponse(ctx, result))
				.switchIfEmpty(Mono.fromSupplier(() -> bug(ctx, "Streaming body completed without a result")))
				// The stream has already started, so a failure can only be reported
				// in-band.
				.onErrorResume(err -> Mono.just(errorResponse(ctx.requestId(), err)))
				.doFinally(signal -> sink.tryEmitComplete());
			return Flux.merge(sink.asFlux(), terminal.flux());
		});
	}

	private JSONRPCResponse resultResponse(McpRequestContext ctx, Result result) {
		if (result.resultType() == null || result.resultType().isBlank()) {
			return bug(ctx, "Result has no resultType");
		}
		if (result instanceof InputRequired && !this.inputRequiredMethods.contains(ctx.method())) {
			return bug(ctx, "Method does not support input-required results");
		}
		Map<String, Object> resultMap = new LinkedHashMap<>(this.jsonMapper.convertValue(result, MAP_TYPE_REF));
		Map<String, Object> resultMeta = new LinkedHashMap<>();
		if (resultMap.get("_meta") instanceof Map<?, ?> existingMeta) {
			existingMeta.forEach((k, v) -> resultMeta.put(String.valueOf(k), v));
		}
		resultMeta.put(MetaKeys.SERVER_INFO, this.serverInfo);
		resultMap.put("_meta", resultMeta);
		if (result instanceof InputRequired inputRequired && inputRequired.requestState() != null) {
			resultMap.put("requestState", this.requestStateCodec.seal(ctx, inputRequired.requestState()));
		}
		// A retry's result depends on inputs outside the cache key. The hints stay
		// (they are required) but mark it uncacheable.
		if (ctx.isRetry() && result instanceof CacheableResult) {
			resultMap.put("ttlMs", 0L);
			resultMap.put("cacheScope", this.jsonMapper.convertValue(CacheScope.PRIVATE, String.class));
		}
		return JSONRPCResponse.result(ctx.requestId(), resultMap);
	}

	private static JSONRPCResponse errorResponse(Object id, Throwable throwable) {
		if (throwable instanceof McpException mcpException) {
			logger.debug("Request {} failed: {}", id, mcpException.getMessage());
			return JSONRPCResponse.error(id, mcpException.error());
		}
		// Any other exception is a bug. Its message may expose internals, so it stays
		// server-side.
		logger.warn("Unhandled exception while handling request {}", id, throwable);
		return JSONRPCResponse.error(id, new JSONRPCError(ErrorCodes.INTERNAL_ERROR, "Internal error"));
	}

	private static JSONRPCResponse bug(McpRequestContext ctx, String problem) {
		logger.warn("Request {} ({}): {}", ctx.requestId(), ctx.method(), problem);
		return JSONRPCResponse.error(ctx.requestId(), new JSONRPCError(ErrorCodes.INTERNAL_ERROR, "Internal error"));
	}

	private static Mono<McpTransportResponse> error(Object id, JSONRPCError error) {
		return Mono.just(McpTransportResponse.error(JSONRPCResponse.error(id, error)));
	}

	private static Mono<McpTransportResponse> invalidParams(Object id, String message) {
		return error(id, new JSONRPCError(ErrorCodes.INVALID_PARAMS, message));
	}

	@SuppressWarnings("unchecked")
	private <T> T convert(Object raw, TypeRef<T> type) {
		if (raw instanceof Map<?, ?> map) {
			return (T) map;
		}
		try {
			return this.jsonMapper.convertValue(raw, type);
		}
		catch (RuntimeException ex) {
			logger.debug("Malformed request value", ex);
			return null;
		}
	}

	private <T> T convert(Object raw, Class<T> type) {
		try {
			return this.jsonMapper.convertValue(raw, type);
		}
		catch (RuntimeException ex) {
			logger.debug("Malformed request value", ex);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMetaMap(Object metaRaw) {
		if (metaRaw instanceof Map<?, ?> m) {
			return (Map<String, Object>) m;
		}
		return null;
	}

	private static String extractPrimitiveName(String method, Map<String, Object> paramsMap) {
		// Keyed by method: a stray "name" on resources/read must not stand in for its
		// uri, or state sealed for one resource would open for another.
		String key = switch (method) {
			case McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_PROMPTS_GET -> "name";
			case McpSchema.METHOD_RESOURCES_READ -> "uri";
			default -> null;
		};
		return key != null && paramsMap.get(key) instanceof String s ? s : null;
	}

	/**
	 * Builds an immutable {@link McpServer} from registered features.
	 */
	public static final class Builder {

		private Implementation serverInfo;

		private String instructions;

		private List<String> supportedVersions = List.of(McpSchema.LATEST_PROTOCOL_VERSION);

		private McpJsonMapper jsonMapper;

		private final List<McpFeature> features = new ArrayList<>();

		private long discoverTtlMs = 0L;

		private CacheScope discoverCacheScope = CacheScope.PRIVATE;

		private RequestStateCodec requestStateCodec;

		private McpChangePublisher changePublisher;

		private Builder() {
		}

		public Builder serverInfo(Implementation serverInfo) {
			this.serverInfo = serverInfo;
			return this;
		}

		public Builder instructions(String instructions) {
			this.instructions = instructions;
			return this;
		}

		public Builder supportedVersions(List<String> supportedVersions) {
			Assert.notEmpty(supportedVersions, "supportedVersions must not be empty");
			this.supportedVersions = List.copyOf(supportedVersions);
			return this;
		}

		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			this.jsonMapper = jsonMapper;
			return this;
		}

		public Builder feature(McpFeature feature) {
			Assert.notNull(feature, "feature must not be null");
			this.features.add(feature);
			return this;
		}

		public Builder features(List<? extends McpFeature> features) {
			Assert.notNull(features, "features must not be null");
			features.forEach(this::feature);
			return this;
		}

		/**
		 * Registers {@code subscriptions/listen}, backed by {@code publisher}, for the
		 * tools, prompts and resources features that are registered.
		 */
		public Builder subscriptions(McpChangePublisher publisher) {
			Assert.notNull(publisher, "publisher must not be null");
			this.changePublisher = publisher;
			return this;
		}

		/**
		 * The codec used to seal/open MRTR {@code requestState}. Defaults to
		 * {@link HmacRequestStateCodec#builder()}{@code .build()}.
		 */
		public Builder requestStateCodec(RequestStateCodec requestStateCodec) {
			Assert.notNull(requestStateCodec, "requestStateCodec must not be null");
			this.requestStateCodec = requestStateCodec;
			return this;
		}

		/**
		 * Caching hints for the {@code server/discover} result. Defaults to no caching.
		 */
		public Builder discoverCache(long ttlMs, CacheScope cacheScope) {
			Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			this.discoverTtlMs = ttlMs;
			this.discoverCacheScope = cacheScope;
			return this;
		}

		public McpServer build() {
			Assert.notNull(this.serverInfo, "serverInfo must not be null");
			McpJsonMapper mapper = this.jsonMapper != null ? this.jsonMapper : McpJsonDefaults.getMapper();

			List<McpFeature> allFeatures = new ArrayList<>(this.features);

			ServerCapabilities.Builder capabilitiesBuilder = ServerCapabilities.builder();
			for (McpFeature feature : allFeatures) {
				feature.capabilities(capabilitiesBuilder);
			}

			// Subscriptions and discover are wired up last: which change types
			// subscriptions can honour depends on the registered primitives, and discover
			// advertises the final capabilities.
			SubscriptionsFeature subscriptionsFeature = null;
			if (this.changePublisher != null) {
				subscriptionsFeature = new SubscriptionsFeature(this.changePublisher, mapper,
						capabilitiesBuilder.hasTools(), capabilitiesBuilder.hasPrompts(),
						capabilitiesBuilder.hasResources(), capabilitiesBuilder.hasResourcesSubscribe());
				subscriptionsFeature.capabilities(capabilitiesBuilder);
				allFeatures.add(subscriptionsFeature);
			}
			else {
				// resources/updated is only ever delivered over subscriptions/listen.
				capabilitiesBuilder.resourcesSubscribe(false, false);
			}
			allFeatures.add(new DiscoverFeature(this.supportedVersions, capabilitiesBuilder.build(), this.instructions,
					this.discoverTtlMs, this.discoverCacheScope));

			Map<String, McpFeature> byMethod = new HashMap<>();
			Set<String> inputRequiredMethods = new HashSet<>();
			for (McpFeature feature : allFeatures) {
				for (String method : feature.methods()) {
					McpFeature existing = byMethod.putIfAbsent(method, feature);
					if (existing != null) {
						throw new IllegalStateException("Method '" + method + "' is served by both "
								+ existing.getClass().getName() + " and " + feature.getClass().getName());
					}
				}
				inputRequiredMethods.addAll(feature.inputRequiredMethods());
			}

			RequestStateCodec codec = this.requestStateCodec != null ? this.requestStateCodec
					: HmacRequestStateCodec.builder().jsonMapper(mapper).build();

			return new McpServer(this.serverInfo, this.supportedVersions, mapper, Map.copyOf(byMethod),
					Set.copyOf(inputRequiredMethods), codec, subscriptionsFeature);
		}

	}

}
