/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Repository for context-aware MCP prompt discovery and resolution in stateless servers.
 *
 * @author Taewoong Kim
 */
public interface StatelessPromptsRepository {

	Mono<McpSchema.ListPromptsResult> listPrompts(McpTransportContext transportContext,
			McpSchema.PaginatedRequest request);

	Mono<McpStatelessServerFeatures.AsyncPromptSpecification> resolvePrompt(String name,
			McpTransportContext transportContext);

	/**
	 * Add a prompt to the repository.
	 * <p>
	 * Custom repositories that support {@link McpStatelessAsyncServer#addPrompt} must
	 * override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param promptSpecification the prompt specification
	 */
	default void addPrompt(McpStatelessServerFeatures.AsyncPromptSpecification promptSpecification) {
		throw new UnsupportedOperationException("This StatelessPromptsRepository does not support adding prompts");
	}

	/**
	 * Remove a prompt from the repository.
	 * <p>
	 * Custom repositories that support {@link McpStatelessAsyncServer#removePrompt} must
	 * override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param name the prompt name
	 * @return {@code true} if a prompt was removed
	 */
	default boolean removePrompt(String name) {
		throw new UnsupportedOperationException("This StatelessPromptsRepository does not support removing prompts");
	}

}
