/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsAcknowledgedParams;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsListenRequest;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsListenResult;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The {@code subscriptions/listen} feature: acknowledges the honoured subset of the
 * requested filter, then forwards the changes from a {@link McpChangePublisher} that the
 * honoured filter covers. The core fields are honoured for the primitives the server has;
 * other features honour the fields they define through
 * {@link McpFeature#honouredSubscriptions}. A {@link SubscriptionRestriction} then
 * narrows the result per request.
 *
 * @author Dariusz Jędrzejczyk
 */
final class SubscriptionsFeature implements McpFeature {

	private static final Logger logger = LoggerFactory.getLogger(SubscriptionsFeature.class);

	private static final TypeRef<LinkedHashMap<String, Object>> PARAMS_TYPE = new TypeRef<>() {
	};

	private static final Set<String> CORE_FIELDS = Set.of(SubscriptionFilter.TOOLS_LIST_CHANGED,
			SubscriptionFilter.PROMPTS_LIST_CHANGED, SubscriptionFilter.RESOURCES_LIST_CHANGED,
			SubscriptionFilter.RESOURCE_SUBSCRIPTIONS);

	private final McpChangePublisher publisher;

	private final McpJsonMapper jsonMapper;

	private final List<McpFeature> features;

	private final SubscriptionRestriction restriction;

	private final boolean hasTools;

	private final boolean hasPrompts;

	private final boolean hasResources;

	private final boolean hasResourcesSubscribe;

	private final Sinks.Empty<Void> shutdown = Sinks.empty();

	/**
	 * @param features the server's other features, asked for the fields they honour
	 * @param restriction narrows what the server and its features honour, per request
	 */
	SubscriptionsFeature(McpChangePublisher publisher, McpJsonMapper jsonMapper, List<McpFeature> features,
			SubscriptionRestriction restriction, boolean hasTools, boolean hasPrompts, boolean hasResources,
			boolean hasResourcesSubscribe) {
		Assert.notNull(publisher, "publisher must not be null");
		Assert.notNull(restriction, "restriction must not be null");
		this.publisher = publisher;
		this.jsonMapper = jsonMapper;
		this.features = List.copyOf(features);
		this.restriction = restriction;
		this.hasTools = hasTools;
		this.hasPrompts = hasPrompts;
		this.hasResources = hasResources;
		this.hasResourcesSubscribe = hasResourcesSubscribe;
	}

	void closeGracefully() {
		this.shutdown.tryEmitEmpty();
	}

	@Override
	public Set<String> methods() {
		return Set.of(McpSchema.METHOD_SUBSCRIPTIONS_LISTEN);
	}

	@Override
	public Mono<McpAsyncResponse<Result>> handle(McpRequestContext ctx, Object params) {
		// The spec requires `notifications` to be a SubscriptionFilter object. Checked on
		// the raw params because SubscriptionsListenRequest deserializes a missing or
		// null value to an empty filter, which would be acknowledged as a valid
		// subscription. Requiring an object (not just non-null) also stops a lenient
		// mapper from coercing "" or [] to null, and therefore to that same empty filter.
		if (!(params instanceof Map<?, ?> map) || !(map.get("notifications") instanceof Map)) {
			return Mono.error(McpException.invalidParams("params.notifications is required"));
		}
		SubscriptionsListenRequest request;
		try {
			request = this.jsonMapper.convertValue(params, SubscriptionsListenRequest.class);
		}
		catch (RuntimeException ex) {
			return Mono.error(McpException.invalidParams("Malformed SubscriptionsListenRequest"));
		}
		SubscriptionFilter requested = request.notifications();
		SubscriptionFilter honoured;
		try {
			honoured = honoured(ctx, requested);
		}
		catch (McpException ex) {
			return Mono.error(ex);
		}
		return restricted(ctx, requested, honoured)
			.map(agreed -> McpAsyncResponse.streaming(notifier -> listen(ctx, agreed, notifier)));
	}

	@Override
	public void capabilities(ServerCapabilities.Builder builder) {
		if (this.hasTools) {
			builder.toolsListChanged(true);
		}
		if (this.hasPrompts) {
			builder.promptsListChanged(true);
		}
		if (this.hasResources) {
			builder.resourcesSubscribe(this.hasResourcesSubscribe, true);
		}
	}

	private Mono<Result> listen(McpRequestContext ctx, SubscriptionFilter honoured, McpAsyncNotifier notifier) {
		// To avoid race conditions in test harnesses, need to ensure that:
		// 1. Changes are subscribed to BEFORE the ack
		// 2. But the ACK goes before any notification
		//
		// Achieve this by:
		// 1. Merging changes + ack
		// 2. Forcing ack to be sent first in the changes
		// 3. Caching the "send ack" so that it only ever goes out once
		Map<String, Object> subscriptionMeta = Map.of(MetaKeys.SUBSCRIPTION_ID, ctx.requestId());
		Mono<Void> ack = notifier
			.notify(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED,
					new SubscriptionsAcknowledgedParams(honoured, subscriptionMeta))
			.cache();

		Flux<Void> forwardChanges = this.publisher.changes()
			.filter(change -> change.isHonouredBy(honoured))
			.takeUntilOther(this.shutdown.asMono())
			.concatMap(change -> {
				Map<String, Object> params = params(change, subscriptionMeta);
				return params == null ? Mono.empty() : ack.then(notifier.notify(change.method(), params));
			});

		return Flux.merge(forwardChanges, ack)
			.then(Mono.fromSupplier(() -> (Result) SubscriptionsListenResult.forSubscription(ctx.requestId())));
	}

	/**
	 * The core fields the server honours, then the fields each feature honours, of those
	 * the client requested.
	 */
	private SubscriptionFilter honoured(McpRequestContext ctx, SubscriptionFilter requested) {
		Map<String, Object> honoured = new LinkedHashMap<>();
		if (this.hasTools && requested.wantsToolsListChanged()) {
			honoured.put(SubscriptionFilter.TOOLS_LIST_CHANGED, true);
		}
		if (this.hasPrompts && requested.wantsPromptsListChanged()) {
			honoured.put(SubscriptionFilter.PROMPTS_LIST_CHANGED, true);
		}
		if (this.hasResources && requested.wantsResourcesListChanged()) {
			honoured.put(SubscriptionFilter.RESOURCES_LIST_CHANGED, true);
		}
		List<String> resourceSubscriptions = this.hasResourcesSubscribe ? requested.resourceSubscriptionsOrEmpty()
				: List.of();
		if (!resourceSubscriptions.isEmpty()) {
			honoured.put(SubscriptionFilter.RESOURCE_SUBSCRIPTIONS, resourceSubscriptions);
		}
		for (McpFeature feature : this.features) {
			feature.honouredSubscriptions(ctx, requested).fields().forEach((field, value) -> {
				// A feature honours only what was asked for, and the core fields are the
				// server's.
				if (!CORE_FIELDS.contains(field) && requested.get(field) != null) {
					honoured.putIfAbsent(field, value);
				}
			});
		}
		return SubscriptionFilter.of(honoured);
	}

	/**
	 * What the restriction leaves of {@code honoured}. Deferred so that a restriction
	 * throwing instead of returning an error {@code Mono} still rejects the request. Not
	 * called when nothing is honoured: it could only narrow to nothing, and may do costly
	 * work such as a permission lookup.
	 */
	private Mono<SubscriptionFilter> restricted(McpRequestContext ctx, SubscriptionFilter requested,
			SubscriptionFilter honoured) {
		if (honoured.isEmpty()) {
			return Mono.just(SubscriptionFilter.EMPTY);
		}
		return Mono.defer(() -> this.restriction.restrict(ctx, requested, honoured))
			.defaultIfEmpty(SubscriptionFilter.EMPTY)
			.map(restricted -> intersection(honoured, restricted));
	}

	/**
	 * The part of {@code restricted} that {@code honoured} covers: the restriction may
	 * only narrow, so that the stream never acknowledges a notification the client didn't
	 * request or that the server can't deliver. A list keeps the elements of
	 * {@code honoured}'s list that {@code restricted}'s list contains, and any other
	 * value, flags included, is kept if {@code restricted} has the same value.
	 */
	private static SubscriptionFilter intersection(SubscriptionFilter honoured, SubscriptionFilter restricted) {
		if (honoured == restricted) {
			return restricted;
		}
		SubscriptionFilter widened = restricted.missingFrom(honoured);
		if (!widened.isEmpty()) {
			logger.warn("Ignoring subscriptions the restriction added beyond what the server honours: {}", widened);
		}
		Map<String, Object> kept = new LinkedHashMap<>();
		honoured.fields().forEach((field, value) -> {
			Object agreed = restricted.get(field);
			if (value instanceof List<?> elements) {
				List<?> agreedElements = agreed instanceof List<?> list ? list : List.of();
				List<?> both = elements.stream().filter(agreedElements::contains).toList();
				if (!both.isEmpty()) {
					kept.put(field, both);
				}
			}
			else if (value.equals(agreed)) {
				kept.put(field, value);
			}
		});
		return SubscriptionFilter.of(kept);
	}

	/**
	 * The params of the notification for {@code change}, with the stream's {@code _meta},
	 * or {@code null} if they aren't an object.
	 */
	private Map<String, Object> params(ServerChange change, Map<String, Object> subscriptionMeta) {
		LinkedHashMap<String, Object> params;
		try {
			params = this.jsonMapper.convertValue(change.params(), PARAMS_TYPE);
		}
		catch (RuntimeException ex) {
			logger.warn("Not forwarding {}: its params aren't a JSON object", change.method(), ex);
			return null;
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		if (params.get("_meta") instanceof Map<?, ?> own) {
			own.forEach((key, value) -> meta.put(String.valueOf(key), value));
		}
		meta.putAll(subscriptionMeta);
		params.put("_meta", meta);
		return params;
	}

}
