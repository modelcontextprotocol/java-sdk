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
 *     // Tool decides TTL directly
 *     long ttl = Duration.ofMinutes(5).toMillis();
 *
 *     return extra.taskStore()
 *         .createTask(CreateTaskOptions.builder()
 *             .requestedTtl(ttl)
 *             .sessionId(extra.sessionId())
 *             .build())
 *         .flatMap(task -> {
 *             // Start background work
 *             doWork(task.taskId(), args, extra.exchange()).subscribe();
 *             return Mono.just(new McpSchema.CreateTaskResult(task, null));
 *         });
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
	 * @param extra Context providing taskStore, exchange, and request metadata
	 * @return a Mono emitting the CreateTaskResult containing the created Task
	 */
	Mono<McpSchema.CreateTaskResult> createTask(Map<String, Object> args, CreateTaskExtra extra);

}
