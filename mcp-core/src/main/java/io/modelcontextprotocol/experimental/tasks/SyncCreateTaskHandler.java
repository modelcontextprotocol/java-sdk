/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Synchronous functional interface for handling task creation for tool calls.
 *
 * <p>
 * This is the synchronous variant of {@link CreateTaskHandler}. It gives tool
 * implementers full control over task creation including TTL configuration, poll
 * interval, and any background work initiation.
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * SyncCreateTaskHandler handler = (args, extra) -> {
 *     // Tool decides TTL directly
 *     long ttl = Duration.ofMinutes(5).toMillis();
 *
 *     Task task = extra.taskStore()
 *         .createTask(CreateTaskOptions.builder()
 *             .requestedTtl(ttl)
 *             .sessionId(extra.sessionId())
 *             .build())
 *         .block();
 *
 *     // Start background work (blocking or async)
 *     startBackgroundWork(task.taskId(), args);
 *
 *     return new McpSchema.CreateTaskResult(task, null);
 * };
 * }</pre>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see CreateTaskHandler
 * @see SyncCreateTaskExtra
 * @see TaskAwareSyncToolSpecification
 */
@FunctionalInterface
public interface SyncCreateTaskHandler {

	/**
	 * Handles task creation for a tool call.
	 *
	 * <p>
	 * The handler is responsible for:
	 * <ul>
	 * <li>Creating the task with desired TTL and poll interval</li>
	 * <li>Starting any background work needed</li>
	 * <li>Returning the created task wrapped in a CreateTaskResult</li>
	 * </ul>
	 * @param args The parsed tool arguments from the CallToolRequest
	 * @param extra Context providing taskStore, exchange, and request metadata
	 * @return the CreateTaskResult containing the created Task
	 */
	McpSchema.CreateTaskResult createTask(Map<String, Object> args, SyncCreateTaskExtra extra);

}
