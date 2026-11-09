/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.feature.McpChangeBroadcaster;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.util.Assert;

/**
 * An {@link McpSyncToolRepository} that keeps {@link McpSyncTool}s in memory, keyed by
 * name. Tools can be added and removed at runtime; each change is broadcast as
 * {@link ServerChange.ToolsListChanged} when a change broadcaster is configured.
 * {@code tools/list} returns tools sorted by name, paged when a page size is set.
 *
 * @author Christian Tzolov
 */
public final class InMemorySyncToolRepository implements McpSyncToolRepository {

	private final Map<String, McpSyncTool> tools = new ConcurrentSkipListMap<>();

	private final McpChangeBroadcaster changeBroadcaster;

	private final Integer pageSize;

	private InMemorySyncToolRepository(Builder builder) {
		this.changeBroadcaster = builder.changeBroadcaster;
		this.pageSize = builder.pageSize;
		builder.tools.forEach(this::put);
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Adds a tool and broadcasts {@link ServerChange.ToolsListChanged}.
	 * @throws IllegalArgumentException if a tool with the same name exists
	 */
	public void add(McpSyncTool tool) {
		put(tool);
		broadcastListChanged();
	}

	/**
	 * Removes a tool by name and, if it existed, broadcasts
	 * {@link ServerChange.ToolsListChanged}.
	 * @return whether a tool was removed
	 */
	public boolean remove(String name) {
		Assert.hasText(name, "name must not be empty");
		boolean removed = this.tools.remove(name) != null;
		if (removed) {
			broadcastListChanged();
		}
		return removed;
	}

	@Override
	public ToolsPage list(McpRequestContext ctx, String cursor) {
		KeyCursorPagination.Page<Tool> page = KeyCursorPagination.page(this.tools.values(),
				tool -> tool.definition().name(), McpSyncTool::definition, cursor, this.pageSize);
		return new ToolsPage(page.items(), page.nextCursor(), null, null);
	}

	@Override
	public McpSyncTool find(McpRequestContext ctx, String name) {
		return this.tools.get(name);
	}

	private void put(McpSyncTool tool) {
		Assert.notNull(tool, "tool must not be null");
		String name = tool.definition().name();
		if (this.tools.putIfAbsent(name, tool) != null) {
			throw new IllegalArgumentException("Tool with name '" + name + "' already exists");
		}
	}

	private void broadcastListChanged() {
		if (this.changeBroadcaster != null) {
			this.changeBroadcaster.broadcast(new ServerChange.ToolsListChanged());
		}
	}

	/**
	 * Builder for {@link InMemorySyncToolRepository}.
	 */
	public static final class Builder {

		private final List<McpSyncTool> tools = new ArrayList<>();

		private McpChangeBroadcaster changeBroadcaster;

		private Integer pageSize;

		private Builder() {
		}

		public Builder tool(McpSyncTool tool) {
			Assert.notNull(tool, "tool must not be null");
			this.tools.add(tool);
			return this;
		}

		public Builder tools(List<McpSyncTool> tools) {
			Assert.notNull(tools, "tools must not be null");
			this.tools.addAll(tools);
			return this;
		}

		/**
		 * Receives a {@link ServerChange.ToolsListChanged} for every add and remove,
		 * typically an {@link McpChangeFeed} also registered with
		 * {@code McpServer.Builder#subscriptions}. Unset, changes are not announced.
		 */
		public Builder changeBroadcaster(McpChangeBroadcaster changeBroadcaster) {
			this.changeBroadcaster = changeBroadcaster;
			return this;
		}

		/**
		 * Maximum number of tools per {@code tools/list} page. Unset, all tools are
		 * returned on one page.
		 */
		public Builder pageSize(Integer pageSize) {
			Assert.isTrue(pageSize == null || pageSize > 0, "pageSize must be positive");
			this.pageSize = pageSize;
			return this;
		}

		public InMemorySyncToolRepository build() {
			return new InMemorySyncToolRepository(this);
		}

	}

}
