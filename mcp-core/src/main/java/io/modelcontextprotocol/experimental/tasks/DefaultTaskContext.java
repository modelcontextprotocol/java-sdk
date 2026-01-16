/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Task;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * Default implementation of {@link TaskContext} that delegates to a {@link TaskStore}.
 *
 * <p>
 * This implementation provides the standard task context functionality for task handlers,
 * including status updates, cancellation checking, and completion signaling.
 *
 * <p>
 * The type parameter {@code R} specifies the result type that this context handles, which
 * must match the result type of the underlying {@link TaskStore}.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @param <R> the type of result this context handles
 */
public class DefaultTaskContext<R extends McpSchema.Result> implements TaskContext {

	private final String taskId;

	private final String sessionId;

	private final TaskStore<R> taskStore;

	private final TaskMessageQueue taskMessageQueue;

	/**
	 * Creates a new DefaultTaskContext.
	 * @param taskId the task identifier
	 * @param sessionId the session ID for session validation, or null for single-tenant
	 * mode
	 * @param taskStore the task store to delegate to
	 */
	public DefaultTaskContext(String taskId, String sessionId, TaskStore<R> taskStore) {
		this(taskId, sessionId, taskStore, null);
	}

	/**
	 * Creates a new DefaultTaskContext with an optional message queue.
	 * @param taskId the task identifier
	 * @param sessionId the session ID for session validation, or null for single-tenant
	 * mode
	 * @param taskStore the task store to delegate to
	 * @param taskMessageQueue the message queue for INPUT_REQUIRED state communication,
	 * may be null
	 */
	public DefaultTaskContext(String taskId, String sessionId, TaskStore<R> taskStore,
			TaskMessageQueue taskMessageQueue) {
		Assert.hasText(taskId, "Task ID must not be empty");
		Assert.notNull(taskStore, "TaskStore must not be null");
		this.taskId = taskId;
		this.sessionId = sessionId;
		this.taskStore = taskStore;
		this.taskMessageQueue = taskMessageQueue;
	}

	@Override
	public String getTaskId() {
		return this.taskId;
	}

	/**
	 * Returns the session ID associated with this task context.
	 * @return the session ID, or null for single-tenant mode
	 */
	public String getSessionId() {
		return this.sessionId;
	}

	@Override
	public Mono<Task> getTask() {
		return this.taskStore.getTask(this.taskId, this.sessionId).map(GetTaskFromStoreResult::task);
	}

	@Override
	public Mono<Boolean> isCancelled() {
		return this.taskStore.isCancellationRequested(this.taskId, this.sessionId);
	}

	@Override
	public Mono<Void> requestCancellation() {
		return this.taskStore.requestCancellation(this.taskId, this.sessionId).then();
	}

	@Override
	public Mono<Void> updateStatus(String statusMessage) {
		return this.taskStore.updateTaskStatus(this.taskId, this.sessionId, TaskStatus.WORKING, statusMessage);
	}

	@Override
	public Mono<Void> requireInput(String statusMessage) {
		return this.taskStore.updateTaskStatus(this.taskId, this.sessionId, TaskStatus.INPUT_REQUIRED, statusMessage);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * <strong>Type Safety Note:</strong> Due to Java type erasure, the generic type
	 * parameter {@code R} cannot be validated at runtime. This method performs a runtime
	 * check that the result is either a {@link McpSchema.ServerTaskPayloadResult} or
	 * {@link McpSchema.ClientTaskPayloadResult}, but it cannot verify that the result
	 * type matches the specific {@code R} type parameter of this
	 * {@code DefaultTaskContext}.
	 *
	 * <p>
	 * Callers must ensure type consistency: if this context was created with
	 * {@code TaskStore<ServerTaskPayloadResult>}, only {@code ServerTaskPayloadResult}
	 * values (like {@code CallToolResult}) should be passed. Passing the wrong type will
	 * not cause an immediate error but may result in {@code ClassCastException} when the
	 * result is retrieved from the TaskStore.
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Mono<Void> complete(McpSchema.Result result) {
		Assert.notNull(result, "Result must not be null");
		if (!(result instanceof McpSchema.ServerTaskPayloadResult)
				&& !(result instanceof McpSchema.ClientTaskPayloadResult)) {
			return Mono.error(new IllegalArgumentException(
					"Result must be a ServerTaskPayloadResult or ClientTaskPayloadResult, got: "
							+ result.getClass().getName()));
		}
		return this.taskStore.storeTaskResult(this.taskId, this.sessionId, TaskStatus.COMPLETED, (R) result);
	}

	@Override
	public Mono<Void> fail(String errorMessage) {
		return this.taskStore.updateTaskStatus(this.taskId, this.sessionId, TaskStatus.FAILED, errorMessage);
	}

	/**
	 * Returns the message queue for INPUT_REQUIRED state communication.
	 * @return the task message queue, or null if not configured
	 */
	public TaskMessageQueue getMessageQueue() {
		return this.taskMessageQueue;
	}

}
