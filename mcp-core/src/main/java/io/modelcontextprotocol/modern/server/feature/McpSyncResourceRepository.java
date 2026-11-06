/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;

import io.modelcontextprotocol.modern.server.McpRequestContext;

/**
 * The blocking counterpart of {@link McpAsyncResourceRepository}.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpSyncResourceRepository {

	ResourcesPage list(McpRequestContext ctx, String cursor);

	default ResourceTemplatesPage listTemplates(McpRequestContext ctx, String cursor) {
		return ResourceTemplatesPage.of(List.of());
	}

	/** See {@link McpAsyncResourceRepository#find}. */
	McpSyncResource find(McpRequestContext ctx, String uri);

	/** See {@link McpAsyncResourceRepository#supportsSubscribe()}. */
	default boolean supportsSubscribe() {
		return false;
	}

}
