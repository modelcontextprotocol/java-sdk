/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ListChangedParams;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.ResourceUpdatedParams;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsAcknowledgedParams;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsListenRequest;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsListenResult;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The {@code subscriptions/listen} feature: acknowledges the honoured subset of the
 * requested filter, then forwards matching changes from a {@link McpChangePublisher}.
 *
 * @author Dariusz Jędrzejczyk
 */
final class SubscriptionsFeature implements McpFeature {

	private final McpChangePublisher publisher;

	private final McpJsonMapper jsonMapper;

	private final boolean hasTools;

	private final boolean hasPrompts;

	private final boolean hasResources;

	private final boolean hasResourcesSubscribe;

	private final Sinks.Empty<Void> shutdown = Sinks.empty();

	SubscriptionsFeature(McpChangePublisher publisher, McpJsonMapper jsonMapper, boolean hasTools, boolean hasPrompts,
			boolean hasResources, boolean hasResourcesSubscribe) {
		Assert.notNull(publisher, "publisher must not be null");
		this.publisher = publisher;
		this.jsonMapper = jsonMapper;
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
		SubscriptionsListenRequest request;
		try {
			request = params == null ? null : this.jsonMapper.convertValue(params, SubscriptionsListenRequest.class);
		}
		catch (RuntimeException ex) {
			return Mono.error(McpException.invalidParams("Malformed SubscriptionsListenRequest"));
		}
		SubscriptionFilter requested = request == null || request.notifications() == null ? SubscriptionFilter.EMPTY
				: request.notifications();
		return Mono.just(McpAsyncResponse.streaming(notifier -> listen(ctx, intersect(requested), notifier)));
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
		Map<String, Object> subscriptionMeta = Map.of(MetaKeys.SUBSCRIPTION_ID, ctx.requestId());
		Mono<Void> ack = notifier.notify(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED,
				new SubscriptionsAcknowledgedParams(honoured, subscriptionMeta));

		Flux<Void> forwardChanges = this.publisher.changes()
			.filter(change -> matches(change, honoured))
			.takeUntilOther(this.shutdown.asMono())
			.concatMap(change -> notifier.notify(methodFor(change), paramsFor(change, subscriptionMeta)));

		return ack.thenMany(forwardChanges)
			.then(Mono.fromSupplier(() -> (Result) SubscriptionsListenResult.forSubscription(ctx.requestId())));
	}

	private SubscriptionFilter intersect(SubscriptionFilter requested) {
		Boolean tools = (this.hasTools && requested.wantsToolsListChanged()) ? Boolean.TRUE : null;
		Boolean prompts = (this.hasPrompts && requested.wantsPromptsListChanged()) ? Boolean.TRUE : null;
		Boolean resources = (this.hasResources && requested.wantsResourcesListChanged()) ? Boolean.TRUE : null;
		List<String> resourceSubs = this.hasResourcesSubscribe ? requested.resourceSubscriptionsOrEmpty() : List.of();
		return new SubscriptionFilter(tools, prompts, resources, resourceSubs.isEmpty() ? null : resourceSubs);
	}

	private static boolean matches(ServerChange change, SubscriptionFilter honoured) {
		if (change instanceof ServerChange.ToolsListChanged) {
			return honoured.wantsToolsListChanged();
		}
		if (change instanceof ServerChange.PromptsListChanged) {
			return honoured.wantsPromptsListChanged();
		}
		if (change instanceof ServerChange.ResourcesListChanged) {
			return honoured.wantsResourcesListChanged();
		}
		if (change instanceof ServerChange.ResourceUpdated updated) {
			return honoured.resourceSubscriptionsOrEmpty().contains(updated.uri());
		}
		return false;
	}

	private static String methodFor(ServerChange change) {
		if (change instanceof ServerChange.ToolsListChanged) {
			return McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED;
		}
		if (change instanceof ServerChange.PromptsListChanged) {
			return McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED;
		}
		if (change instanceof ServerChange.ResourcesListChanged) {
			return McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED;
		}
		if (change instanceof ServerChange.ResourceUpdated) {
			return McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED;
		}
		throw new IllegalStateException("Unrecognized change: " + change);
	}

	private static Object paramsFor(ServerChange change, Map<String, Object> subscriptionMeta) {
		if (change instanceof ServerChange.ResourceUpdated updated) {
			return new ResourceUpdatedParams(updated.uri(), subscriptionMeta);
		}
		return new ListChangedParams(subscriptionMeta);
	}

}
