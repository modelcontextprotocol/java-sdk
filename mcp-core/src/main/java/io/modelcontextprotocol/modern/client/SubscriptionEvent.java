/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.Objects;

import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.util.Assert;

/**
 * An event of a {@code subscriptions/listen} stream, opened with
 * {@link McpAsyncClient#listen()}. Every connection starts with {@link Subscribed}; the
 * other events report changes on the server.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface SubscriptionEvent {

	/**
	 * The server acknowledged the stream. Changes made before it may have been missed, so
	 * a receiver lists again what it keeps, and polls for whatever is {@link #missing()
	 * missing}.
	 */
	final class Subscribed implements SubscriptionEvent {

		private final SubscriptionFilter requested;

		private final SubscriptionFilter honoured;

		Subscribed(SubscriptionFilter requested, SubscriptionFilter honoured) {
			Assert.notNull(requested, "requested must not be null");
			Assert.notNull(honoured, "honoured must not be null");
			this.requested = requested;
			this.honoured = honoured;
		}

		/**
		 * The filter the client sent.
		 */
		public SubscriptionFilter requested() {
			return this.requested;
		}

		/**
		 * The part of the requested filter the server agreed to.
		 */
		public SubscriptionFilter honoured() {
			return this.honoured;
		}

		/**
		 * The parts of the requested filter the server didn't agree to, as
		 * {@link SubscriptionFilter#missingFrom}; empty when the whole filter is
		 * honoured.
		 */
		public SubscriptionFilter missing() {
			return this.requested.missingFrom(this.honoured);
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Subscribed that && this.requested.equals(that.requested)
					&& this.honoured.equals(that.honoured);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.requested, this.honoured);
		}

		@Override
		public String toString() {
			return "Subscribed[requested=" + this.requested + ", honoured=" + this.honoured + "]";
		}

	}

	/**
	 * The server's tools changed ({@code notifications/tools/list_changed}).
	 */
	final class ToolsListChanged implements SubscriptionEvent {

		ToolsListChanged() {
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ToolsListChanged;
		}

		@Override
		public int hashCode() {
			return ToolsListChanged.class.hashCode();
		}

		@Override
		public String toString() {
			return "ToolsListChanged[]";
		}

	}

	/**
	 * The server's prompts changed ({@code notifications/prompts/list_changed}).
	 */
	final class PromptsListChanged implements SubscriptionEvent {

		PromptsListChanged() {
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof PromptsListChanged;
		}

		@Override
		public int hashCode() {
			return PromptsListChanged.class.hashCode();
		}

		@Override
		public String toString() {
			return "PromptsListChanged[]";
		}

	}

	/**
	 * The server's list of resources changed
	 * ({@code notifications/resources/list_changed}).
	 */
	final class ResourcesListChanged implements SubscriptionEvent {

		ResourcesListChanged() {
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ResourcesListChanged;
		}

		@Override
		public int hashCode() {
			return ResourcesListChanged.class.hashCode();
		}

		@Override
		public String toString() {
			return "ResourcesListChanged[]";
		}

	}

	/**
	 * A resource changed and may be read again ({@code notifications/resources/updated}).
	 */
	final class ResourceUpdated implements SubscriptionEvent {

		private final String uri;

		ResourceUpdated(String uri) {
			Assert.hasText(uri, "uri must not be empty");
			this.uri = uri;
		}

		/**
		 * The resource's URI; it may be a sub-resource of a subscribed URI.
		 */
		public String uri() {
			return this.uri;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ResourceUpdated that && this.uri.equals(that.uri);
		}

		@Override
		public int hashCode() {
			return this.uri.hashCode();
		}

		@Override
		public String toString() {
			return "ResourceUpdated[uri=" + this.uri + "]";
		}

	}

}
