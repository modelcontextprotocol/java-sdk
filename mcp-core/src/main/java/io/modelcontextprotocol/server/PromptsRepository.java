/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Repository contract for listing, resolving, and getting stateless prompts from the
 * current MCP request context.
 * <p>
 * Context-free server-side helper calls receive {@link McpTransportContext#EMPTY}.
 *
 * @author Taewoong Kim
 */
public interface PromptsRepository {

	/**
	 * List prompts visible for the current request context.
	 * @param request the paginated list request
	 * @param transportContext the transport context for the current request, or
	 * {@link McpTransportContext#EMPTY} for a context-free server-side call
	 * @return the prompts visible for the current request
	 */
	McpSchema.ListPromptsResult listPrompts(McpSchema.PaginatedRequest request, McpTransportContext transportContext);

	/**
	 * Resolve prompt metadata for the current request context.
	 * @param name the prompt name
	 * @param transportContext the transport context for the current request
	 * @return the matching prompt metadata, if any
	 */
	Optional<McpSchema.Prompt> resolvePrompt(String name, McpTransportContext transportContext);

	/**
	 * Get a prompt for the current request context.
	 * @param request the prompt get request
	 * @param transportContext the transport context for the current request
	 * @return the prompt result
	 */
	McpSchema.GetPromptResult getPrompt(McpSchema.GetPromptRequest request, McpTransportContext transportContext);

	/**
	 * Add a prompt to repositories that support runtime mutation.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * @param promptSpecification the prompt specification to add
	 */
	default void addPrompt(McpStatelessServerFeatures.SyncPromptSpecification promptSpecification) {
		throw new UnsupportedOperationException("Prompts repository does not support adding prompts");
	}

	/**
	 * Remove a prompt from repositories that support runtime mutation.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * @param promptName the prompt name to remove
	 * @return {@code true} if a prompt was removed
	 */
	default boolean removePrompt(String promptName) {
		throw new UnsupportedOperationException("Prompts repository does not support removing prompts");
	}

}
