/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.util.Assert;

/**
 * A page of {@code prompts/list}.
 *
 * @author Dariusz Jędrzejczyk
 */
public record PromptsPage(List<Prompt> prompts, String nextCursor, Long ttlMs, CacheScope cacheScope) {

	public PromptsPage {
		Assert.isTrue(ttlMs == null || ttlMs >= 0, "ttlMs must not be negative");
	}

	public static PromptsPage of(List<Prompt> prompts) {
		return new PromptsPage(prompts, null, null, null);
	}

}
