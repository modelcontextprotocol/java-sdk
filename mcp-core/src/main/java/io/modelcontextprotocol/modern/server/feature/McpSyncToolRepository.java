/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.server.McpRequestContext;

/**
 * The blocking counterpart of {@link McpAsyncToolRepository}.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpSyncToolRepository {

	ToolsPage list(McpRequestContext ctx, String cursor);

	/** The tool with this name, or {@code null} if there is none. */
	McpSyncTool find(McpRequestContext ctx, String name);

}
