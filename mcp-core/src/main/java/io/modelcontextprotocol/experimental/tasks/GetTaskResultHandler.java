/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Functional interface for handling custom task result retrieval logic.
 *
 * <p>
 * When a tool registers a custom {@code GetTaskResultHandler}, it will be called instead
 * of the default task store lookup when {@code tasks/result} requests are received for
 * tasks created by that tool.
 *
 * <p>
 * This enables tools to:
 * <ul>
 * <li>Fetch task results from external storage</li>
 * <li>Transform or enrich results before returning</li>
 * <li>Implement lazy result computation</li>
 * </ul>
 *
 * <p>
 * <strong>Full override pattern:</strong> Custom handlers do NOT receive the stored
 * result as input - they are expected to fetch everything independently for maximum
 * flexibility.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see SyncGetTaskResultHandler
 * @see GetTaskHandler
 */
@FunctionalInterface
public interface GetTaskResultHandler {

	/**
	 * Handles a {@code tasks/result} request for a task created by the associated tool.
	 * @param exchange the server exchange providing access to the client session
	 * @param request the task result request containing the task ID
	 * @return a Mono emitting the task payload result, or an error if the result cannot
	 * be retrieved
	 */
	Mono<McpSchema.ServerTaskPayloadResult> handle(McpAsyncServerExchange exchange,
			McpSchema.GetTaskPayloadRequest request);

}
