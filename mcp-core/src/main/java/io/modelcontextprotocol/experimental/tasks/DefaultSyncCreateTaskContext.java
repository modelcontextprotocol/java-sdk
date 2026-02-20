/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.function.Consumer;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.util.Assert;

/**
 * Default implementation of {@link SyncCreateTaskContext} that delegates to a
 * {@link DefaultCreateTaskContext} for all task operations.
 *
 * <p>
 * This implementation is created by {@link io.modelcontextprotocol.server.McpSyncServer}
 * when delegating to a tool's {@link SyncCreateTaskHandler}.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see SyncCreateTaskContext
 * @see DefaultCreateTaskContext
 * @see SyncCreateTaskHandler
 */
public class DefaultSyncCreateTaskContext implements SyncCreateTaskContext {

	private final DefaultCreateTaskContext delegate;

	private final McpSyncServerExchange syncExchange;

	/**
	 * Creates a new DefaultSyncCreateTaskContext that delegates to the given async
	 * implementation.
	 * @param delegate the async CreateTaskContext to delegate task operations to
	 * (required)
	 * @param syncExchange the synchronous server exchange for client interaction
	 * (required)
	 */
	DefaultSyncCreateTaskContext(DefaultCreateTaskContext delegate, McpSyncServerExchange syncExchange) {
		Assert.notNull(delegate, "delegate must not be null");
		Assert.notNull(syncExchange, "syncExchange must not be null");

		this.delegate = delegate;
		this.syncExchange = syncExchange;
	}

	// --------------------------
	// Internal accessors (for framework use only)
	// --------------------------

	/**
	 * Returns the task store from the async delegate. This method is package-private for
	 * internal framework use only.
	 * @return the task store
	 */
	TaskStore<McpSchema.ServerTaskPayloadResult> taskStore() {
		return this.delegate.taskStore();
	}

	/**
	 * Returns the message queue from the async delegate. This method is package-private
	 * for internal framework use only.
	 * @return the message queue, or null if not configured
	 */
	TaskMessageQueue taskMessageQueue() {
		return this.delegate.taskMessageQueue();
	}

	// --------------------------
	// SyncCreateTaskContext implementation
	// --------------------------

	@Override
	public McpSyncServerExchange exchange() {
		return this.syncExchange;
	}

	@Override
	public String sessionId() {
		return this.delegate.sessionId();
	}

	@Override
	public Long requestTtl() {
		return this.delegate.requestTtl();
	}

	@Override
	public McpSchema.Request originatingRequest() {
		return this.delegate.originatingRequest();
	}

	@Override
	public McpSchema.Task createTask() {
		return this.delegate.createTask().block();
	}

	@Override
	public McpSchema.Task createTask(Consumer<CreateTaskOptions.Builder> customizer) {
		return this.delegate.createTask(customizer).block();
	}

	@Override
	public void completeTask(String taskId, CallToolResult result) {
		this.delegate.completeTask(taskId, result).block();
	}

	@Override
	public void failTask(String taskId, String message) {
		this.delegate.failTask(taskId, message).block();
	}

	@Override
	public void setInputRequired(String taskId, String message) {
		this.delegate.setInputRequired(taskId, message).block();
	}

}
