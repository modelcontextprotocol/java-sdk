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
 *     McpSchema.Task task = extra.createTask(opts -> opts.pollInterval(500L));
 *
 *     // Start external job - completion happens via getTaskHandler or external callback
 *     externalApi.startJob(task.taskId(), args);
 *
 *     return McpSchema.CreateTaskResult.builder().task(task).build();
 * };
 * }</pre>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see CreateTaskHandler
 * @see SyncCreateTaskContext
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
	 * @param extra Context providing task lifecycle methods, exchange, and request
	 * metadata
	 * @return the CreateTaskResult containing the created Task
	 */
	McpSchema.CreateTaskResult createTask(Map<String, Object> args, SyncCreateTaskContext extra);

}
