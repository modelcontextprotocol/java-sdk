/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.function.Consumer;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;

/**
 * Synchronous context passed to {@link SyncCreateTaskHandler} providing access to task
 * infrastructure and request metadata.
 *
 * <p>
 * This is the synchronous variant of {@link CreateTaskContext}. It gives tool handlers
 * access to everything needed to create and manage tasks.
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
 * <strong>Design Note:</strong> This interface mirrors {@link CreateTaskContext} for the
 * asynchronous API. The duplication is intentional because async methods return
 * {@code Mono} while sync methods return values directly. This separation allows for
 * proper blocking semantics without requiring reactive programming knowledge.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see SyncCreateTaskHandler
 * @see CreateTaskContext
 */
public interface SyncCreateTaskContext {

	/**
	 * The server exchange for client interaction.
	 *
	 * <p>
	 * Provides access to session-scoped operations like sending notifications to the
	 * client.
	 * @return the McpSyncServerExchange instance
	 */
	McpSyncServerExchange exchange();

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
	 * McpSchema.Task task = extra.createTask();
	 * // Start background work that will complete the task later
	 * new Thread(() -> {
	 *     CallToolResult result = doWork(args);
	 *     extra.completeTask(task.taskId(), result);
	 * }).start();
	 * return McpSchema.CreateTaskResult.builder().task(task).build();
	 * }</pre>
	 * @return the created task
	 */
	McpSchema.Task createTask();

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
	 * McpSchema.Task task = extra.createTask(opts -> opts.pollInterval(500L));
	 * // Pass task ID explicitly for side-channeling
	 * extra.exchange().createElicitation(request, task.taskId());
	 * }</pre>
	 * @param customizer function to customize options beyond the defaults
	 * @return the created task
	 */
	McpSchema.Task createTask(Consumer<CreateTaskOptions.Builder> customizer);

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
	 * // In a getTaskHandler or external callback:
	 * CallToolResult result = externalApi.getJobResult(taskId);
	 * if (result != null) {
	 *     extra.completeTask(taskId, result);
	 * }
	 * }</pre>
	 * @param taskId the ID of the task to complete
	 * @param result the tool result to store
	 */
	void completeTask(String taskId, CallToolResult result);

	/**
	 * Mark a task as failed with an error message.
	 *
	 * <p>
	 * This marks the task as {@link TaskStatus#FAILED} with the provided message.
	 *
	 * <pre>{@code
	 * // In a getTaskHandler or external callback:
	 * try {
	 *     CallToolResult result = externalApi.getJobResult(taskId);
	 *     extra.completeTask(taskId, result);
	 * } catch (Exception e) {
	 *     extra.failTask(taskId, e.getMessage());
	 * }
	 * }</pre>
	 * @param taskId the ID of the task to fail
	 * @param message the error message describing what went wrong
	 */
	void failTask(String taskId, String message);

	/**
	 * Set a task to INPUT_REQUIRED status, triggering side-channel delivery.
	 *
	 * <p>
	 * When a task is in {@link TaskStatus#INPUT_REQUIRED}, the client will poll via
	 * {@code tasks/result} and receive any queued notifications or requests via
	 * side-channeling.
	 *
	 * <pre>{@code
	 * McpSchema.Task task = extra.createTask();
	 * // Queue a notification for side-channel delivery
	 * extra.exchange().loggingNotification(notification, task.taskId());
	 * extra.setInputRequired(task.taskId(), "Waiting for user input");
	 * return McpSchema.CreateTaskResult.builder().task(task).build();
	 * }</pre>
	 * @param taskId the ID of the task
	 * @param message a status message describing what input is required
	 */
	void setInputRequired(String taskId, String message);

}
