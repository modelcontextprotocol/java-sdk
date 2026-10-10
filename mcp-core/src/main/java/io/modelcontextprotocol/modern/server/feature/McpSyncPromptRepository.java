/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;

/**
 * The blocking counterpart of {@link McpAsyncPromptRepository}.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpSyncPromptRepository {

	PromptsPage list(McpRequestContext ctx, String cursor);

	/** The prompt with this name, or {@code null} if there is none. */
	Prompt find(McpRequestContext ctx, String name);

	McpSyncResponse<GetPromptOutcome> get(McpRequestContext ctx, GetPromptRequest request);

}
