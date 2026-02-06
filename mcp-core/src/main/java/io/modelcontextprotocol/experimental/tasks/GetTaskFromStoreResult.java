/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Result type returned by {@link TaskStore#getTask(String, String)} containing both the
 * task and the original request that created it.
 *
 * <p>
 * This record encapsulates the task along with its originating request, enabling callers
 * to access context about how the task was created without requiring a separate lookup.
 * For tool calls, the originating request will be a {@link McpSchema.CallToolRequest}
 * containing the tool name, arguments, and any task metadata.
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * taskStore.getTask(taskId, sessionId)
 *     .map(result -> {
 *         McpSchema.Task task = result.task();
 *         if (result.originatingRequest() instanceof McpSchema.CallToolRequest ctr) {
 *             String toolName = ctr.name();
 *             // dispatch to tool-specific handler
 *         }
 *         return task;
 *     });
 * }</pre>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @param task the retrieved task
 * @param originatingRequest the original MCP request that triggered task creation
 * @see TaskStore#getTask(String, String)
 */
public record GetTaskFromStoreResult(McpSchema.Task task, McpSchema.Request originatingRequest) {

	/**
	 * Creates a GetTaskFromStoreResult with validation.
	 * @throws IllegalArgumentException if task or originatingRequest is null
	 */
	public GetTaskFromStoreResult {
		if (task == null) {
			throw new IllegalArgumentException("task must not be null");
		}
		if (originatingRequest == null) {
			throw new IllegalArgumentException("originatingRequest must not be null");
		}
	}

}
