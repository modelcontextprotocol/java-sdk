/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Task handler that encapsulates all task lifecycle logic on the client side.
 *
 * <p>
 * This class extends {@link AbstractTaskHandler} to inherit shared TaskManager lifecycle
 * management and provides client-specific task functionality:
 * <ul>
 * <li>Task-augmented tool call streaming ({@link #callToolStreamTask})</li>
 * <li>Task polling and result fetching</li>
 * <li>Sampling/elicitation task augmentation ({@link #executeTaskAugmentedRequest})</li>
 * <li>Client result processing with related-task metadata echoing</li>
 * <li>Handler adaptation for the client session pipeline</li>
 * </ul>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see AbstractTaskHandler
 * @see TaskManager
 */
public class ClientTaskHandler extends AbstractTaskHandler<McpSchema.ClientTaskPayloadResult> {

	private static final Logger logger = LoggerFactory.getLogger(ClientTaskHandler.class);

	/**
	 * Default timeout for task polling operations. If not explicitly configured via the
	 * builder, polling will timeout after this duration to prevent infinite loops.
	 */
	private static final Duration DEFAULT_TASK_POLL_TIMEOUT = Duration.ofMinutes(5);

	// --------------------------
	// Transport Abstractions
	// --------------------------

	/**
	 * Functional interface for sending requests through the client session. The
	 * implementation wraps initialization and session management.
	 */
	@FunctionalInterface
	public interface SessionRequestSender {

		/**
		 * Sends a request through the client session.
		 * @param <T> the result type
		 * @param method the MCP method name
		 * @param params the request parameters
		 * @param resultType the type reference for deserializing the result
		 * @return a Mono emitting the result
		 */
		<T> Mono<T> sendRequest(String method, Object params, TypeRef<T> resultType);

	}

	/**
	 * Functional interface for sending notifications through the client session. The
	 * implementation wraps initialization and session management.
	 */
	@FunctionalInterface
	public interface SessionNotifier {

		/**
		 * Sends a notification through the client session.
		 * @param method the notification method
		 * @param params the notification parameters
		 * @return a Mono that completes when the notification is sent
		 */
		Mono<Void> sendNotification(String method, Object params);

	}

	// --------------------------
	// Fields
	// --------------------------

	private final SessionRequestSender requestSender;

	private final SessionNotifier notifier;

	private final Duration taskPollTimeout;

	private final McpClientSession.NotificationHandler taskStatusNotificationHandler;

	private final Map<String, McpClientSession.RequestHandler<?>> requestHandlers;

	private final BiFunction<Object, TypeRef<?>, ?> unmarshalFrom;

	// --------------------------
	// Constructor
	// --------------------------

	/**
	 * Creates a new ClientTaskHandler.
	 * @param taskStore the task store for client-side task hosting, or null
	 * @param taskPollTimeout maximum duration to poll for task completion in
	 * callToolStream
	 * @param taskStatusConsumers consumers for task status notifications
	 * @param clientTaskCapabilities the client's task capabilities for conditional
	 * handler wiring
	 * @param requestSender function to send requests through the client session
	 * @param notifier function to send notifications through the client session
	 * @param unmarshalFrom function to deserialize notification params (wraps
	 * transport::unmarshalFrom)
	 */
	public ClientTaskHandler(TaskStore<McpSchema.ClientTaskPayloadResult> taskStore, Duration taskPollTimeout,
			List<Function<McpSchema.TaskStatusNotification, Mono<Void>>> taskStatusConsumers,
			ClientCapabilities.ClientTaskCapabilities clientTaskCapabilities, SessionRequestSender requestSender,
			SessionNotifier notifier, BiFunction<Object, TypeRef<?>, ?> unmarshalFrom) {
		super(taskStore, buildTaskManagerOptions(taskStore));

		this.requestSender = requestSender;
		this.notifier = notifier;
		this.taskPollTimeout = taskPollTimeout;
		this.unmarshalFrom = unmarshalFrom;

		// Build task status notification handler
		this.taskStatusNotificationHandler = buildTaskStatusNotificationHandler(taskStatusConsumers);

		// Wire task request handlers via TaskHandlerRegistry
		Map<String, McpClientSession.RequestHandler<?>> handlers = new HashMap<>();
		if (taskStore != null && clientTaskCapabilities != null) {
			this.taskHandlerRegistry.wireHandlers(clientTaskCapabilities.list() != null,
					clientTaskCapabilities.cancel() != null, this::adaptTaskHandler, handlers::put);
		}
		this.requestHandlers = Collections.unmodifiableMap(handlers);
	}

	private static TaskManagerOptions buildTaskManagerOptions(TaskStore<McpSchema.ClientTaskPayloadResult> taskStore) {
		if (taskStore == null) {
			return null;
		}
		return TaskManagerOptions.builder()
			.store(taskStore)
			.defaultPollInterval(Duration.ofMillis(TaskDefaults.DEFAULT_POLL_INTERVAL_MS))
			.build();
	}

	// --------------------------
	// TaskManagerHost Abstract Impl
	// --------------------------

	@Override
	public <T extends McpSchema.Result> Mono<T> request(McpSchema.Request request, Class<T> resultType) {
		// PaginatedRequest is ambiguous in general, but in the TaskManagerHost
		// context it is always a tasks/list request.
		String method = (request instanceof McpSchema.PaginatedRequest) ? McpSchema.METHOD_TASKS_LIST
				: McpSchema.Request.getRequestMethod(request);
		return this.requestSender.sendRequest(method, request, new TypeRef<>() {
		});
	}

	@Override
	public Mono<Void> notification(McpSchema.Notification notification) {
		String method = McpSchema.Notification.getNotificationMethod(notification);
		return this.notifier.sendNotification(method, notification);
	}

	// --------------------------
	// Public API: Handler Getters
	// --------------------------

	/**
	 * Returns the task status notification handler for registration in the client's
	 * notification handler map.
	 * @return the task status notification handler
	 */
	public McpClientSession.NotificationHandler getTaskStatusNotificationHandler() {
		return this.taskStatusNotificationHandler;
	}

	/**
	 * Returns the adapted task request handlers for merging into the client's request
	 * handler map.
	 * @return unmodifiable map of method name to RequestHandler
	 */
	public Map<String, McpClientSession.RequestHandler<?>> getRequestHandlers() {
		return this.requestHandlers;
	}

	// --------------------------
	// Task Operations (outbound to server)
	// --------------------------

	/**
	 * Retrieves a task previously initiated by the client with the server.
	 * @param getTaskRequest the request containing the task ID
	 * @return a Mono that completes with the task information
	 */
	public Mono<McpSchema.GetTaskResult> getTask(McpSchema.GetTaskRequest getTaskRequest) {
		return this.requestSender.sendRequest(McpSchema.METHOD_TASKS_GET, getTaskRequest, TaskTypeRefs.GET_TASK_RESULT);
	}

	/**
	 * Retrieves a task by its ID.
	 * @param taskId the task identifier
	 * @return a Mono that completes with the task status and metadata
	 */
	public Mono<McpSchema.GetTaskResult> getTask(String taskId) {
		return getTask(McpSchema.GetTaskRequest.builder().taskId(taskId).build());
	}

	/**
	 * Get the result of a completed task.
	 * @param <T> the expected result type
	 * @param getTaskPayloadRequest the request containing the task ID
	 * @param resultTypeRef type reference for deserializing the result
	 * @return a Mono that completes with the task result
	 */
	public <T extends McpSchema.ServerTaskPayloadResult> Mono<T> getTaskResult(
			McpSchema.GetTaskPayloadRequest getTaskPayloadRequest, TypeRef<T> resultTypeRef) {
		return this.requestSender.sendRequest(McpSchema.METHOD_TASKS_RESULT, getTaskPayloadRequest, resultTypeRef);
	}

	/**
	 * Get the result of a completed task by ID.
	 * @param <T> the expected result type
	 * @param taskId the task identifier
	 * @param resultTypeRef type reference for deserializing the result
	 * @return a Mono that completes with the task result
	 */
	public <T extends McpSchema.ServerTaskPayloadResult> Mono<T> getTaskResult(String taskId,
			TypeRef<T> resultTypeRef) {
		return getTaskResult(McpSchema.GetTaskPayloadRequest.builder().taskId(taskId).build(), resultTypeRef);
	}

	/**
	 * List all tasks known by the server with automatic pagination.
	 * @return a Mono that completes with the list of all tasks
	 */
	public Mono<McpSchema.ListTasksResult> listTasks() {
		return this.listTasks(McpSchema.FIRST_PAGE).expand(result -> {
			String next = result.nextCursor();
			return (next != null && !next.isEmpty()) ? this.listTasks(next) : Mono.empty();
		}).reduce(McpSchema.ListTasksResult.builder().tasks(new ArrayList<>()).build(), (allTasksResult, result) -> {
			allTasksResult.tasks().addAll(result.tasks());
			return allTasksResult;
		})
			.map(result -> McpSchema.ListTasksResult.builder()
				.tasks(Collections.unmodifiableList(result.tasks()))
				.build());
	}

	/**
	 * List tasks with pagination support.
	 * @param cursor pagination cursor from a previous list request
	 * @return a Mono that completes with a page of tasks
	 */
	public Mono<McpSchema.ListTasksResult> listTasks(String cursor) {
		return this.requestSender.sendRequest(McpSchema.METHOD_TASKS_LIST, new McpSchema.PaginatedRequest(cursor),
				TaskTypeRefs.LIST_TASKS_RESULT);
	}

	/**
	 * Request cancellation of a task.
	 * @param cancelTaskRequest the request containing the task ID
	 * @return a Mono that completes with the updated task status
	 */
	public Mono<McpSchema.CancelTaskResult> cancelTask(McpSchema.CancelTaskRequest cancelTaskRequest) {
		return this.requestSender.sendRequest(McpSchema.METHOD_TASKS_CANCEL, cancelTaskRequest,
				TaskTypeRefs.CANCEL_TASK_RESULT);
	}

	/**
	 * Request cancellation of a task by ID.
	 * @param taskId the task identifier to cancel
	 * @return a Mono that completes with the updated task status
	 */
	public Mono<McpSchema.CancelTaskResult> cancelTask(String taskId) {
		return cancelTask(McpSchema.CancelTaskRequest.builder().taskId(taskId).build());
	}

	/**
	 * Invokes a tool with task augmentation, creating a background task.
	 * @param callToolRequest the request with task metadata set
	 * @return a Mono that emits the task creation result
	 */
	public Mono<McpSchema.CreateTaskResult> callToolTask(McpSchema.CallToolRequest callToolRequest) {
		return this.requestSender.sendRequest(McpSchema.METHOD_TOOLS_CALL, callToolRequest,
				TaskTypeRefs.CREATE_TASK_RESULT);
	}

	// --------------------------
	// Task-Augmented Streaming
	// --------------------------

	/**
	 * Handles the task-augmented path of callToolStream. Creates a task, polls for
	 * status, and fetches the result.
	 * @param callToolRequest the request with task metadata set
	 * @return a Flux emitting ResponseMessage instances
	 */
	public Flux<McpSchema.ResponseMessage<McpSchema.CallToolResult>> callToolStreamTask(
			McpSchema.CallToolRequest callToolRequest) {
		return Flux.create(sink -> {
			// Cancellation flag to stop polling when subscriber cancels
			AtomicBoolean cancelled = new AtomicBoolean(false);

			// Composite disposable to track all active subscriptions and prevent memory
			// leaks
			Disposable.Composite disposables = Disposables.composite();

			sink.onCancel(() -> {
				cancelled.set(true);
				disposables.dispose();
			});
			sink.onDispose(() -> {
				cancelled.set(true);
				disposables.dispose();
			});

			// Step 1: Create the task
			Disposable createTaskSub = this.callToolTask(callToolRequest).subscribe(createResult -> {
				// Check if cancelled before proceeding
				if (cancelled.get()) {
					return;
				}

				McpSchema.Task task = createResult.task();
				if (task == null) {
					sink.error(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
						.message("Task creation did not return a task")
						.build());
					return;
				}

				// Emit taskCreated message
				sink.next(McpSchema.TaskCreatedMessage.of(task));

				// Step 2: Start polling for task status (record start time for timeout)
				pollTaskUntilTerminal(task.taskId(), sink, Instant.now(), cancelled, disposables);
			}, error -> {
				McpError mcpError = (error instanceof McpError) ? (McpError) error
						: McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message(error.getMessage() != null ? error.getMessage() : "Unknown error")
							.build();
				sink.next(McpSchema.ErrorMessage.of(mcpError));
				sink.complete();
			});
			disposables.add(createTaskSub);
		});
	}

	/**
	 * Polls task status until it reaches a terminal state, emitting status updates and
	 * final result.
	 */
	private void pollTaskUntilTerminal(String taskId,
			reactor.core.publisher.FluxSink<McpSchema.ResponseMessage<McpSchema.CallToolResult>> sink,
			Instant startTime, AtomicBoolean cancelled, Disposable.Composite disposables) {

		// Check if cancelled before proceeding
		if (cancelled.get()) {
			return;
		}

		// Check timeout (use configured timeout or default to prevent infinite loops)
		Duration effectiveTimeout = this.taskPollTimeout != null ? this.taskPollTimeout : DEFAULT_TASK_POLL_TIMEOUT;
		Duration elapsed = Duration.between(startTime, Instant.now());
		if (elapsed.compareTo(effectiveTimeout) > 0) {
			sink.next(McpSchema.ErrorMessage.of(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
				.message("Task polling timed out after " + effectiveTimeout)
				.build()));
			sink.complete();
			return;
		}

		Disposable getTaskSub = this.getTask(McpSchema.GetTaskRequest.builder().taskId(taskId).build())
			.subscribe(taskResult -> {
				// Check if cancelled before processing result
				if (cancelled.get()) {
					return;
				}

				McpSchema.Task task = taskResult.toTask();

				// Emit status update
				sink.next(McpSchema.TaskStatusMessage.of(task));

				// Check TTL enforcement - if task has expired based on server's TTL
				if (task.ttl() != null && task.createdAt() != null) {
					try {
						Instant createdAt = Instant.parse(task.createdAt());
						Instant expiresAt = createdAt.plusMillis(task.ttl());
						if (Instant.now().isAfter(expiresAt)) {
							sink.next(McpSchema.ErrorMessage.of(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
								.message("Task TTL expired after " + task.ttl() + "ms")
								.build()));
							sink.complete();
							return;
						}
					}
					catch (DateTimeParseException e) {
						// Ignore TTL check errors and continue polling
					}
				}

				// Check if terminal
				if (task.isTerminal()) {
					handleTerminalTask(taskId, task, sink, cancelled, disposables);
				}
				else if (task.status() == McpSchema.TaskStatus.INPUT_REQUIRED) {
					// For input_required, call tasks/result which blocks until terminal
					// (This handles elicitation/sampling that may happen server-side)
					fetchTaskResultAndComplete(taskId, sink, cancelled, disposables);
				}
				else {
					// Schedule next poll (only if not cancelled)
					if (!cancelled.get()) {
						long pollInterval = task.pollInterval() != null ? task.pollInterval()
								: TaskDefaults.DEFAULT_POLL_INTERVAL_MS;
						Disposable delaySub = Mono.delay(Duration.ofMillis(pollInterval))
							.subscribe(
									ignored -> pollTaskUntilTerminal(taskId, sink, startTime, cancelled, disposables));
						disposables.add(delaySub);
					}
				}
			}, error -> {
				McpError mcpError = (error instanceof McpError) ? (McpError) error
						: McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message(error.getMessage() != null ? error.getMessage() : "Unknown error")
							.build();
				sink.next(McpSchema.ErrorMessage.of(mcpError));
				sink.complete();
			});
		disposables.add(getTaskSub);
	}

	/**
	 * Handles a task that has reached a terminal state.
	 */
	private void handleTerminalTask(String taskId, McpSchema.Task task,
			reactor.core.publisher.FluxSink<McpSchema.ResponseMessage<McpSchema.CallToolResult>> sink,
			AtomicBoolean cancelled, Disposable.Composite disposables) {
		// Check if cancelled before proceeding
		if (cancelled.get()) {
			return;
		}

		if (task.status() == McpSchema.TaskStatus.COMPLETED) {
			fetchTaskResultAndComplete(taskId, sink, cancelled, disposables);
		}
		else if (task.status() == McpSchema.TaskStatus.FAILED) {
			String message = task.statusMessage() != null ? task.statusMessage() : "Task " + taskId + " failed";
			sink.next(McpSchema.ErrorMessage
				.of(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(message).build()));
			sink.complete();
		}
		else if (task.status() == McpSchema.TaskStatus.CANCELLED) {
			sink.next(McpSchema.ErrorMessage.of(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
				.message("Task " + taskId + " was cancelled")
				.build()));
			sink.complete();
		}
		else {
			sink.complete();
		}
	}

	private static final TypeRef<McpSchema.CallToolResult> CALL_TOOL_RESULT_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * Fetches the task result and completes the stream.
	 */
	private void fetchTaskResultAndComplete(String taskId,
			reactor.core.publisher.FluxSink<McpSchema.ResponseMessage<McpSchema.CallToolResult>> sink,
			AtomicBoolean cancelled, Disposable.Composite disposables) {
		// Check if cancelled before proceeding
		if (cancelled.get()) {
			return;
		}

		Disposable fetchResultSub = this
			.getTaskResult(McpSchema.GetTaskPayloadRequest.builder().taskId(taskId).build(), CALL_TOOL_RESULT_TYPE_REF)
			.subscribe(result -> {
				// Check if cancelled before emitting result
				if (cancelled.get()) {
					return;
				}
				sink.next(McpSchema.ResultMessage.of(result));
				sink.complete();
			}, error -> {
				McpError mcpError = (error instanceof McpError) ? (McpError) error
						: McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message(error.getMessage() != null ? error.getMessage() : "Unknown error")
							.build();
				sink.next(McpSchema.ErrorMessage.of(mcpError));
				sink.complete();
			});
		disposables.add(fetchResultSub);
	}

	// --------------------------
	// Task-Augmented Request Support
	// --------------------------

	/**
	 * Executes a task-augmented request, creating a background task and returning
	 * immediately with a CreateTaskResult.
	 * @param <T> the result type (must implement ClientTaskPayloadResult)
	 * @param originatingRequest the original MCP request that triggered task creation
	 * @param taskMetadata the task metadata from the request
	 * @param handlerMono the handler execution that produces the result
	 * @param operationType name of the operation for logging
	 * @return a Mono that emits CreateTaskResult immediately
	 */
	public <T extends McpSchema.ClientTaskPayloadResult> Mono<McpSchema.Result> executeTaskAugmentedRequest(
			McpSchema.Request originatingRequest, McpSchema.TaskMetadata taskMetadata, Mono<T> handlerMono,
			String operationType) {
		return this.taskStore
			.createTask(CreateTaskOptions.builder(originatingRequest).requestedTtl(taskMetadata.ttl()).build())
			.flatMap(task -> {
				// Execute the handler in the background (fire-and-forget).
				handlerMono
					.flatMap(result -> this.taskStore.storeTaskResult(task.taskId(), null,
							McpSchema.TaskStatus.COMPLETED, result))
					.onErrorResume(error -> this.taskStore
						.updateTaskStatus(task.taskId(), null, McpSchema.TaskStatus.FAILED, error.getMessage())
						.onErrorResume(storeError -> {
							logger.error("Failed to update {} task status for {}: {}", operationType, task.taskId(),
									storeError.getMessage());
							return Mono.empty();
						}))
					.subscribe(unused -> {
						// Background task completed successfully
					}, error -> logger.error("Unexpected error in {} task {}: {}", operationType, task.taskId(),
							error.getMessage()));
				// Return CreateTaskResult immediately
				return Mono.just((McpSchema.Result) McpSchema.CreateTaskResult.builder().task(task).build());
			});
	}

	/**
	 * Processes a client result before returning it to the server. Echoes related-task
	 * metadata from the request to the response.
	 * @param requestMeta the request's _meta field
	 * @param result the handler's result
	 * @return the processed result with related-task metadata echoed (if present)
	 */
	public McpSchema.Result processClientResult(Map<String, Object> requestMeta, McpSchema.Result result) {
		if (requestMeta == null || !requestMeta.containsKey(McpSchema.RELATED_TASK_META_KEY)) {
			return result;
		}

		Object relatedTask = requestMeta.get(McpSchema.RELATED_TASK_META_KEY);
		Map<String, Object> newMeta = TaskMetadataUtils.mergeRelatedTaskMetadata(relatedTask, result.meta());

		if (result instanceof McpSchema.ElicitResult elicitResult) {
			return new McpSchema.ElicitResult(elicitResult.action(), elicitResult.content(), newMeta);
		}
		else if (result instanceof McpSchema.CreateMessageResult messageResult) {
			return new McpSchema.CreateMessageResult(messageResult.role(), messageResult.content(),
					messageResult.model(), messageResult.stopReason(), newMeta);
		}

		return result;
	}

	// --------------------------
	// Handler Adaptation
	// --------------------------

	/**
	 * Creates an adapter that converts a {@link TaskManagerHost.TaskRequestHandler} to a
	 * {@link McpClientSession.RequestHandler}. This allows TaskManager-registered
	 * handlers to be used in the client's standard request handling pipeline.
	 * @param <T> the expected result type
	 * @param method the MCP method name
	 * @param taskHandler the TaskRequestHandler to adapt
	 * @return a RequestHandler that delegates to the task handler via the registry
	 */
	private <T> McpClientSession.RequestHandler<T> adaptTaskHandler(String method,
			TaskManagerHost.TaskRequestHandler taskHandler) {
		return params -> {
			TaskManagerHost.TaskHandlerContext context = createTaskHandlerContext(null,
					(reqMethod, reqParams) -> ClientTaskHandler.this.requestSender.sendRequest(reqMethod, reqParams,
							TaskHandlerRegistry.getResultTypeRefForMethod(reqMethod)),
					(notifMethod, notification) -> ClientTaskHandler.this.notifier.sendNotification(notifMethod,
							notification));

			return this.taskHandlerRegistry.<T>invokeHandler(method, params, context);
		};
	}

	// --------------------------
	// Notification Handler Builder
	// --------------------------

	private static final TypeRef<McpSchema.TaskStatusNotification> TASK_STATUS_NOTIFICATION_TYPE_REF = new TypeRef<>() {
	};

	private McpClientSession.NotificationHandler buildTaskStatusNotificationHandler(
			List<Function<McpSchema.TaskStatusNotification, Mono<Void>>> taskStatusConsumers) {
		return params -> {
			McpSchema.TaskStatusNotification taskStatusNotification = (McpSchema.TaskStatusNotification) this.unmarshalFrom
				.apply(params, TASK_STATUS_NOTIFICATION_TYPE_REF);

			return Flux.fromIterable(taskStatusConsumers)
				.flatMap(consumer -> consumer.apply(taskStatusNotification))
				.onErrorResume(error -> {
					logger.error("Error handling task status notification", error);
					return Mono.empty();
				})
				.then();
		};
	}

}
