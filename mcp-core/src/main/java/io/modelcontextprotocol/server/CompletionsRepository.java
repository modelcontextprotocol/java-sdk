/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Repository for context-aware MCP completion handler resolution.
 *
 * @author Taewoong Kim
 */
public interface CompletionsRepository {

	/**
	 * Resolve a completion handler for the given reference.
	 * @param reference the completion reference
	 * @param exchange the current client exchange
	 * @return the completion specification, or empty if none is available
	 */
	Mono<McpServerFeatures.AsyncCompletionSpecification> resolveCompletion(McpSchema.CompleteReference reference,
			McpAsyncServerExchange exchange);

	/**
	 * Add a completion to the repository.
	 * <p>
	 * Custom repositories that support adding completions through this repository
	 * contract must override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param completionSpecification the completion specification
	 */
	default void addCompletion(McpServerFeatures.AsyncCompletionSpecification completionSpecification) {
		throw new UnsupportedOperationException("This CompletionsRepository does not support adding completions");
	}

	/**
	 * Remove a completion from the repository.
	 * <p>
	 * Custom repositories that support removing completions through this repository
	 * contract must override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param reference the completion reference
	 * @return {@code true} if a completion was removed
	 */
	default boolean removeCompletion(McpSchema.CompleteReference reference) {
		throw new UnsupportedOperationException("This CompletionsRepository does not support removing completions");
	}

}
