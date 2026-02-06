/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import reactor.core.publisher.Mono;

/**
 * Repository interface for managing MCP tools with context-aware operations.
 * <p>
 * This interface allows custom implementations to provide dynamic tool availability and
 * access control based on the client session context.
 * </p>
 */
public interface ToolsRepository {

	/**
	 * List tools visible to the given exchange context with pagination support.
	 * @param exchange The client exchange context containing transport details (e.g.,
	 * headers); may be {@code null} for context-free listing
	 * @param cursor An opaque pagination token. If null, the first page of results is
	 * returned. The structure and meaning of this token is implementation-defined.
	 * @return A {@link Mono} emitting the {@link ToolsListResult} containing visible
	 * tools and optional next cursor.
	 */
	Mono<ToolsListResult> listTools(McpAsyncServerExchange exchange, String cursor);

	/**
	 * Resolve a tool specification for execution by name.
	 * <p>
	 * <b>SECURITY NOTE:</b> Implementations capable of access control <b>SHOULD</b>
	 * verify permission here. It is not sufficient to simply check if the tool exists. If
	 * the tool exists but the current context is not allowed to execute it, this method
	 * <b>SHOULD</b> return an empty Mono.
	 * </p>
	 * @param name The name of the tool to execute
	 * @param exchange The client exchange context
	 * @return A {@link Mono} emitting the
	 * {@link McpServerFeatures.AsyncToolSpecification} if found AND allowed, otherwise
	 * empty.
	 */
	Mono<McpServerFeatures.AsyncToolSpecification> resolveToolForCall(String name, McpAsyncServerExchange exchange);

	/**
	 * Add a tool to the repository at runtime.
	 * <p>
	 * Implementations should ensure thread safety. If a tool with the same name already
	 * exists, the behavior is implementation-defined (typically overwrites).
	 * </p>
	 * @param tool The tool specification to add
	 */
	void addTool(McpServerFeatures.AsyncToolSpecification tool);

	/**
	 * Remove a tool from the repository at runtime by name.
	 * @param name The name of the tool to remove
	 */
	void removeTool(String name);

}
