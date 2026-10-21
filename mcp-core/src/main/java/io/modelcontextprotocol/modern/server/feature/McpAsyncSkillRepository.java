/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.SkillsSchema.Skill;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import reactor.core.publisher.Mono;

/**
 * User-implemented catalogue of skills. The skills' files are served separately, as
 * ordinary resources.
 */
public interface McpAsyncSkillRepository {

	/**
	 * List (a page of) the skills this repository exposes. The listing may be empty or
	 * partial. An unrecognized cursor is an {@link McpException#invalidParams(String)
	 * invalid-params error}.
	 */
	Mono<SkillsPage> list(McpRequestContext ctx, String cursor);

	/**
	 * The skill whose {@code SKILL.md} has this URI, or empty if there is none. Must find
	 * every skill served, listed or not.
	 */
	Mono<Skill> find(McpRequestContext ctx, String uri);

}
