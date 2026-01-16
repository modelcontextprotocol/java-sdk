/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Task;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;
import reactor.core.publisher.Mono;

/**
 * Context for executing a task, providing methods for status updates, cancellation
 * checking, and completion signaling.
 *
 * <p>
 * TaskContext is passed to task handlers to allow them to:
 * <ul>
 * <li>Check if cancellation has been requested via {@link #isCancelled()}
 * <li>Update the task status with progress information via {@link #updateStatus(String)}
 * <li>Complete the task with a result via {@link #complete(McpSchema.Result)}
 * <li>Fail the task with an error message via {@link #fail(String)}
 * </ul>
 *
 * <p>
 * Example usage in a task handler:
 *
 * <pre>{@code
 * public Mono<CreateTaskResult> handleLongRunningTask(TaskContext context, Object args) {
 *     return Mono.fromCallable(() -> {
 *         for (int i = 0; i < 100; i++) {
 *             // Check for cancellation periodically
 *             if (context.isCancelled().block()) {
 *                 return null; // Task was cancelled
 *             }
 *
 *             // Update progress
 *             context.updateStatus("Processing item " + i + "/100").block();
 *
 *             // Do actual work...
 *             processItem(i);
 *         }
 *
 *         // Complete with result
 *         context.complete(new CallToolResult(...)).block();
 *         return new CreateTaskResult(context.getTask(), null);
 *     });
 * }
 * }</pre>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 */
public interface TaskContext {

	/**
	 * Returns the task ID for this context.
	 * @return the task identifier
	 */
	String getTaskId();

	/**
	 * Returns the current task object with the latest status.
	 * @return a Mono emitting the current task state
	 */
	Mono<Task> getTask();

	/**
	 * Checks if cancellation has been requested for this task.
	 *
	 * <p>
	 * Task handlers should check this periodically (e.g., before starting expensive
	 * operations) to support cooperative cancellation.
	 * @return a Mono emitting true if cancellation was requested, false otherwise
	 */
	Mono<Boolean> isCancelled();

	/**
	 * Requests cancellation of this task.
	 *
	 * <p>
	 * This signals that cancellation is desired. The actual cancellation is cooperative -
	 * the task handler must check {@link #isCancelled()} and respond appropriately.
	 * @return a Mono that completes when the cancellation request is recorded
	 */
	Mono<Void> requestCancellation();

	/**
	 * Updates the task status with a progress message.
	 *
	 * <p>
	 * This keeps the task in WORKING status but updates the statusMessage field to
	 * provide progress information to clients polling the task.
	 * @param statusMessage human-readable progress message
	 * @return a Mono that completes when the status is updated
	 */
	Mono<Void> updateStatus(String statusMessage);

	/**
	 * Transitions the task to INPUT_REQUIRED status.
	 *
	 * <p>
	 * Use this when the task needs additional input (e.g., via elicitation) before it can
	 * continue. The task will transition back to WORKING when input is received.
	 * @param statusMessage description of what input is required
	 * @return a Mono that completes when the status is updated
	 */
	Mono<Void> requireInput(String statusMessage);

	/**
	 * Completes the task successfully with the given result.
	 *
	 * <p>
	 * This transitions the task to COMPLETED status and stores the result for retrieval
	 * via tasks/result. After calling this method, the task cannot transition to any
	 * other state.
	 * @param result the task result to store
	 * @return a Mono that completes when the task is completed
	 */
	Mono<Void> complete(McpSchema.Result result);

	/**
	 * Fails the task with an error message.
	 *
	 * <p>
	 * This transitions the task to FAILED status. After calling this method, the task
	 * cannot transition to any other state.
	 * @param errorMessage description of what went wrong
	 * @return a Mono that completes when the task is failed
	 */
	Mono<Void> fail(String errorMessage);

}
