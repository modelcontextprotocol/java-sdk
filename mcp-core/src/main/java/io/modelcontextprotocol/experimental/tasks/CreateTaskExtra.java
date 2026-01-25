/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.function.Consumer;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;
import reactor.core.publisher.Mono;

/**
 * Context passed to {@link CreateTaskHandler} providing access to task infrastructure and
 * request metadata.
 * <p />
 * Example usage:
 *
 * <pre>{@code
 * CreateTaskHandler handler = (args, extra) -> {
 *     return extra.createTask(opts -> opts.pollInterval(500L)).flatMap(task -> {
 *         // Start async work that will complete the task later
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
 * <strong>Design Note:</strong> This interface mirrors {@link SyncCreateTaskExtra} for
 * the synchronous API. The duplication is intentional because async methods return
 * {@link Mono} while sync methods return values directly. This separation allows for
 * proper reactive and blocking semantics without forcing one paradigm on the other.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see CreateTaskHandler
 * @see SyncCreateTaskExtra
 */
public interface CreateTaskExtra {

	/**
	 * The server exchange for client interaction.
	 *
	 * <p>
	 * Provides access to session-scoped operations like sending notifications to the
	 * client.
	 * @return the McpAsyncServerExchange instance
	 */
	McpAsyncServerExchange exchange();

	/**
	 * Session ID for task isolation.
	 *
	 * <p>
	 * Tasks created with this session ID will only be visible to the same session,
	 * enabling proper multi-client isolation.
	 * @return the session ID string
	 */
	String sessionId();

	/**
	 * Request-specified TTL from client (may be null).
	 *
	 * <p>
	 * If the client specified a TTL in the task metadata of their request, it will be
	 * available here. Tools can use this to implement client-controlled TTL policies:
	 *
	 * <pre>{@code
	 * // Client can lower but not raise TTL
	 * long maxTtl = Duration.ofMinutes(30).toMillis();
	 * long ttl = extra.requestTtl() != null
	 *     ? Math.min(extra.requestTtl(), maxTtl)
	 *     : maxTtl;
	 * }</pre>
	 * @return the TTL in milliseconds from the client request, or null if not specified
	 */
	Long requestTtl();

	/**
	 * The original MCP request that triggered this task creation.
	 *
	 * <p>
	 * For tool calls, this will be a {@link McpSchema.CallToolRequest} containing the
	 * tool name, arguments, and any task metadata. This request is stored alongside the
	 * task and can be retrieved later via the task store, eliminating the need for
	 * separate task-to-tool mapping.
	 * @return the original request that triggered task creation
	 */
	McpSchema.Request originatingRequest();

	// --------------------------
	// Task Creation
	// --------------------------

	/**
	 * Convenience method to create a task with default options derived from this context.
	 *
	 * <p>
	 * This method automatically uses {@link #originatingRequest()}, {@link #sessionId()},
	 * and {@link #requestTtl()} from this context.
	 *
	 * <pre>{@code
	 * extra.createTask().flatMap(task -> {
	 *     doAsyncWork(args)
	 *         .flatMap(result -> extra.completeTask(task.taskId(), result))
	 *         .subscribe();
	 *     return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
	 * });
	 * }</pre>
	 * @return Mono that completes with the created task
	 */
	Mono<McpSchema.Task> createTask();

	/**
	 * Convenience method to create a task with custom options, but inheriting session
	 * context.
	 *
	 * <p>
	 * This method pre-populates the builder with {@link #originatingRequest()},
	 * {@link #sessionId()}, and {@link #requestTtl()}, then allows customization.
	 *
	 * <pre>{@code
	 * // Create a task with custom poll interval:
	 * extra.createTask(opts -> opts.pollInterval(500L)).flatMap(task -> {
	 *     // Pass task ID explicitly for side-channeling
	 *     extra.exchange().createElicitation(request, task.taskId()).subscribe();
	 *     return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
	 * });
	 * }</pre>
	 * @param customizer function to customize options beyond the defaults
	 * @return Mono that completes with the created task
	 */
	Mono<McpSchema.Task> createTask(Consumer<CreateTaskOptions.Builder> customizer);

	// --------------------------
	// Task Lifecycle
	// --------------------------

	/**
	 * Complete a task with a successful result.
	 *
	 * <p>
	 * This marks the task as {@link TaskStatus#COMPLETED} and stores the result for
	 * client retrieval.
	 *
	 * <pre>{@code
	 * extra.createTask().flatMap(task -> {
	 *     doAsyncWork(args)
	 *         .flatMap(result -> extra.completeTask(task.taskId(), result))
	 *         .subscribe();
	 *     return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
	 * });
	 * }</pre>
	 * @param taskId the ID of the task to complete
	 * @param result the tool result to store
	 * @return Mono that completes when the task is updated
	 */
	Mono<Void> completeTask(String taskId, CallToolResult result);

	/**
	 * Mark a task as failed with an error message.
	 *
	 * <p>
	 * This marks the task as {@link TaskStatus#FAILED} with the provided message.
	 *
	 * <pre>{@code
	 * extra.createTask().flatMap(task -> {
	 *     doAsyncWork(args)
	 *         .flatMap(result -> extra.completeTask(task.taskId(), result))
	 *         .onErrorResume(e -> extra.failTask(task.taskId(), e.getMessage()))
	 *         .subscribe();
	 *     return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
	 * });
	 * }</pre>
	 * @param taskId the ID of the task to fail
	 * @param message the error message describing what went wrong
	 * @return Mono that completes when the task is updated
	 */
	Mono<Void> failTask(String taskId, String message);

	/**
	 * Set a task to INPUT_REQUIRED status, triggering side-channel delivery.
	 *
	 * <p>
	 * When a task is in {@link TaskStatus#INPUT_REQUIRED}, the client will poll via
	 * {@code tasks/result} and receive any queued notifications or requests via
	 * side-channeling.
	 *
	 * <pre>{@code
	 * extra.createTask().flatMap(task -> {
	 *     // Queue a notification for side-channel delivery
	 *     extra.exchange().loggingNotification(notification, task.taskId())
	 *         .then(extra.setInputRequired(task.taskId(), "Waiting for user input"))
	 *         .subscribe();
	 *     return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
	 * });
	 * }</pre>
	 * @param taskId the ID of the task
	 * @param message a status message describing what input is required
	 * @return Mono that completes when the task is updated
	 */
	Mono<Void> setInputRequired(String taskId, String message);

}
