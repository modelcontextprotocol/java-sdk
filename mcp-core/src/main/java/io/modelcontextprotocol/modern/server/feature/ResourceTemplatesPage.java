/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.util.Assert;

/**
 * A page of {@code resources/templates/list}.
 *
 * @author Dariusz Jędrzejczyk
 */
public record ResourceTemplatesPage(List<ResourceTemplate> resourceTemplates, String nextCursor, Long ttlMs,
		CacheScope cacheScope) {

	public ResourceTemplatesPage {
		Assert.isTrue(ttlMs == null || ttlMs >= 0, "ttlMs must not be negative");
	}

	public static ResourceTemplatesPage of(List<ResourceTemplate> resourceTemplates) {
		return new ResourceTemplatesPage(resourceTemplates, null, null, null);
	}

}
