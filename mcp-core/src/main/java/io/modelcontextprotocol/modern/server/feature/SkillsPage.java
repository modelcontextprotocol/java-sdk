/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.SkillsSchema.Skill;
import io.modelcontextprotocol.util.Assert;

/**
 * A page of {@code skills/list}. {@code ttlMs}/{@code cacheScope} are optional; when
 * absent, {@code SkillsFeature} substitutes the server's cache defaults.
 */
public record SkillsPage(List<Skill> skills, String nextCursor, Long ttlMs, CacheScope cacheScope) {

	public SkillsPage {
		Assert.notNull(skills, "skills must not be null");
		Assert.isTrue(ttlMs == null || ttlMs >= 0, "ttlMs must not be negative");
	}

	public static SkillsPage of(List<Skill> skills) {
		return new SkillsPage(skills, null, null, null);
	}

}
