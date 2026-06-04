/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Repository for context-aware MCP prompt discovery and resolution.
 *
 * @author Taewoong Kim
 */
public interface PromptsRepository {

	/**
	 * List prompts visible to the current exchange.
	 * @param exchange the current client exchange, or {@code null} for context-free
	 * listing
	 * @param request the paginated request parameters
	 * @return the visible prompts and optional next cursor
	 */
	Mono<McpSchema.ListPromptsResult> listPrompts(McpAsyncServerExchange exchange, McpSchema.PaginatedRequest request);

	/**
	 * Resolve a prompt that is visible in the current exchange.
	 * @param name the prompt name
	 * @param exchange the current client exchange
	 * @return the prompt specification, or empty if the prompt is not found or not
	 * allowed
	 */
	Mono<McpServerFeatures.AsyncPromptSpecification> resolvePrompt(String name, McpAsyncServerExchange exchange);

	/**
	 * Add a prompt to the repository.
	 * <p>
	 * Custom repositories that support {@link McpAsyncServer#addPrompt} must override
	 * this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param promptSpecification the prompt specification
	 */
	default void addPrompt(McpServerFeatures.AsyncPromptSpecification promptSpecification) {
		throw new UnsupportedOperationException("This PromptsRepository does not support adding prompts");
	}

	/**
	 * Remove a prompt from the repository.
	 * <p>
	 * Custom repositories that support {@link McpAsyncServer#removePrompt} must override
	 * this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param name the prompt name
	 * @return {@code true} if a prompt was removed
	 */
	default boolean removePrompt(String name) {
		throw new UnsupportedOperationException("This PromptsRepository does not support removing prompts");
	}

}
