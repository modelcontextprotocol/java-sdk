/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.feature.McpChangeBroadcaster;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.McpSyncCompletionHandler;
import io.modelcontextprotocol.modern.server.feature.McpSyncPrompt;
import io.modelcontextprotocol.modern.server.feature.McpSyncPromptRepository;
import io.modelcontextprotocol.modern.server.feature.PromptsPage;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.util.Assert;

/**
 * An {@link McpSyncPromptRepository} that keeps {@link McpSyncPrompt}s in memory, keyed
 * by name. Prompts can be added and removed at runtime; each change is broadcast as
 * {@link ServerChange.PromptsListChanged} when a change broadcaster is configured.
 * {@code prompts/list} returns prompts sorted by name, paged when a page size is set.
 * <p>
 * Prompt arguments are completed through
 * {@link McpSyncPrompt#withCompletionHandler(McpSyncCompletionHandler) the prompt's
 * completion handler} only when the repository is built with
 * {@link Builder#supportsCompletion(boolean) supportsCompletion(true)}.
 *
 * @author Christian Tzolov
 */
public final class InMemorySyncPromptRepository implements McpSyncPromptRepository {

	private final Map<String, McpSyncPrompt> prompts = new ConcurrentSkipListMap<>();

	private final McpChangeBroadcaster changeBroadcaster;

	private final Integer pageSize;

	private final boolean supportsCompletion;

	private InMemorySyncPromptRepository(Builder builder) {
		this.changeBroadcaster = builder.changeBroadcaster;
		this.pageSize = builder.pageSize;
		this.supportsCompletion = builder.supportsCompletion;
		builder.prompts.forEach(this::put);
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Adds a prompt and broadcasts {@link ServerChange.PromptsListChanged}.
	 * @throws IllegalArgumentException if a prompt with the same name exists
	 */
	public void add(McpSyncPrompt prompt) {
		put(prompt);
		broadcastListChanged();
	}

	/**
	 * Removes a prompt by name and, if it existed, broadcasts
	 * {@link ServerChange.PromptsListChanged}.
	 * @return whether a prompt was removed
	 */
	public boolean remove(String name) {
		Assert.hasText(name, "name must not be empty");
		boolean removed = this.prompts.remove(name) != null;
		if (removed) {
			broadcastListChanged();
		}
		return removed;
	}

	@Override
	public PromptsPage list(McpRequestContext ctx, String cursor) {
		KeyCursorPagination.Page<Prompt> page = KeyCursorPagination.page(this.prompts.values(),
				prompt -> prompt.definition().name(), McpSyncPrompt::definition, cursor, this.pageSize);
		return new PromptsPage(page.items(), page.nextCursor(), null, null);
	}

	@Override
	public McpSyncPrompt find(McpRequestContext ctx, String name) {
		return this.prompts.get(name);
	}

	@Override
	public boolean supportsCompletion() {
		return this.supportsCompletion;
	}

	private void put(McpSyncPrompt prompt) {
		Assert.notNull(prompt, "prompt must not be null");
		String name = prompt.definition().name();
		if (this.prompts.putIfAbsent(name, prompt) != null) {
			throw new IllegalArgumentException("Prompt with name '" + name + "' already exists");
		}
	}

	private void broadcastListChanged() {
		if (this.changeBroadcaster != null) {
			this.changeBroadcaster.broadcast(new ServerChange.PromptsListChanged());
		}
	}

	/**
	 * Builder for {@link InMemorySyncPromptRepository}.
	 */
	public static final class Builder {

		private final List<McpSyncPrompt> prompts = new ArrayList<>();

		private McpChangeBroadcaster changeBroadcaster;

		private Integer pageSize;

		private boolean supportsCompletion;

		private Builder() {
		}

		public Builder prompt(McpSyncPrompt prompt) {
			Assert.notNull(prompt, "prompt must not be null");
			this.prompts.add(prompt);
			return this;
		}

		public Builder prompts(List<McpSyncPrompt> prompts) {
			Assert.notNull(prompts, "prompts must not be null");
			this.prompts.addAll(prompts);
			return this;
		}

		/**
		 * Receives a {@link ServerChange.PromptsListChanged} for every add and remove,
		 * typically an {@link McpChangeFeed} also registered with
		 * {@code McpServer.Builder#subscriptions}. Unset, changes are not announced.
		 */
		public Builder changeBroadcaster(McpChangeBroadcaster changeBroadcaster) {
			this.changeBroadcaster = changeBroadcaster;
			return this;
		}

		/**
		 * Maximum number of prompts per {@code prompts/list} page. Unset, all prompts are
		 * returned on one page.
		 */
		public Builder pageSize(Integer pageSize) {
			Assert.isTrue(pageSize == null || pageSize > 0, "pageSize must be positive");
			this.pageSize = pageSize;
			return this;
		}

		/**
		 * Whether {@code completion/complete} is served for {@code ref/prompt} references
		 * and {@code completions} is advertised. The server reads this once, when it is
		 * built, so it is a fixed setting rather than derived from the prompts present:
		 * prompts with a completion handler may be added later. A prompt without a
		 * completion handler completes to no values. Defaults to {@code false}.
		 */
		public Builder supportsCompletion(boolean supportsCompletion) {
			this.supportsCompletion = supportsCompletion;
			return this;
		}

		public InMemorySyncPromptRepository build() {
			return new InMemorySyncPromptRepository(this);
		}

	}

}
