/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Encapsulates all task-aware tool management and task lifecycle logic on the server
 * side.
 *
 * <p>
 * This class implements {@link TaskManagerHost} and owns:
 * <ul>
 * <li>Task tool registry (add/remove/list task tools)</li>
 * <li>Task tool dispatching (handleToolCall, createTask, automatic polling)</li>
 * <li>TaskManager lifecycle (creation, binding, close)</li>
 * <li>Task handler adaptation (adapting TaskRequestHandlers to McpRequestHandlers)</li>
 * <li>Metadata decoration for task-related results and notifications</li>
 * </ul>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see TaskManager
 * @see TaskManagerHost
 */
public class ServerTaskToolHandler extends AbstractTaskHandler<McpSchema.ServerTaskPayloadResult> {

	private static final Logger logger = LoggerFactory.getLogger(ServerTaskToolHandler.class);

	private final McpJsonMapper jsonMapper;

	private final TaskManagerOptions taskOptions;

	private final Duration automaticPollingTimeout;

	// Task-aware tools that support long-running operations
	private final CopyOnWriteArrayList<TaskAwareAsyncToolSpecification> taskTools = new CopyOnWriteArrayList<>();

	// Index for fast lookup of task tools by name (for task handler dispatch)
	private final ConcurrentHashMap<String, TaskAwareAsyncToolSpecification> taskToolsByName = new ConcurrentHashMap<>();

	// Lock for atomic tool registration to prevent race conditions between normal tools
	// and task tools
	private final Object toolRegistrationLock = new Object();

	/**
	 * Function to notify all connected clients of a method/params pair. Wraps
	 * {@code mcpTransportProvider::notifyClients}.
	 */
	private final BiFunction<String, Object, Mono<Void>> clientNotifier;

	/**
	 * Creates a new ServerTaskToolHandler.
	 * @param taskTools the initial list of task-aware tool specifications
	 * @param taskOptions the task manager options (may be null if tasks are not
	 * configured)
	 * @param jsonMapper the JSON mapper for serialization/deserialization
	 * @param clientNotifier function to notify all connected clients (wraps
	 * mcpTransportProvider::notifyClients)
	 * @param automaticPollingTimeout timeout for automatic task polling
	 */
	@SuppressWarnings("unchecked")
	public ServerTaskToolHandler(List<TaskAwareAsyncToolSpecification> taskTools, TaskManagerOptions taskOptions,
			McpJsonMapper jsonMapper, BiFunction<String, Object, Mono<Void>> clientNotifier,
			Duration automaticPollingTimeout) {
		super(taskOptions != null ? (TaskStore<McpSchema.ServerTaskPayloadResult>) taskOptions.taskStore() : null,
				taskOptions);

		this.jsonMapper = jsonMapper;
		this.clientNotifier = clientNotifier;
		this.automaticPollingTimeout = automaticPollingTimeout;
		this.taskOptions = taskOptions;

		// Populate task tools from provided list
		this.taskTools.addAll(taskTools);
		for (TaskAwareAsyncToolSpecification taskTool : taskTools) {
			this.taskToolsByName.put(taskTool.tool().name(), taskTool);
		}
	}

	// ---------------------------------------
	// Task Tool Registration
	// ---------------------------------------

	/**
	 * Add a new task-aware tool at runtime.
	 * @param taskToolSpecification The task-aware tool specification to add
	 * @param toolCapabilities The server's tool capabilities (for listChanged
	 * notification)
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addTaskTool(TaskAwareAsyncToolSpecification taskToolSpecification,
			McpSchema.ServerCapabilities.ToolCapabilities toolCapabilities) {
		if (taskToolSpecification == null) {
			return Mono.error(new IllegalArgumentException("Task tool specification must not be null"));
		}
		if (taskToolSpecification.tool() == null) {
			return Mono.error(new IllegalArgumentException("Tool must not be null"));
		}
		if (taskToolSpecification.createTaskHandler() == null) {
			return Mono.error(new IllegalArgumentException("createTask handler must not be null"));
		}

		return Mono.defer(() -> {
			String toolName = taskToolSpecification.tool().name();
			synchronized (this.toolRegistrationLock) {
				// Remove existing task tool with same name if present
				if (this.taskTools.removeIf(th -> th.tool().name().equals(toolName))) {
					logger.warn("Replace existing TaskTool with name '{}'", toolName);
				}

				this.taskTools.add(taskToolSpecification);
				this.taskToolsByName.put(toolName, taskToolSpecification);
			}
			logger.debug("Added task tool handler: {}", toolName);

			if (toolCapabilities != null && toolCapabilities.listChanged()) {
				return notifyToolsListChanged();
			}
			return Mono.empty();
		});
	}

	/**
	 * Remove a task-aware tool at runtime.
	 * @param toolName The name of the task-aware tool to remove
	 * @param toolCapabilities The server's tool capabilities (for listChanged
	 * notification)
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removeTaskTool(String toolName, McpSchema.ServerCapabilities.ToolCapabilities toolCapabilities) {
		if (toolName == null) {
			return Mono.error(new IllegalArgumentException("Tool name must not be null"));
		}

		return Mono.defer(() -> {
			if (this.taskTools.removeIf(toolSpecification -> toolSpecification.tool().name().equals(toolName))) {
				this.taskToolsByName.remove(toolName);
				logger.debug("Removed task tool handler: {}", toolName);
				if (toolCapabilities != null && toolCapabilities.listChanged()) {
					return notifyToolsListChanged();
				}
			}
			else {
				logger.warn("Ignore as a TaskTool with name '{}' not found", toolName);
			}

			return Mono.empty();
		});
	}

	/**
	 * List all registered task-aware tools.
	 * @return A Flux stream of all registered task-aware tools
	 */
	public Flux<Tool> listTaskTools() {
		return Flux.fromIterable(this.taskTools).map(TaskAwareAsyncToolSpecification::tool);
	}

	/**
	 * Returns the list of task tool Tool definitions for merging into the server's tool
	 * list.
	 * @return list of Tool definitions from task tools
	 */
	public List<Tool> getToolDefinitions() {
		return this.taskTools.stream().map(TaskAwareAsyncToolSpecification::tool).toList();
	}

	/**
	 * Checks if this handler has a task tool with the given name.
	 * @param name the tool name to check
	 * @return true if a task tool with the given name exists
	 */
	public boolean hasToolNamed(String name) {
		return this.taskToolsByName.containsKey(name);
	}

	/**
	 * Returns the lock object used for atomic tool registration. Used by McpAsyncServer
	 * to synchronize normal tool registration against task tool names.
	 * @return the tool registration lock
	 */
	public Object getToolRegistrationLock() {
		return this.toolRegistrationLock;
	}

	// ---------------------------------------
	// Task Tool Call Handling
	// ---------------------------------------

	/**
	 * Handles a call to a task-aware tool. Returns empty Mono if the tool name is not a
	 * task tool.
	 * @param exchange the server exchange
	 * @param callToolRequest the tool call request
	 * @return Mono with the result, or empty Mono if not a task tool
	 */
	public Mono<Object> handleToolCall(McpAsyncServerExchange exchange, McpSchema.CallToolRequest callToolRequest) {
		TaskAwareAsyncToolSpecification taskTool = this.taskToolsByName.get(callToolRequest.name());
		if (taskTool == null) {
			return Mono.empty();
		}
		return doHandleTaskToolCall(exchange, callToolRequest, taskTool).map(r -> (Object) r);
	}

	/**
	 * Handles a call to a task-aware tool. Task-aware tools always support tasks and use
	 * the createTaskHandler for task creation.
	 */
	private Mono<?> doHandleTaskToolCall(McpAsyncServerExchange exchange, McpSchema.CallToolRequest request,
			TaskAwareAsyncToolSpecification taskTool) {

		McpSchema.ToolExecution execution = taskTool.tool().execution();
		McpSchema.TaskSupportMode taskSupportMode = execution != null ? execution.taskSupport() : null;

		// Handle task-augmented calls
		if (request.task() != null) {
			// Check if server has task capability
			if (getTaskStore() == null) {
				return Mono.error(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
					.message("Server does not support tasks")
					.data("Task store not configured")
					.build());
			}
			return handleTaskToolCreateTask(exchange, request, taskTool);
		}

		// Check if tool REQUIRES task augmentation
		if (taskSupportMode == McpSchema.TaskSupportMode.REQUIRED) {
			return Mono.error(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
				.message("This tool requires task-augmented execution")
				.data("Tool '" + request.name() + "' requires task metadata in the request")
				.build());
		}

		// No task metadata - use automatic polling shim if taskStore is configured
		if (getTaskStore() != null) {
			return handleAutomaticTaskPolling(exchange, request, taskTool);
		}

		// Fall back to direct call if no task store
		return taskTool.callHandler().apply(exchange, request);
	}

	/**
	 * Handles task creation for a task-aware tool. The tool's createTaskHandler has full
	 * control over task creation including TTL configuration.
	 */
	private Mono<McpSchema.CreateTaskResult> handleTaskToolCreateTask(McpAsyncServerExchange exchange,
			McpSchema.CallToolRequest request, TaskAwareAsyncToolSpecification taskTool) {

		Long requestTtl = request.task() != null ? request.task().ttl() : null;

		CreateTaskContext extra = new DefaultCreateTaskContext(getTaskStore(), getTaskMessageQueue(), exchange,
				exchange.sessionId(), requestTtl, request);

		Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

		return taskTool.createTaskHandler()
			.createTask(args, extra)
			.onErrorMap(e -> !(e instanceof McpError),
					e -> new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
							"Task creation failed: " + e.getMessage(), null)));
	}

	/**
	 * Handles automatic task polling for task-aware tools called without task metadata.
	 *
	 * <p>
	 * This behavior only occurs when ALL of the following are true:
	 * <ul>
	 * <li>The tool has {@link McpSchema.TaskSupportMode#OPTIONAL}</li>
	 * <li>The request does NOT include task metadata</li>
	 * <li>A {@link TaskStore} is configured on the server</li>
	 * </ul>
	 * @param exchange the server exchange context
	 * @param request the original tool call request (without task metadata)
	 * @param taskTool the task-aware tool specification
	 * @return a Mono that completes with the final CallToolResult
	 */
	private Mono<McpSchema.CallToolResult> handleAutomaticTaskPolling(McpAsyncServerExchange exchange,
			McpSchema.CallToolRequest request, TaskAwareAsyncToolSpecification taskTool) {

		CreateTaskContext extra = new DefaultCreateTaskContext(getTaskStore(), getTaskMessageQueue(), exchange,
				exchange.sessionId(), null, request);

		Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

		// 1. Call createTask handler internally
		return taskTool.createTaskHandler().createTask(args, extra).flatMap(createResult -> {
			McpSchema.Task task = createResult.task();
			if (task == null) {
				return Mono.error(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
					.message("createTaskHandler did not return a task")
					.build());
			}

			String taskId = task.taskId();
			long pollInterval = task.pollInterval() != null ? task.pollInterval()
					: TaskDefaults.DEFAULT_POLL_INTERVAL_MS;

			// 2. Poll until terminal state or INPUT_REQUIRED
			String sessionId = exchange.sessionId();
			return Flux.interval(Duration.ofMillis(pollInterval)).flatMap(tick -> {
				// Use getTaskHandler or default
				if (taskTool.getTaskHandler() != null) {
					return taskTool.getTaskHandler()
						.handle(exchange, McpSchema.GetTaskRequest.builder().taskId(taskId).build())
						.map(McpSchema.GetTaskResult::toTask);
				}
				return getTaskStore().getTask(taskId, sessionId).map(GetTaskFromStoreResult::task);
			})
				.filter(Objects::nonNull)
				.takeUntil(t -> t.isTerminal() || t.status() == McpSchema.TaskStatus.INPUT_REQUIRED)
				.last()
				.timeout(getEffectiveAutomaticPollingTimeout())
				.onErrorResume(java.util.concurrent.TimeoutException.class,
						e -> Mono.error(new McpError(
								new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
										"Task timed out waiting for completion: " + taskId, null))))
				.flatMap(finalTask -> {
					if (finalTask.status() == McpSchema.TaskStatus.INPUT_REQUIRED) {
						return Mono.error(new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(
								McpSchema.ErrorCodes.INTERNAL_ERROR,
								"Task requires interactive input which is not supported in automatic polling mode. "
										+ "Use task-augmented requests (with TaskMetadata) to enable interactive input. "
										+ "Task ID: " + taskId,
								null)));
					}
					// 3. Get final result
					if (taskTool.getTaskResultHandler() != null) {
						return taskTool.getTaskResultHandler()
							.handle(exchange, McpSchema.GetTaskPayloadRequest.builder().taskId(taskId).build())
							.map(result -> (McpSchema.CallToolResult) result);
					}
					return getTaskStore().getTaskResult(taskId, sessionId)
						.map(result -> (McpSchema.CallToolResult) result);
				});
		});
	}

	// ---------------------------------------
	// Task Request Handler Wiring
	// ---------------------------------------

	/**
	 * Returns task-related McpRequestHandlers to merge into the server's request handler
	 * map.
	 * @param taskCapabilities the server's task capabilities
	 * @return map of method name to McpRequestHandler for task operations
	 */
	public Map<String, McpRequestHandler<?>> getRequestHandlers(
			McpSchema.ServerCapabilities.ServerTaskCapabilities taskCapabilities) {
		Map<String, McpRequestHandler<?>> handlers = new HashMap<>();
		if (taskCapabilities != null && getTaskStore() != null) {
			this.taskHandlerRegistry.wireHandlers(taskCapabilities.list() != null, taskCapabilities.cancel() != null,
					this::adaptTaskHandler, handlers::put);
		}
		return handlers;
	}

	/**
	 * Logs any capability/implementation mismatches for tasks.
	 * @param taskCapabilities the server's task capabilities
	 */
	public void logCapabilityMismatches(McpSchema.ServerCapabilities.ServerTaskCapabilities taskCapabilities) {
		if (taskCapabilities != null && getTaskStore() == null) {
			logger.warn("Server has tasks capability enabled but no TaskStore configured. "
					+ "Task operations will not be available. Either provide a TaskStore or "
					+ "remove the tasks capability.");
		}
		if (getTaskStore() != null && taskCapabilities == null) {
			logger.warn("Server has TaskStore configured but tasks capability is not enabled. "
					+ "Task operations will not be available. Either enable the tasks capability "
					+ "or remove the TaskStore configuration.");
		}
	}

	// ---------------------------------------
	// Metadata Helpers
	// ---------------------------------------

	/**
	 * Adds the related-task metadata to a server task result.
	 */
	private McpSchema.Result addRelatedTaskMetadata(String taskId, McpSchema.Result result) {
		if (result instanceof McpSchema.CallToolResult ctr) {
			Map<String, Object> newMeta = TaskMetadataUtils.mergeRelatedTaskMetadata(taskId, ctr.meta());
			return new McpSchema.CallToolResult(ctr.content(), ctr.isError(), ctr.structuredContent(), newMeta);
		}
		return result;
	}

	/**
	 * Get the task message queue used for task communication during input_required state.
	 * @return The task message queue, or null if not configured
	 */
	public TaskMessageQueue getTaskMessageQueue() {
		return this.taskOptions != null ? this.taskOptions.messageQueue() : null;
	}

	/**
	 * Returns the effective automatic polling timeout, using the configured value or the
	 * default if not configured.
	 */
	private Duration getEffectiveAutomaticPollingTimeout() {
		return this.automaticPollingTimeout != null ? this.automaticPollingTimeout
				: Duration.ofMillis(TaskDefaults.DEFAULT_AUTOMATIC_POLLING_TIMEOUT_MS);
	}

	// ---------------------------------------
	// Lifecycle
	// ---------------------------------------

	/**
	 * Sends a task status notification to all connected clients.
	 * @param taskStatusNotification The task status notification to send
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyTaskStatus(McpSchema.TaskStatusNotification taskStatusNotification) {
		if (taskStatusNotification == null) {
			return Mono.error(McpError.builder(ErrorCodes.INVALID_REQUEST)
				.message("Task status notification must not be null")
				.build());
		}
		return this.clientNotifier.apply(McpSchema.METHOD_NOTIFICATION_TASKS_STATUS, taskStatusNotification);
	}

	/**
	 * Notifies clients that the list of available tools has changed.
	 */
	private Mono<Void> notifyToolsListChanged() {
		return this.clientNotifier.apply(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
	}

	// --------------------------
	// TaskManagerHost Implementation
	// --------------------------

	@Override
	public <T extends McpSchema.Result> Mono<T> request(McpSchema.Request request, Class<T> resultType) {
		logger.debug(
				"TaskManagerHost.request called on server. For session-specific requests, use McpAsyncServerExchange.");
		return Mono.error(new UnsupportedOperationException(
				"Broadcast requests not supported for tasks. Use session-specific exchange methods."));
	}

	@Override
	public Mono<Void> notification(McpSchema.Notification notification) {
		String method = McpSchema.Notification.getNotificationMethod(notification);
		return this.clientNotifier.apply(method, notification);
	}

	@SuppressWarnings("unchecked")
	private <T> McpRequestHandler<T> adaptTaskHandler(String method, TaskManagerHost.TaskRequestHandler taskHandler) {
		return (exchange, params) -> {
			TaskManagerHost.TaskHandlerContext context = createTaskHandlerContext(exchange.sessionId(),
					(reqMethod, reqParams) -> {
						TypeRef<? extends McpSchema.Result> typeRef = TaskHandlerRegistry
							.getResultTypeRefForMethod(reqMethod);
						return exchange.getSession().sendRequest(reqMethod, reqParams, typeRef);
					},
					(notifMethod, notification) -> exchange.getSession().sendNotification(notifMethod, notification));

			return this.taskHandlerRegistry.<T>invokeHandler(method, params, context).map(result -> {
				if (McpSchema.METHOD_TASKS_RESULT.equals(method) && result instanceof McpSchema.Result) {
					McpSchema.GetTaskPayloadRequest payloadReq = jsonMapper.convertValue(params, new TypeRef<>() {
					});
					if (payloadReq != null && payloadReq.taskId() != null) {
						return (T) addRelatedTaskMetadata(payloadReq.taskId(), (McpSchema.Result) result);
					}
				}
				return result;
			});
		};
	}

	@Override
	@SuppressWarnings("unchecked")
	protected <T extends McpSchema.Result> Mono<T> findAndInvokeCustomHandler(GetTaskFromStoreResult storeResult,
			String method, McpSchema.Request request, TaskManagerHost.TaskHandlerContext context, Class<T> resultType) {

		String toolName = null;
		if (storeResult.originatingRequest() instanceof McpSchema.CallToolRequest ctr) {
			toolName = ctr.name();
		}

		TaskAwareAsyncToolSpecification taskTool = toolName != null ? this.taskToolsByName.get(toolName) : null;

		if (taskTool == null) {
			return Mono.empty();
		}

		McpAsyncServerExchange exchange = new McpAsyncServerExchange(context.sessionId(), null, null, null,
				McpTransportContext.EMPTY);

		if (McpSchema.METHOD_TASKS_GET.equals(method)) {
			var handler = taskTool.getTaskHandler();
			if (handler != null && request instanceof McpSchema.GetTaskRequest getRequest) {
				return handler.handle(exchange, getRequest).map(result -> (T) result);
			}
		}
		else if (McpSchema.METHOD_TASKS_RESULT.equals(method)) {
			var handler = taskTool.getTaskResultHandler();
			if (handler != null && request instanceof McpSchema.GetTaskPayloadRequest payloadRequest) {
				return handler.handle(exchange, payloadRequest).map(result -> (T) result);
			}
		}

		return Mono.empty();
	}

}
