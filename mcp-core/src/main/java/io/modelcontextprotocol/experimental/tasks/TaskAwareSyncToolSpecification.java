/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.function.BiFunction;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.util.Assert;

/**
 * Specification for a task-aware synchronous tool.
 *
 * <p>
 * This is the synchronous variant of {@link TaskAwareAsyncToolSpecification}. It
 * encapsulates all information needed to define an MCP tool that supports task-augmented
 * execution (SEP-1686) using blocking handlers.
 *
 * <p>
 * Use {@link #builder()} to create instances.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * TaskAwareSyncToolSpecification spec = TaskAwareSyncToolSpecification.builder()
 *     .name("long-computation")
 *     .description("A long-running computation task")
 *     .createTaskHandler((args, extra) -> {
 *         long ttl = Duration.ofMinutes(5).toMillis();
 *         Task task = extra.taskStore()
 *             .createTask(CreateTaskOptions.builder()
 *                 .requestedTtl(ttl)
 *                 .sessionId(extra.sessionId())
 *                 .build())
 *             .block();
 *
 *         // Start background work
 *         startBackgroundComputation(task.taskId(), args);
 *
 *         return new McpSchema.CreateTaskResult(task, null);
 *     })
 *     .build();
 *
 * // Register with server
 * McpServer.sync(transport)
 *     .taskTools(spec)
 *     .build();
 * }</pre>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see SyncCreateTaskHandler
 * @see SyncGetTaskHandler
 * @see SyncGetTaskResultHandler
 * @see TaskAwareAsyncToolSpecification
 * @see Builder
 */
public final class TaskAwareSyncToolSpecification {

	private final Tool tool;

	private final BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> callHandler;

	private final SyncCreateTaskHandler createTaskHandler;

	private final SyncGetTaskHandler getTaskHandler;

	private final SyncGetTaskResultHandler getTaskResultHandler;

	private TaskAwareSyncToolSpecification(Tool tool,
			BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> callHandler,
			SyncCreateTaskHandler createTaskHandler, SyncGetTaskHandler getTaskHandler,
			SyncGetTaskResultHandler getTaskResultHandler) {
		this.tool = tool;
		this.callHandler = callHandler;
		this.createTaskHandler = createTaskHandler;
		this.getTaskHandler = getTaskHandler;
		this.getTaskResultHandler = getTaskResultHandler;
	}

	/**
	 * Returns the tool definition.
	 * @return the tool definition including name, description, and schema
	 */
	public Tool tool() {
		return this.tool;
	}

	/**
	 * Returns the handler for direct (non-task) tool calls.
	 * @return the call handler
	 */
	public BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> callHandler() {
		return this.callHandler;
	}

	/**
	 * Returns the handler for task creation.
	 * @return the create task handler
	 */
	public SyncCreateTaskHandler createTaskHandler() {
		return this.createTaskHandler;
	}

	/**
	 * Returns the optional custom handler for tasks/get requests.
	 * @return the get task handler, or null if not set
	 */
	public SyncGetTaskHandler getTaskHandler() {
		return this.getTaskHandler;
	}

	/**
	 * Returns the optional custom handler for tasks/result requests.
	 * @return the get task result handler, or null if not set
	 */
	public SyncGetTaskResultHandler getTaskResultHandler() {
		return this.getTaskResultHandler;
	}

	/**
	 * Creates a new builder for constructing a task-aware sync tool specification.
	 * @return a new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates a synchronous server exchange from an asynchronous one.
	 *
	 * <p>
	 * This is used internally when converting sync tools to async for server execution.
	 * @param asyncExchange the asynchronous exchange
	 * @return a synchronous exchange wrapping the async exchange
	 */
	McpSyncServerExchange createSyncExchange(McpAsyncServerExchange asyncExchange) {
		return new McpSyncServerExchange(asyncExchange);
	}

	/**
	 * Builder for creating task-aware sync tool specifications.
	 *
	 * <p>
	 * This builder provides full control over task creation through the
	 * {@link SyncCreateTaskHandler}. Tools decide their own TTL, poll interval, and how
	 * to start background work.
	 */
	public static class Builder extends AbstractTaskAwareToolSpecificationBuilder<Builder> {

		private SyncCreateTaskHandler createTaskHandler;

		private SyncGetTaskHandler getTaskHandler;

		private SyncGetTaskResultHandler getTaskResultHandler;

		/**
		 * Sets the handler for task creation (required).
		 *
		 * <p>
		 * This handler is called when the tool is invoked with task metadata. The handler
		 * has full control over task creation including TTL configuration.
		 *
		 * <p>
		 * Example:
		 *
		 * <pre>{@code
		 * .createTaskHandler((args, extra) -> {
		 *     long ttl = Duration.ofMinutes(5).toMillis();
		 *     Task task = extra.taskStore()
		 *         .createTask(CreateTaskOptions.builder()
		 *             .requestedTtl(ttl)
		 *             .sessionId(extra.sessionId())
		 *             .build())
		 *         .block();
		 *
		 *     startBackgroundWork(task.taskId(), args);
		 *     return new McpSchema.CreateTaskResult(task, null);
		 * })
		 * }</pre>
		 * @param createTaskHandler the task creation handler
		 * @return this builder
		 */
		public Builder createTaskHandler(SyncCreateTaskHandler createTaskHandler) {
			this.createTaskHandler = createTaskHandler;
			return this;
		}

		/**
		 * Sets a custom handler for {@code tasks/get} requests.
		 *
		 * <p>
		 * When set, this handler will be called instead of the default task store lookup
		 * when retrieving task status. This enables fetching from external storage or
		 * custom task lifecycle logic.
		 * @param getTaskHandler the custom task retrieval handler
		 * @return this builder
		 */
		public Builder getTaskHandler(SyncGetTaskHandler getTaskHandler) {
			this.getTaskHandler = getTaskHandler;
			return this;
		}

		/**
		 * Sets a custom handler for {@code tasks/result} requests.
		 *
		 * <p>
		 * When set, this handler will be called instead of the default task store lookup
		 * when retrieving task results. This enables fetching from external storage or
		 * lazy result computation.
		 * @param getTaskResultHandler the custom task result retrieval handler
		 * @return this builder
		 */
		public Builder getTaskResultHandler(SyncGetTaskResultHandler getTaskResultHandler) {
			this.getTaskResultHandler = getTaskResultHandler;
			return this;
		}

		/**
		 * Builds the {@link TaskAwareSyncToolSpecification}.
		 *
		 * <p>
		 * The returned specification handles task-augmented tool calls by delegating to
		 * the createTaskHandler. For non-task calls, the server uses an automatic polling
		 * shim.
		 * @return a new TaskAwareSyncToolSpecification instance
		 * @throws IllegalArgumentException if required fields (name, createTask) are not
		 * set
		 */
		@Override
		public TaskAwareSyncToolSpecification build() {
			validateCommonFields();
			Assert.notNull(createTaskHandler, "createTaskHandler must not be null");

			Tool tool = buildTool();

			// Create a placeholder callHandler for non-task calls
			// (will be handled by automatic polling shim in McpSyncServer)
			BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> callHandler = (exchange, request) -> {
				throw new UnsupportedOperationException("Tool '" + name
						+ "' requires task-augmented execution. Either provide TaskMetadata in the request, "
						+ "or ensure the server has a TaskStore configured for automatic polling. "
						+ "Direct tool calls without task support are not available for this tool.");
			};

			return new TaskAwareSyncToolSpecification(tool, callHandler, createTaskHandler, getTaskHandler,
					getTaskResultHandler);
		}

	}

}
