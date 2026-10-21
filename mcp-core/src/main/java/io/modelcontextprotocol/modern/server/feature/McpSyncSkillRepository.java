/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.SkillsSchema.Skill;
import io.modelcontextprotocol.modern.server.McpRequestContext;

/**
 * The blocking counterpart of {@link McpAsyncSkillRepository}.
 */
public interface McpSyncSkillRepository {

	SkillsPage list(McpRequestContext ctx, String cursor);

	/**
	 * The skill whose {@code SKILL.md} has this URI, or {@code null} if there is none.
	 */
	Skill find(McpRequestContext ctx, String uri);

	/** See {@link McpAsyncSkillRepository#supportsDirectoryRead()}. */
	default boolean supportsDirectoryRead() {
		return false;
	}

	/**
	 * See {@link McpAsyncSkillRepository#readDirectory}; {@code null} if {@code uri} is
	 * not a directory this server serves.
	 */
	default ResourcesPage readDirectory(McpRequestContext ctx, String uri, String cursor) {
		throw new UnsupportedOperationException("resources/directory/read is not supported");
	}

}
