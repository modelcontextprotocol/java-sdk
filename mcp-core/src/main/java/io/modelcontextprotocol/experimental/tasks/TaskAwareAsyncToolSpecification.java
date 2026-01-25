/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TaskSupportMode;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Specification for a task-aware asynchronous tool.
 *
 * <p>
 * This class encapsulates all information needed to define an MCP tool that supports
 * task-augmented execution (SEP-1686). Unlike regular tools
 * ({@link io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification}),
 * task-aware tools have handlers for managing task lifecycle.
 *
 * <p>
 * Use {@link #builder()} to create instances.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * TaskAwareAsyncToolSpecification spec = TaskAwareAsyncToolSpecification.builder()
 *     .name("long-computation")
 *     .description("A long-running computation task")
 *     .inputSchema(new JsonSchema("object", Map.of("input", Map.of("type", "string")), null, null, null, null))
 *     .createTaskHandler((args, extra) -> {
 *         return extra.createTask(opts -> opts.pollInterval(500L)).flatMap(task -> {
 *             doExpensiveComputation(task.taskId(), args)
 *                 .flatMap(result -> extra.completeTask(task.taskId(), result))
 *                 .onErrorResume(e -> extra.failTask(task.taskId(), e.getMessage()))
 *                 .subscribe();
 *             return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
 *         });
 *     })
 *     .build();
 *
 * // Register with server
 * McpServer.async(transport)
 *     .taskTools(spec)
 *     .build();
 * }</pre>
 *
 * <h2>How It Works</h2>
 * <ol>
 * <li>When the tool is called with task metadata, the {@link CreateTaskHandler} is
 * invoked</li>
 * <li>The handler creates a task with desired TTL and poll interval</li>
 * <li>The handler starts any background work and returns immediately with the task</li>
 * <li>Callers can poll {@code tasks/get} for status and retrieve results via
 * {@code tasks/result}</li>
 * </ol>
 *
 * <h2>Task Support Modes</h2>
 * <p>
 * The {@link TaskSupportMode} controls how the tool responds to calls with or without
 * task metadata:
 * <ul>
 * <li><strong>{@link TaskSupportMode#OPTIONAL}</strong> (default): Tool can be called
 * with OR without task metadata. When called without metadata, the server automatically
 * creates an internal task and polls it to completion, returning the result as if it were
 * a synchronous call. This provides backward compatibility for clients that don't support
 * tasks.</li>
 * <li><strong>{@link TaskSupportMode#REQUIRED}</strong>: Tool MUST be called with task
 * metadata. Calls without metadata return error {@code -32601} (METHOD_NOT_FOUND). Use
 * this for tools where callers must explicitly handle task lifecycle (e.g., very
 * long-running operations, tasks requiring user input).</li>
 * <li><strong>{@link TaskSupportMode#FORBIDDEN}</strong>: Tool cannot use tasks. This is
 * the default for normal (non-task-aware) tools. Task-aware tools should not use this
 * mode.</li>
 * </ul>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see CreateTaskHandler
 * @see GetTaskHandler
 * @see GetTaskResultHandler
 * @see TaskAwareSyncToolSpecification
 * @see Builder
 */
public final class TaskAwareAsyncToolSpecification {

	private final Tool tool;

	private final BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> callHandler;

	private final CreateTaskHandler createTaskHandler;

	private final GetTaskHandler getTaskHandler;

	private final GetTaskResultHandler getTaskResultHandler;

	private TaskAwareAsyncToolSpecification(Tool tool,
			BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> callHandler,
			CreateTaskHandler createTaskHandler, GetTaskHandler getTaskHandler,
			GetTaskResultHandler getTaskResultHandler) {
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
	public BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> callHandler() {
		return this.callHandler;
	}

	/**
	 * Returns the handler for task creation.
	 * @return the create task handler
	 */
	public CreateTaskHandler createTaskHandler() {
		return this.createTaskHandler;
	}

	/**
	 * Returns the optional custom handler for tasks/get requests.
	 * @return the get task handler, or null if not set
	 */
	public GetTaskHandler getTaskHandler() {
		return this.getTaskHandler;
	}

	/**
	 * Returns the optional custom handler for tasks/result requests.
	 * @return the get task result handler, or null if not set
	 */
	public GetTaskResultHandler getTaskResultHandler() {
		return this.getTaskResultHandler;
	}

	/**
	 * Creates a new builder for constructing a task-aware async tool specification.
	 * @return a new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Converts a synchronous task-aware tool specification to an asynchronous one.
	 *
	 * <p>
	 * The sync handlers are wrapped to execute on the provided executor.
	 *
	 * <p>
	 * <strong>Important:</strong> The provided executor should be bounded to prevent
	 * thread exhaustion under high task load. Unbounded executors like
	 * {@link java.util.concurrent.ForkJoinPool#commonPool()} are not recommended for
	 * production use. Consider using a bounded thread pool such as
	 * {@link java.util.concurrent.Executors#newFixedThreadPool(int)}.
	 *
	 * <p>
	 * <strong>Note:</strong> This conversion creates a new
	 * {@link DefaultSyncCreateTaskExtra} internally. Custom {@link SyncCreateTaskExtra}
	 * implementations from the original sync specification will not be preserved.
	 * @param sync the synchronous specification to convert
	 * @param executor the executor for running sync handlers (should be bounded)
	 * @return an asynchronous task-aware tool specification
	 */
	public static TaskAwareAsyncToolSpecification fromSync(TaskAwareSyncToolSpecification sync, Executor executor) {
		Assert.notNull(sync, "sync specification must not be null");
		Assert.notNull(executor, "executor must not be null");

		// Wrap sync callHandler
		BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> asyncCallHandler = (exchange,
				request) -> Mono
					.fromCallable(() -> sync.callHandler().apply(sync.createSyncExchange(exchange), request))
					.subscribeOn(Schedulers.fromExecutor(executor));

		// Wrap sync createTaskHandler
		CreateTaskHandler asyncCreateTaskHandler = (args, extra) -> Mono.fromCallable(() -> {
			// Cast to DefaultCreateTaskExtra to access internal
			// taskStore/taskMessageQueue
			DefaultCreateTaskExtra defaultExtra = (DefaultCreateTaskExtra) extra;
			SyncCreateTaskExtra syncExtra = new DefaultSyncCreateTaskExtra(defaultExtra.taskStore(),
					defaultExtra.taskMessageQueue(), sync.createSyncExchange(extra.exchange()), extra.sessionId(),
					extra.requestTtl(), extra.originatingRequest());
			return sync.createTaskHandler().createTask(args, syncExtra);
		}).subscribeOn(Schedulers.fromExecutor(executor));

		// Wrap sync getTask handler if present
		GetTaskHandler asyncGetTaskHandler = null;
		if (sync.getTaskHandler() != null) {
			asyncGetTaskHandler = (exchange, request) -> Mono
				.fromCallable(() -> sync.getTaskHandler().handle(sync.createSyncExchange(exchange), request))
				.subscribeOn(Schedulers.fromExecutor(executor));
		}

		// Wrap sync getTaskResult handler if present
		GetTaskResultHandler asyncGetTaskResultHandler = null;
		if (sync.getTaskResultHandler() != null) {
			asyncGetTaskResultHandler = (exchange, request) -> Mono
				.fromCallable(() -> sync.getTaskResultHandler().handle(sync.createSyncExchange(exchange), request))
				.subscribeOn(Schedulers.fromExecutor(executor));
		}

		return new TaskAwareAsyncToolSpecification(sync.tool(), asyncCallHandler, asyncCreateTaskHandler,
				asyncGetTaskHandler, asyncGetTaskResultHandler);
	}

	/**
	 * Builder for creating task-aware async tool specifications.
	 *
	 * <p>
	 * This builder provides full control over task creation through the
	 * {@link CreateTaskHandler}. Tools decide their own TTL, poll interval, and how to
	 * start background work.
	 */
	public static class Builder extends AbstractTaskAwareToolSpecificationBuilder<Builder> {

		private CreateTaskHandler createTaskHandler;

		private GetTaskHandler getTaskHandler;

		private GetTaskResultHandler getTaskResultHandler;

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
		 *     return extra.createTask(opts -> opts.pollInterval(500L)).flatMap(task -> {
		 *         doWork(task.taskId(), args)
		 *             .flatMap(result -> extra.completeTask(task.taskId(), result))
		 *             .subscribe();
		 *         return Mono.just(McpSchema.CreateTaskResult.builder().task(task).build());
		 *     });
		 * })
		 * }</pre>
		 * @param createTaskHandler the task creation handler
		 * @return this builder
		 */
		public Builder createTaskHandler(CreateTaskHandler createTaskHandler) {
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
		public Builder getTaskHandler(GetTaskHandler getTaskHandler) {
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
		public Builder getTaskResultHandler(GetTaskResultHandler getTaskResultHandler) {
			this.getTaskResultHandler = getTaskResultHandler;
			return this;
		}

		/**
		 * Builds the {@link TaskAwareAsyncToolSpecification}.
		 *
		 * <p>
		 * The returned specification handles task-augmented tool calls by delegating to
		 * the createTaskHandler. For non-task calls, the server uses an automatic polling
		 * shim.
		 * @return a new TaskAwareAsyncToolSpecification instance
		 * @throws IllegalArgumentException if required fields (name, createTask) are not
		 * set
		 */
		@Override
		public TaskAwareAsyncToolSpecification build() {
			validateCommonFields();
			Assert.notNull(createTaskHandler, "createTaskHandler must not be null");

			Tool tool = buildTool();

			// Create a placeholder callHandler for non-task calls
			// (will be handled by automatic polling shim in McpAsyncServer)
			BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> callHandler = (exchange,
					request) -> Mono.error(new UnsupportedOperationException("Tool '" + name
							+ "' requires task-augmented execution. Either provide TaskMetadata in the request, "
							+ "or ensure the server has a TaskStore configured for automatic polling. "
							+ "Direct tool calls without task support are not available for this tool."));

			return new TaskAwareAsyncToolSpecification(tool, callHandler, createTaskHandler, getTaskHandler,
					getTaskResultHandler);
		}

	}

}
