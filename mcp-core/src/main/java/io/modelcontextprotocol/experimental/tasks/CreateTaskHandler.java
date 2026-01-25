/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Functional interface for handling task creation for tool calls.
 * <p />
 * Example usage:
 *
 * <pre>{@code
 * CreateTaskHandler handler = (args, extra) -> {
 *     return extra.createTask(opts -> opts.pollInterval(500L)).flatMap(task -> {
 *         // Start background work that will complete the task later
 *         doAsyncWork(args)
 *             .flatMap(result -> extra.completeTask(task.taskId(), result))
 *             .onErrorResume(e -> extra.failTask(task.taskId(), e.getMessage()))
 *             .subscribe();
 *
 *         return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
 *     });
 * };
 * }</pre>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see CreateTaskExtra
 * @see TaskAwareToolSpec
 */
@FunctionalInterface
public interface CreateTaskHandler {

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
	 * @return a Mono emitting the CreateTaskResult containing the created Task
	 */
	Mono<McpSchema.CreateTaskResult> createTask(Map<String, Object> args, CreateTaskExtra extra);

}
