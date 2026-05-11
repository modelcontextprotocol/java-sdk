/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Repository for context-aware MCP tool discovery and resolution in stateless servers.
 *
 * @author Taewoong Kim
 */
public interface StatelessToolsRepository {

	Mono<McpSchema.ListToolsResult> listTools(McpTransportContext transportContext, McpSchema.PaginatedRequest request);

	Mono<McpStatelessServerFeatures.AsyncToolSpecification> resolveTool(String name,
			McpTransportContext transportContext);

	/**
	 * Add a tool to the repository.
	 * <p>
	 * Custom repositories that support {@link McpStatelessAsyncServer#addTool} must
	 * override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param toolSpecification the tool specification
	 */
	default void addTool(McpStatelessServerFeatures.AsyncToolSpecification toolSpecification) {
		throw new UnsupportedOperationException("This StatelessToolsRepository does not support adding tools");
	}

	/**
	 * Remove a tool from the repository.
	 * <p>
	 * Custom repositories that support {@link McpStatelessAsyncServer#removeTool} must
	 * override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param name the tool name
	 * @return {@code true} if a tool was removed
	 */
	default boolean removeTool(String name) {
		throw new UnsupportedOperationException("This StatelessToolsRepository does not support removing tools");
	}

}
