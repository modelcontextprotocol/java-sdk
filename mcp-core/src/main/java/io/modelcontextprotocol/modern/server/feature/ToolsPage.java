/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.util.Assert;

/**
 * A page of {@code tools/list}. {@code ttlMs}/{@code cacheScope} are optional; when
 * absent, {@code ToolsFeature} substitutes the server's cache defaults. Every page of one
 * listing must carry the same {@code cacheScope}.
 *
 * @author Dariusz Jędrzejczyk
 */
public record ToolsPage(List<Tool> tools, String nextCursor, Long ttlMs, CacheScope cacheScope) {

	public ToolsPage {
		Assert.isTrue(ttlMs == null || ttlMs >= 0, "ttlMs must not be negative");
	}

	public static ToolsPage of(List<Tool> tools) {
		return new ToolsPage(tools, null, null, null);
	}

}
