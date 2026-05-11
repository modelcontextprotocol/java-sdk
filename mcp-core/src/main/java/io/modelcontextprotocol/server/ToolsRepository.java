/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Repository for context-aware MCP tool discovery and resolution.
 *
 * @author Taewoong Kim
 */
public interface ToolsRepository {

	/**
	 * List tools visible to the current exchange.
	 * @param exchange the current client exchange, or {@code null} for context-free
	 * listing
	 * @param request the paginated request parameters
	 * @return the visible tools and optional next cursor
	 */
	Mono<McpSchema.ListToolsResult> listTools(McpAsyncServerExchange exchange, McpSchema.PaginatedRequest request);

	/**
	 * Resolve a tool that is allowed to be called in the current exchange.
	 * @param name the tool name
	 * @param exchange the current client exchange
	 * @return the tool specification, or empty if the tool is not found or not allowed
	 */
	Mono<McpServerFeatures.AsyncToolSpecification> resolveTool(String name, McpAsyncServerExchange exchange);

	/**
	 * Add a tool to the repository.
	 * <p>
	 * Custom repositories that support {@link McpAsyncServer#addTool} must override this
	 * method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param toolSpecification the tool specification
	 */
	default void addTool(McpServerFeatures.AsyncToolSpecification toolSpecification) {
		throw new UnsupportedOperationException("This ToolsRepository does not support adding tools");
	}

	/**
	 * Remove a tool from the repository.
	 * <p>
	 * Custom repositories that support {@link McpAsyncServer#removeTool} must override
	 * this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param name the tool name
	 * @return {@code true} if a tool was removed
	 */
	default boolean removeTool(String name) {
		throw new UnsupportedOperationException("This ToolsRepository does not support removing tools");
	}

}
