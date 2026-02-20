/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Synchronous functional interface for handling custom task result retrieval logic.
 *
 * <p>
 * This is the synchronous variant of {@link GetTaskResultHandler}. When a tool registers
 * a custom {@code SyncGetTaskResultHandler}, it will be called instead of the default
 * task store lookup when {@code tasks/result} requests are received for tasks created by
 * that tool.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see GetTaskResultHandler
 * @see SyncGetTaskHandler
 */
@FunctionalInterface
public interface SyncGetTaskResultHandler {

	/**
	 * Handles a {@code tasks/result} request for a task created by the associated tool.
	 * @param exchange the server exchange providing access to the client session
	 * @param request the task result request containing the task ID
	 * @return the task payload result
	 * @throws io.modelcontextprotocol.spec.McpError if the result cannot be retrieved
	 */
	McpSchema.ServerTaskPayloadResult handle(McpSyncServerExchange exchange, McpSchema.GetTaskPayloadRequest request);

}
