/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

/**
 * Opens the {@code subscriptions/listen} streams of a client and turns their
 * notifications into {@link SubscriptionEvent}s.
 */
final class SubscriptionStream {

	private static final Logger logger = LoggerFactory.getLogger(SubscriptionStream.class);

	private final JsonRpcRequestSender sender;

	private final ClientCapabilities capabilities;

	// Present exactly when the transport mirrors tool params into headers.
	private final ToolCatalog catalog;

	SubscriptionStream(JsonRpcRequestSender sender, ClientCapabilities capabilities, ToolCatalog catalog) {
		this.sender = sender;
		this.capabilities = capabilities;
		this.catalog = catalog;
	}

	/**
	 * Each subscription opens one stream with {@code filter} as its {@code notifications}
	 * and emits {@link SubscriptionEvent.Subscribed} first.
	 */
	Flux<SubscriptionEvent> events(SubscriptionFilter filter, Map<String, Object> applicationMeta) {
		return Flux.defer(() -> {
			Connection connection = new Connection(filter);
			return this.sender.listen(Map.of("notifications", filter.fields()), applicationMeta, this.capabilities)
				.handle(connection::onNotification);
		});
	}

	/**
	 * One stream. Its notifications arrive one after another, so its state needs no
	 * synchronization.
	 */
	private final class Connection {

		private final SubscriptionFilter requested;

		@Nullable private SubscriptionFilter honoured;

		Connection(SubscriptionFilter requested) {
			this.requested = requested;
		}

		void onNotification(JSONRPCNotification notification, SynchronousSink<SubscriptionEvent> sink) {
			String method = notification.method();
			if (McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED.equals(method)) {
				acknowledged(notification, sink);
				return;
			}
			if (this.honoured == null) {
				logger.warn("Dropping {} sent before the subscription was acknowledged", method);
				return;
			}
			SubscriptionEvent event = event(notification);
			if (event == null) {
				return;
			}
			if (event instanceof SubscriptionEvent.ToolsListChanged) {
				invalidateCatalog();
			}
			sink.next(event);
		}

		private void acknowledged(JSONRPCNotification notification, SynchronousSink<SubscriptionEvent> sink) {
			if (this.honoured != null) {
				logger.warn("Dropping a repeated acknowledgment of the subscription");
				return;
			}
			SubscriptionEvent.Subscribed subscribed = new SubscriptionEvent.Subscribed(this.requested,
					honouredFilter(notification.params()));
			this.honoured = subscribed.honoured();
			SubscriptionFilter missing = subscribed.missing();
			if (!missing.isEmpty()) {
				logger.warn("The server doesn't honour this part of the subscription: {}", missing);
			}
			// Changes may have been missed before this connection, so a stream that
			// tracks the tools makes the next call list them again.
			if (this.honoured.wantsToolsListChanged()) {
				invalidateCatalog();
			}
			sink.next(subscribed);
		}

		@Nullable private SubscriptionEvent event(JSONRPCNotification notification) {
			String method = notification.method();
			return switch (method) {
				case McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED ->
					honours(this.honoured.wantsToolsListChanged(), method) ? new SubscriptionEvent.ToolsListChanged()
							: null;
				case McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED ->
					honours(this.honoured.wantsPromptsListChanged(), method)
							? new SubscriptionEvent.PromptsListChanged() : null;
				case McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED ->
					honours(this.honoured.wantsResourcesListChanged(), method)
							? new SubscriptionEvent.ResourcesListChanged() : null;
				case McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED -> resourceUpdated(notification);
				default -> {
					logger.debug("Dropping {}, which no listen stream event stands for", method);
					yield null;
				}
			};
		}

		@Nullable private SubscriptionEvent resourceUpdated(JSONRPCNotification notification) {
			// The URI may be a sub-resource of a subscribed one, so it isn't compared
			// with the subscribed URIs.
			if (this.honoured.resourceSubscriptionsOrEmpty().isEmpty()) {
				logger.warn("Dropping {}, which the subscription doesn't include", notification.method());
				return null;
			}
			if (!(notification.params() instanceof Map<?, ?> params) || !(params.get("uri") instanceof String uri)
					|| uri.isEmpty()) {
				logger.warn("Dropping {} without a uri", notification.method());
				return null;
			}
			return new SubscriptionEvent.ResourceUpdated(uri);
		}

		private boolean honours(boolean honoured, String method) {
			if (honoured) {
				return true;
			}
			logger.warn("Dropping {}, which the subscription doesn't include", method);
			return false;
		}

		private SubscriptionFilter honouredFilter(@Nullable Object params) {
			Map<String, Object> honoured = new LinkedHashMap<>();
			if (params instanceof Map<?, ?> map && map.get("notifications") instanceof Map<?, ?> filter) {
				filter.forEach((field, value) -> honoured.put(String.valueOf(field), value));
			}
			return SubscriptionFilter.of(honoured);
		}

		private void invalidateCatalog() {
			SubscriptionStream.this.catalog.invalidate();
		}

	}

}
