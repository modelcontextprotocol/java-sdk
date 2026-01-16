/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Functional interface for handling custom task retrieval logic.
 *
 * <p>
 * When a tool registers a custom {@code GetTaskHandler}, it will be called instead of the
 * default task store lookup when {@code tasks/get} requests are received for tasks
 * created by that tool.
 *
 * <p>
 * This enables tools to:
 * <ul>
 * <li>Fetch task state from external storage (Redis, database, etc.)</li>
 * <li>Transform or enrich task data before returning</li>
 * <li>Implement custom task lifecycle logic</li>
 * </ul>
 *
 * <p>
 * <strong>Full override pattern:</strong> Custom handlers do NOT receive the stored task
 * as input - they are expected to fetch everything independently for maximum flexibility.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see SyncGetTaskHandler
 * @see GetTaskResultHandler
 */
@FunctionalInterface
public interface GetTaskHandler {

	/**
	 * Handles a {@code tasks/get} request for a task created by the associated tool.
	 * @param exchange the server exchange providing access to the client session
	 * @param request the task retrieval request containing the task ID
	 * @return a Mono emitting the task result, or an error if the task cannot be
	 * retrieved
	 */
	Mono<McpSchema.GetTaskResult> handle(McpAsyncServerExchange exchange, McpSchema.GetTaskRequest request);

}
