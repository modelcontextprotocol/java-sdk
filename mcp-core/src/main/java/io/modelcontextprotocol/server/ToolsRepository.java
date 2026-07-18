/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Repository contract for listing, resolving, and calling stateless tools from the
 * current MCP request context.
 * <p>
 * Context-free server-side helper calls receive {@link McpTransportContext#EMPTY}.
 *
 * @author Taewoong Kim
 */
public interface ToolsRepository {

	/**
	 * List tools visible for the current request context.
	 * @param request the paginated list request
	 * @param transportContext the transport context for the current request, or
	 * {@link McpTransportContext#EMPTY} for a context-free server-side call
	 * @return the tools visible for the current request
	 */
	McpSchema.ListToolsResult listTools(McpSchema.PaginatedRequest request, McpTransportContext transportContext);

	/**
	 * Resolve tool metadata for the current request context.
	 * @param name the tool name
	 * @param transportContext the transport context for the current request
	 * @return the matching tool metadata, if any
	 */
	Optional<McpSchema.Tool> resolveTool(String name, McpTransportContext transportContext);

	/**
	 * Call a tool for the current request context.
	 * @param request the tool call request
	 * @param transportContext the transport context for the current request
	 * @return the tool call result
	 */
	CallToolResult callTool(McpSchema.CallToolRequest request, McpTransportContext transportContext);

	/**
	 * Add a tool to repositories that support runtime mutation.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * @param toolSpecification the tool specification to add
	 */
	default void addTool(McpStatelessServerFeatures.SyncToolSpecification toolSpecification) {
		throw new UnsupportedOperationException("Tools repository does not support adding tools");
	}

	/**
	 * Remove a tool from repositories that support runtime mutation.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * @param toolName the tool name to remove
	 * @return {@code true} if a tool was removed
	 */
	default boolean removeTool(String toolName) {
		throw new UnsupportedOperationException("Tools repository does not support removing tools");
	}

}
