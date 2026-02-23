/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Synchronous functional interface for handling custom task retrieval logic.
 *
 * <p>
 * This is the synchronous variant of {@link GetTaskHandler}. When a tool registers a
 * custom {@code SyncGetTaskHandler}, it will be called instead of the default task store
 * lookup when {@code tasks/get} requests are received for tasks created by that tool.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see GetTaskHandler
 * @see SyncGetTaskResultHandler
 */
@FunctionalInterface
public interface SyncGetTaskHandler {

	/**
	 * Handles a {@code tasks/get} request for a task created by the associated tool.
	 * @param exchange the server exchange providing access to the client session
	 * @param request the task retrieval request containing the task ID
	 * @return the task result
	 * @throws io.modelcontextprotocol.spec.McpError if the task cannot be retrieved
	 */
	McpSchema.GetTaskResult handle(McpSyncServerExchange exchange, McpSchema.GetTaskRequest request);

}
