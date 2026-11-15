/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.Map;

import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.util.Assert;

/**
 * A change a {@link McpChangePublisher} emits for {@code subscriptions/listen} to forward
 * as a notification, to the streams whose honoured filter covers it. The core changes are
 * the nested records; an extension defines its own, for the filter fields it honours.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface ServerChange {

	/**
	 * The method of the notification the change is sent as.
	 */
	String method();

	/**
	 * The params of the notification, without {@code _meta}, which the stream adds; a
	 * value the server's JSON mapper turns into an object.
	 */
	default Object params() {
		return Map.of();
	}

	/**
	 * Whether a stream that agreed to {@code honoured} receives this change.
	 */
	boolean isHonouredBy(SubscriptionFilter honoured);

	record ToolsListChanged() implements ServerChange {

		@Override
		public String method() {
			return McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED;
		}

		@Override
		public boolean isHonouredBy(SubscriptionFilter honoured) {
			return honoured.wantsToolsListChanged();
		}

	}

	record PromptsListChanged() implements ServerChange {

		@Override
		public String method() {
			return McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED;
		}

		@Override
		public boolean isHonouredBy(SubscriptionFilter honoured) {
			return honoured.wantsPromptsListChanged();
		}

	}

	record ResourcesListChanged() implements ServerChange {

		@Override
		public String method() {
			return McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED;
		}

		@Override
		public boolean isHonouredBy(SubscriptionFilter honoured) {
			return honoured.wantsResourcesListChanged();
		}

	}

	record ResourceUpdated(String uri) implements ServerChange {

		public ResourceUpdated {
			Assert.hasText(uri, "uri must not be empty");
		}

		@Override
		public String method() {
			return McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED;
		}

		@Override
		public Object params() {
			return Map.of("uri", this.uri);
		}

		@Override
		public boolean isHonouredBy(SubscriptionFilter honoured) {
			return honoured.resourceSubscriptionsOrEmpty().contains(this.uri);
		}

	}

}
