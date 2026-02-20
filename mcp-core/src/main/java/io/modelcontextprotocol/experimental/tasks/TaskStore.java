/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.util.Objects;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Task;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface for storing and managing MCP tasks.
 *
 * <p>
 * The TaskStore provides persistence for long-running tasks, enabling task state
 * management, result storage, and task listing. Implementations may store tasks in
 * memory, a database, or other backing stores.
 *
 * <p>
 * The type parameter {@code R} specifies the result type that this store handles:
 * <ul>
 * <li>For server-side stores (handling tool calls), use
 * {@link McpSchema.ServerTaskPayloadResult}
 * <li>For client-side stores (handling sampling/elicitation), use
 * {@link McpSchema.ClientTaskPayloadResult}
 * <li>For stores that can handle any result type, use {@link McpSchema.Result}
 * </ul>
 *
 * <h2>Error Handling Contract</h2>
 * <p>
 * Methods in this interface follow these error handling conventions:
 * <ul>
 * <li>{@link #getTask}, {@link #getTaskResult}: Return empty Mono if task not found or
 * session mismatch (null-safe pattern for optional lookups)</li>
 * <li>{@link #storeTaskResult}: Throws {@link io.modelcontextprotocol.spec.McpError} if
 * task not found or session mismatch (fail-fast to prevent data loss)</li>
 * <li>{@link #requestCancellation}: Returns empty Mono if task not found or session
 * mismatch (idempotent); throws {@link io.modelcontextprotocol.spec.McpError} with code
 * {@code -32602} if task is in terminal status (per MCP specification requirement)</li>
 * <li>{@link #updateTaskStatus}: Completes silently if task not found or session mismatch
 * (idempotent)</li>
 * </ul>
 *
 * <h2>Session Isolation Model (Defense-in-Depth)</h2>
 * <p>
 * All task operations require a {@code sessionId} parameter for defense-in-depth session
 * isolation. This ensures that even if higher layers (e.g., request handlers) have bugs,
 * the TaskStore itself enforces session boundaries.
 *
 * <p>
 * <strong>Session validation rules:</strong>
 * <ul>
 * <li>If {@code sessionId} is {@code null}, access is allowed (single-tenant mode)</li>
 * <li>If task has no session (created with null sessionId), access is allowed from any
 * session</li>
 * <li>If both task and request have session IDs, they must match for access</li>
 * </ul>
 *
 * <p>
 * <strong>For implementers:</strong>
 * <ol>
 * <li>Store the session ID from {@link CreateTaskOptions#sessionId()} when creating
 * tasks</li>
 * <li>Validate session ID on ALL operations using the rules above</li>
 * <li>Use atomic operations to prevent TOCTOU race conditions between session check and
 * data access</li>
 * </ol>
 *
 * <p>
 * <strong>Durability Note:</strong> The default {@link InMemoryTaskStore} does not
 * provide durability guarantees - task results may be lost if the server crashes during
 * execution. For production use cases requiring durability, implement a custom TaskStore
 * backed by persistent storage (database, Redis, etc.).
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @param <R> the type of result this store handles
 */
public interface TaskStore<R extends McpSchema.Result> {

	/**
	 * Creates a new task with the given options.
	 *
	 * <p>
	 * The session ID for the task is captured from {@link CreateTaskOptions#sessionId()}.
	 * @param options the task creation options
	 * @return a Mono emitting the created Task
	 */
	Mono<Task> createTask(CreateTaskOptions options);

	/**
	 * Retrieves a task by its ID with session validation.
	 *
	 * <p>
	 * This method performs atomic session validation - the task is only returned if the
	 * session ID matches (or if either is null for single-tenant mode).
	 *
	 * <p>
	 * The returned {@link GetTaskFromStoreResult} contains both the task and the original
	 * request that created it, enabling callers to access full context without separate
	 * lookups. For tool calls, the originating request will be a
	 * {@link McpSchema.CallToolRequest} containing the tool name.
	 * @param taskId the task identifier
	 * @param sessionId the session ID for validation, or null for single-tenant mode
	 * @return a Mono emitting the GetTaskFromStoreResult, or empty if not found or
	 * session mismatch
	 */
	Mono<GetTaskFromStoreResult> getTask(String taskId, String sessionId);

	/**
	 * Updates the status of a task with session validation.
	 *
	 * <p>
	 * <strong>Terminal state behavior:</strong> If the task is already in a terminal
	 * state (COMPLETED, FAILED, or CANCELLED), the update will be silently ignored and
	 * the Mono will complete successfully without making any changes. This is intentional
	 * - once a task reaches a terminal state, it cannot transition to any other state.
	 *
	 * <p>
	 * <strong>Valid state transitions:</strong>
	 * <ul>
	 * <li>WORKING → INPUT_REQUIRED, COMPLETED, FAILED, CANCELLED</li>
	 * <li>INPUT_REQUIRED → WORKING, COMPLETED, FAILED, CANCELLED</li>
	 * <li>COMPLETED, FAILED, CANCELLED → (no further transitions allowed)</li>
	 * </ul>
	 * @param taskId the task identifier
	 * @param sessionId the session ID for validation, or null for single-tenant mode
	 * @param status the new status
	 * @param statusMessage optional human-readable status message
	 * @return a Mono that completes when the update is done (or silently ignored for
	 * terminal tasks or session mismatch)
	 */
	Mono<Void> updateTaskStatus(String taskId, String sessionId, TaskStatus status, String statusMessage);

	/**
	 * Stores the result of a completed task with session validation.
	 *
	 * <p>
	 * Implementations should throw {@link io.modelcontextprotocol.spec.McpError} with
	 * {@link McpSchema.ErrorCodes#INVALID_PARAMS} if the task is not found or session
	 * validation fails. This ensures callers are notified of race conditions (e.g., task
	 * expired and was cleaned up between checking existence and storing result) rather
	 * than silently losing data.
	 * @param taskId the task identifier
	 * @param sessionId the session ID for validation, or null for single-tenant mode
	 * @param status the terminal status (completed, failed, or cancelled)
	 * @param result the result to store
	 * @return a Mono that completes when the result is stored
	 * @throws io.modelcontextprotocol.spec.McpError if task not found or session mismatch
	 */
	Mono<Void> storeTaskResult(String taskId, String sessionId, TaskStatus status, R result);

	/**
	 * Retrieves the stored result of a task with session validation.
	 * @param taskId the task identifier
	 * @param sessionId the session ID for validation, or null for single-tenant mode
	 * @return a Mono emitting the Result, or empty if not available or session mismatch
	 */
	Mono<R> getTaskResult(String taskId, String sessionId);

	/**
	 * Lists tasks with pagination and session filtering.
	 *
	 * <p>
	 * When sessionId is provided, only tasks belonging to that session are returned. When
	 * sessionId is null, all tasks are returned (single-tenant mode).
	 *
	 * <p>
	 * <strong>Implementation note:</strong> When filtering by sessionId, pages may
	 * contain fewer than the configured page size entries. This is intentional - it
	 * ensures consistent cursor behavior while allowing session-scoped views of the task
	 * list. Implementations should NOT attempt to "fill" pages by fetching additional
	 * entries.
	 * @param cursor optional pagination cursor
	 * @param sessionId the session ID to filter tasks by, or null for all tasks
	 * @return a Mono emitting the ListTasksResult
	 */
	Mono<McpSchema.ListTasksResult> listTasks(String cursor, String sessionId);

	/**
	 * Requests cancellation of a task with session validation. This is cooperative - the
	 * task handler must periodically check for cancellation.
	 *
	 * <p>
	 * Per the MCP specification, cancellation of tasks in terminal status (COMPLETED,
	 * FAILED, or CANCELLED) MUST be rejected with error code {@code -32602} (Invalid
	 * params). Implementations must throw {@link io.modelcontextprotocol.spec.McpError}
	 * with the appropriate error code when this occurs.
	 * @param taskId the task identifier
	 * @param sessionId the session ID for validation, or null for single-tenant mode
	 * @return a Mono emitting the updated Task after cancellation is requested, or empty
	 * if task not found or session mismatch
	 * @throws io.modelcontextprotocol.spec.McpError with code {@code -32602} if the task
	 * is in a terminal state
	 */
	Mono<Task> requestCancellation(String taskId, String sessionId);

	/**
	 * Checks if cancellation has been requested for a task with session validation.
	 * @param taskId the task identifier
	 * @param sessionId the session ID for validation, or null for single-tenant mode
	 * @return a Mono emitting true if cancellation was requested, false if not canceled
	 * or task not found/session mismatch
	 */
	Mono<Boolean> isCancellationRequested(String taskId, String sessionId);

	/**
	 * Watches a task until it reaches a terminal state (COMPLETED, FAILED, or CANCELLED),
	 * emitting status updates along the way.
	 *
	 * <p>
	 * This method is used for implementing blocking behavior in tasks/result requests. It
	 * polls the task status at regular intervals and emits each status update until the
	 * task reaches a terminal state or the timeout is reached.
	 *
	 * <p>
	 * <strong>Default Implementation Note:</strong> The default implementation uses
	 * {@link Flux#interval} for polling, which creates periodic emissions. This is a
	 * basic approach suitable for development and testing. For production deployments
	 * with many concurrent tasks, consider overriding this method to use more efficient
	 * mechanisms:
	 * <ul>
	 * <li>Event-based watching with notifications</li>
	 * <li>Callback-based approaches using CompletableFuture</li>
	 * <li>Long polling or server-sent events</li>
	 * </ul>
	 *
	 * <p>
	 * The default implementation also uses {@code concatMap} (sequential), meaning only
	 * one {@link #getTask} call is in-flight at a time per watching stream.
	 * @param taskId the task identifier
	 * @param sessionId the session ID for validation, or null for single-tenant mode
	 * @param timeout maximum duration to wait for the task to reach terminal state
	 * @return a Flux emitting Task status updates, completing when terminal or timing out
	 */
	default Flux<Task> watchTaskUntilTerminal(String taskId, String sessionId, Duration timeout) {
		long pollIntervalMs = TaskDefaults.DEFAULT_POLL_INTERVAL_MS;
		return Flux.interval(Duration.ofMillis(pollIntervalMs))
			.concatMap(tick -> getTask(taskId, sessionId).map(GetTaskFromStoreResult::task))
			.filter(Objects::nonNull)
			.takeUntil(Task::isTerminal)
			.timeout(timeout);
	}

	/**
	 * Shuts down the task store, releasing any resources such as background threads or
	 * connections.
	 *
	 * <p>
	 * Default implementation is a no-op. Implementations with cleanup requirements should
	 * override this method.
	 * @return a Mono that completes when shutdown is complete
	 */
	default Mono<Void> shutdown() {
		return Mono.empty();
	}

}
