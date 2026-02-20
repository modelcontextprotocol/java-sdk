/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.Notification;
import io.modelcontextprotocol.spec.McpSchema.Request;
import io.modelcontextprotocol.spec.McpSchema.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default implementation of {@link TaskManager} that handles task orchestration.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see TaskManager
 * @see NullTaskManager
 */
public class DefaultTaskManager implements TaskManager {

	private static final Logger logger = LoggerFactory.getLogger(DefaultTaskManager.class);

	private static final String RELATED_TASK_META_KEY = "relatedTask";

	private final TaskStore<?> taskStore;

	private final TaskMessageQueue messageQueue;

	private final Duration defaultPollInterval;

	private final Duration pollTimeout;

	// Request resolvers for queued requests awaiting responses
	private final Map<Object, Consumer<Object>> requestResolvers = new ConcurrentHashMap<>();

	private TaskManagerHost host;

	/**
	 * Creates a DefaultTaskManager with the given options.
	 * @param options the configuration options
	 */
	DefaultTaskManager(TaskManagerOptions options) {
		this.taskStore = options.taskStore();
		this.messageQueue = options.messageQueue();
		this.defaultPollInterval = options.defaultPollInterval();
		this.pollTimeout = options.pollTimeout() != null ? options.pollTimeout()
				: Duration.ofMillis(TaskDefaults.DEFAULT_AUTOMATIC_POLLING_TIMEOUT_MS);
	}

	@Override
	public void bind(TaskManagerHost host) {
		this.host = host;

		if (this.taskStore != null) {
			// Register handlers for task-related methods
			host.registerHandler(McpSchema.METHOD_TASKS_GET, this::handleGetTask);
			host.registerHandler(McpSchema.METHOD_TASKS_RESULT, this::handleGetTaskResult);
			host.registerHandler(McpSchema.METHOD_TASKS_LIST, this::handleListTasks);
			host.registerHandler(McpSchema.METHOD_TASKS_CANCEL, this::handleCancelTask);
		}
	}

	@Override
	public InboundRequestResult processInboundRequest(JSONRPCRequest request, InboundRequestContext ctx) {
		// Extract task-related info from request params
		String relatedTaskId = extractRelatedTaskId(request);
		TaskCreationParams taskCreationParams = extractTaskCreationParams(request);

		// Wrap sendNotification to add related-task metadata
		java.util.function.Function<Notification, Mono<Void>> wrappedSendNotification;
		if (relatedTaskId != null) {
			wrappedSendNotification = notification -> ctx.sendNotification()
				.apply(notification, NotificationOptions.withRelatedTask(new RelatedTaskInfo(relatedTaskId)));
		}
		else {
			wrappedSendNotification = notification -> ctx.sendNotification()
				.apply(notification, NotificationOptions.empty());
		}

		// Wrap sendRequest to add related-task metadata
		SendRequestFunction wrappedSendRequest = getSendRequest(ctx, relatedTaskId);

		// Create route response function for queued requests
		java.util.function.Function<JSONRPCResponse, Mono<Boolean>> routeResponse = response -> {
			if (relatedTaskId != null && this.messageQueue != null) {
				return routeResponseToQueue(relatedTaskId, response, ctx.sessionId());
			}
			return Mono.just(false);
		};

		return new InboundRequestResult(wrappedSendNotification, wrappedSendRequest, routeResponse,
				taskCreationParams != null);
	}

	private static SendRequestFunction getSendRequest(InboundRequestContext ctx, String relatedTaskId) {
		SendRequestFunction wrappedSendRequest;
		if (relatedTaskId != null) {
			wrappedSendRequest = new SendRequestFunction() {
				@Override
				public <T extends Result> Mono<T> send(Request request, Class<T> resultType, RequestOptions options) {
					RequestOptions augmented = new RequestOptions(options != null ? options.task() : null,
							new RelatedTaskInfo(relatedTaskId));
					return ctx.sendRequest().send(request, resultType, augmented);
				}
			};
		}
		else {
			wrappedSendRequest = ctx.sendRequest();
		}
		return wrappedSendRequest;
	}

	@Override
	public OutboundRequestResult processOutboundRequest(JSONRPCRequest request, RequestOptions options,
			Object messageId, Consumer<Object> responseHandler, Consumer<Throwable> errorHandler) {

		// Augment request with task params if provided
		// Note: In practice, this would modify the request params - for now we just check
		// if queuing is needed

		String relatedTaskId = options != null && options.relatedTask() != null ? options.relatedTask().taskId() : null;

		if (relatedTaskId != null && this.messageQueue != null) {
			// Queue the request for side-channel delivery
			this.requestResolvers.put(messageId, responseHandler);

			QueuedMessage.Request queuedRequest = new QueuedMessage.Request(messageId, request.method(), null);
			this.messageQueue.enqueue(relatedTaskId, queuedRequest).doOnError(errorHandler).subscribe();

			return new OutboundRequestResult(true);
		}

		return new OutboundRequestResult(false);
	}

	@Override
	public InboundResponseResult processInboundResponse(JSONRPCResponse response, Object messageId) {
		// Check if this response is for a queued request
		Consumer<Object> resolver = this.requestResolvers.remove(messageId);
		if (resolver != null) {
			if (response.error() != null) {
				resolver.accept(new McpError(response.error()));
			}
			else {
				resolver.accept(response.result());
			}
			return new InboundResponseResult(true);
		}

		return new InboundResponseResult(false);
	}

	@Override
	public Mono<OutboundNotificationResult> processOutboundNotification(Notification notification,
			NotificationOptions options) {

		String relatedTaskId = options != null && options.relatedTask() != null ? options.relatedTask().taskId() : null;

		if (relatedTaskId != null && this.messageQueue != null) {
			// Queue the notification for side-channel delivery
			String method = Notification.getNotificationMethod(notification);
			QueuedMessage.Notification queuedNotification = new QueuedMessage.Notification(method, notification);

			return this.messageQueue.enqueue(relatedTaskId, queuedNotification)
				.thenReturn(new OutboundNotificationResult(true, Optional.empty()));
		}

		// Not queued - let caller handle the notification
		return Mono.just(new OutboundNotificationResult(false, Optional.empty()));
	}

	@Override
	public void onClose() {
		this.requestResolvers.clear();
	}

	@Override
	public Optional<TaskStore<?>> taskStore() {
		return Optional.ofNullable(this.taskStore);
	}

	@Override
	public Optional<TaskMessageQueue> messageQueue() {
		return Optional.ofNullable(this.messageQueue);
	}

	@Override
	public Duration defaultPollInterval() {
		return this.defaultPollInterval;
	}

	// === Private helper methods ===

	@SuppressWarnings("unchecked")
	private String extractRelatedTaskId(JSONRPCRequest request) {
		if (request.params() == null) {
			return null;
		}
		try {
			if (request.params() instanceof Map) {
				Map<String, Object> params = (Map<String, Object>) request.params();
				Object meta = params.get("_meta");
				if (meta instanceof Map) {
					Map<String, Object> metaMap = (Map<String, Object>) meta;
					Object relatedTask = metaMap.get(RELATED_TASK_META_KEY);
					if (relatedTask instanceof Map) {
						Map<String, Object> relatedTaskMap = (Map<String, Object>) relatedTask;
						return (String) relatedTaskMap.get("taskId");
					}
				}
			}
		}
		catch (ClassCastException e) {
			logger.debug("Failed to extract related task ID: {}", e.getMessage());
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private TaskCreationParams extractTaskCreationParams(JSONRPCRequest request) {
		if (request.params() == null) {
			return null;
		}
		try {
			if (request.params() instanceof Map) {
				Map<String, Object> params = (Map<String, Object>) request.params();
				Object task = params.get("task");
				if (task instanceof Map) {
					Map<String, Object> taskMap = (Map<String, Object>) task;
					Long ttl = taskMap.containsKey("ttl") ? ((Number) taskMap.get("ttl")).longValue() : null;
					return new TaskCreationParams(ttl);
				}
			}
		}
		catch (ClassCastException e) {
			logger.debug("Failed to extract task creation params: {}", e.getMessage());
		}
		return null;
	}

	private Mono<Boolean> routeResponseToQueue(String taskId, JSONRPCResponse response, String sessionId) {
		// Note: QueuedMessage.Response only supports Result, not errors.
		// Error responses should be handled by the caller's error path.
		// We only queue successful responses here.
		if (response.error() != null) {
			// Errors are not routed through the queue - return false so caller handles
			// them
			return Mono.just(false);
		}

		// For success responses with a Result, we'd need to convert/cast the result
		// This is a simplified implementation that doesn't queue responses
		// Full implementation would need type info to properly deserialize
		return Mono.just(false);
	}

	// === Handler implementations ===

	private Mono<Result> handleGetTask(JSONRPCRequest request, TaskManagerHost.TaskHandlerContext ctx) {
		if (this.taskStore == null) {
			return Mono.error(McpError.builder(ErrorCodes.INTERNAL_ERROR).message("TaskStore not configured").build());
		}

		// Extract taskId from request params
		String taskId = extractTaskIdFromParams(request.params());
		if (taskId == null) {
			return Mono.error(
					McpError.builder(ErrorCodes.INVALID_PARAMS).message("Missing required parameter: taskId").build());
		}

		// Create the typed request for the callback
		McpSchema.GetTaskRequest typedRequest = McpSchema.GetTaskRequest.builder().taskId(taskId).build();

		// First try custom handler via host callback
		return this.host
			.invokeCustomTaskHandler(taskId, McpSchema.METHOD_TASKS_GET, typedRequest, ctx,
					McpSchema.GetTaskResult.class)
			.map(result -> (Result) result)
			.switchIfEmpty(Mono.defer(() -> this.taskStore.getTask(taskId, ctx.sessionId())
				.switchIfEmpty(Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
					.message("Task not found (may have expired after TTL)")
					.data("Task ID: %s".formatted(taskId))
					.build()))
				.map(storeResult -> McpSchema.GetTaskResult.fromTask(storeResult.task()))));
	}

	private Mono<Result> handleGetTaskResult(JSONRPCRequest request, TaskManagerHost.TaskHandlerContext ctx) {
		if (this.taskStore == null) {
			return Mono.error(McpError.builder(ErrorCodes.INTERNAL_ERROR).message("TaskStore not configured").build());
		}

		// Extract taskId from request params
		String taskId = extractTaskIdFromParams(request.params());
		if (taskId == null) {
			return Mono.error(
					McpError.builder(ErrorCodes.INVALID_PARAMS).message("Missing required parameter: taskId").build());
		}

		String sessionId = ctx.sessionId();

		// Create the typed request for the callback
		McpSchema.GetTaskPayloadRequest typedRequest = McpSchema.GetTaskPayloadRequest.builder().taskId(taskId).build();

		// First validate the task exists and is accessible
		return this.taskStore.getTask(taskId, sessionId)
			.switchIfEmpty(Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
				.message("Task not found (may have expired after TTL)")
				.data("Task ID: %s".formatted(taskId))
				.build()))
			.flatMap(storeResult -> {
				McpSchema.Task task = storeResult.task();

				logger.debug("handleGetTaskResult: Task {} status={}, messageQueue={}", taskId, task.status(),
						this.messageQueue != null ? "present" : "null");

				// SIDE-CHANNELING FIRST: Handle INPUT_REQUIRED by processing queued
				// messages
				if (task.status() == McpSchema.TaskStatus.INPUT_REQUIRED && this.messageQueue != null) {
					logger.debug("handleGetTaskResult: Task {} is INPUT_REQUIRED, starting side-channel processing",
							taskId);
					// Process side-channeling, then check custom handler after
					return processQueuedMessagesAndWaitForTerminal(ctx, taskId, sessionId)
						.flatMap(sideChannelResult -> {
							// After side-channeling completes, try custom handler
							return tryCustomHandlerOrDefault(taskId, typedRequest, ctx, sessionId);
						});
				}

				// For terminal or WORKING tasks, try custom handler first
				return tryCustomHandlerOrDefault(taskId, typedRequest, ctx, sessionId);
			});
	}

	/**
	 * Attempts to invoke a custom handler for tasks/result, falling back to default
	 * behavior.
	 */
	private Mono<Result> tryCustomHandlerOrDefault(String taskId, McpSchema.GetTaskPayloadRequest typedRequest,
			TaskManagerHost.TaskHandlerContext ctx, String sessionId) {

		return this.host
			.invokeCustomTaskHandler(taskId, McpSchema.METHOD_TASKS_RESULT, typedRequest, ctx,
					McpSchema.ServerTaskPayloadResult.class)
			.map(result -> (Result) result)
			.switchIfEmpty(Mono.defer(() -> defaultGetTaskResult(taskId, sessionId)));
	}

	/**
	 * Default implementation for tasks/result using TaskStore.
	 */
	private Mono<Result> defaultGetTaskResult(String taskId, String sessionId) {
		return this.taskStore.getTask(taskId, sessionId).flatMap(storeResult -> {
			McpSchema.Task task = storeResult.task();

			// If task is terminal, return the result immediately
			if (task.isTerminal()) {
				logger.debug("defaultGetTaskResult: Task {} is terminal, fetching result", taskId);
				return fetchTaskResult(taskId, sessionId);
			}

			// For WORKING status, watch until terminal
			return watchAndFetchResult(taskId, sessionId);
		});
	}

	/**
	 * Fetches the result for a terminal task.
	 */
	@SuppressWarnings("unchecked")
	private Mono<Result> fetchTaskResult(String taskId, String sessionId) {
		TaskStore<Result> store = (TaskStore<Result>) this.taskStore;
		return store.getTaskResult(taskId, sessionId);
	}

	/**
	 * Watches a task until it reaches terminal state, then fetches the result.
	 */
	private Mono<Result> watchAndFetchResult(String taskId, String sessionId) {
		return this.taskStore.watchTaskUntilTerminal(taskId, sessionId, this.pollTimeout)
			.last()
			.onErrorResume(java.util.concurrent.TimeoutException.class,
					e -> Mono.error(McpError.builder(ErrorCodes.INTERNAL_ERROR)
						.message("Task did not complete within timeout")
						.data("Task ID: %s".formatted(taskId))
						.build()))
			.flatMap(terminalTask -> fetchTaskResult(taskId, sessionId));
	}

	/**
	 * Processes all queued messages for an INPUT_REQUIRED task via side-channeling, then
	 * waits for terminal state.
	 *
	 * <p>
	 * This is the core side-channeling handler: it dequeues messages, sends them to the
	 * client, and enqueues responses back. When the tool's waitForResponse() sees the
	 * response, it unblocks and continues execution.
	 */
	private Mono<Result> processQueuedMessagesAndWaitForTerminal(TaskManagerHost.TaskHandlerContext ctx, String taskId,
			String sessionId) {
		logger.debug("processQueuedMessagesAndWaitForTerminal: Starting side-channel processing for task {}", taskId);

		// Process all queued actionable messages
		return processAllQueuedMessages(ctx, taskId)
			.doOnSuccess(v -> logger
				.debug("processQueuedMessagesAndWaitForTerminal: Finished processing queue for task {}", taskId))
			.then(Mono.defer(() -> pollAndProcessUntilTerminal(ctx, taskId, sessionId)));
	}

	/**
	 * Processes all queued actionable messages for a task.
	 */
	private Mono<Void> processAllQueuedMessages(TaskManagerHost.TaskHandlerContext ctx, String taskId) {
		return this.messageQueue.dequeueAll(taskId)
			.flatMapMany(Flux::fromIterable)
			.concatMap(msg -> processMessage(ctx, msg, taskId)) // Process in order
			.then();
	}

	/**
	 * Processes a single queued message by sending it to the client.
	 */
	private Mono<Void> processMessage(TaskManagerHost.TaskHandlerContext ctx, QueuedMessage msg, String taskId) {
		// Handle Request messages (need to send and wait for response)
		if (msg instanceof QueuedMessage.Request req) {
			return sendRequestAndEnqueueResponse(ctx, req, taskId);
		}

		// Handle Notification messages (no response expected)
		if (msg instanceof QueuedMessage.Notification notif) {
			return sendNotificationToClient(ctx, notif, taskId);
		}

		// Response messages should never be returned by dequeue() - but handle gracefully
		return Mono.empty();
	}

	/**
	 * Sends a request to the client and enqueues the response for waitForResponse() to
	 * pick up.
	 */
	private Mono<Void> sendRequestAndEnqueueResponse(TaskManagerHost.TaskHandlerContext ctx, QueuedMessage.Request req,
			String taskId) {
		String requestId = String.valueOf(req.requestId());

		logger.debug("sendRequestAndEnqueueResponse: Sending {} request {} to client for task {}", req.method(),
				requestId, taskId);

		// Determine the result class based on method
		Class<? extends McpSchema.Result> resultClass = getResultClass(req.method());

		return ctx.sendRequest(req.method(), req.request(), resultClass).flatMap(result -> {
			logger.debug("sendRequestAndEnqueueResponse: Got response for request {}, enqueueing for task {}",
					requestId, taskId);
			// Enqueue response for the tool's waitForResponse() to retrieve
			QueuedMessage.Response response = new QueuedMessage.Response(requestId, result);
			return this.messageQueue.enqueue(taskId, response);
		});
	}

	/**
	 * Returns the appropriate result class for a side-channel method.
	 */
	private Class<? extends McpSchema.Result> getResultClass(String method) {
		return switch (method) {
			case McpSchema.METHOD_ELICITATION_CREATE -> McpSchema.ElicitResult.class;
			case McpSchema.METHOD_SAMPLING_CREATE_MESSAGE -> McpSchema.CreateMessageResult.class;
			default -> throw new IllegalArgumentException("Unsupported side-channel method: " + method);
		};
	}

	/**
	 * Sends a notification to the client without waiting for a response.
	 */
	private Mono<Void> sendNotificationToClient(TaskManagerHost.TaskHandlerContext ctx,
			QueuedMessage.Notification notif, String taskId) {
		McpSchema.Notification notification = TaskMetadataUtils.addRelatedTaskMetadata(taskId, notif.notification());
		return ctx.sendNotification(notif.method(), notification);
	}

	/**
	 * Polls the task and processes messages until it reaches terminal state.
	 */
	private Mono<Result> pollAndProcessUntilTerminal(TaskManagerHost.TaskHandlerContext ctx, String taskId,
			String sessionId) {
		return this.taskStore.getTask(taskId, sessionId)
			.switchIfEmpty(Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
				.message("Task not found during polling")
				.data("Task ID: %s".formatted(taskId))
				.build()))
			.flatMap(storeResult -> {
				McpSchema.Task task = storeResult.task();

				// If terminal, return result
				if (task.isTerminal()) {
					return fetchTaskResult(taskId, sessionId);
				}

				// Determine poll interval for this task
				long interval = task.pollInterval() != null ? task.pollInterval() : this.defaultPollInterval.toMillis();

				// If INPUT_REQUIRED, process any queued messages then continue polling
				// Note: We MUST add a delay after processing messages to avoid an
				// infinite
				// tight loop. When only notifications are queued (no blocking requests),
				// processAllQueuedMessages completes immediately. Without a delay, we'd
				// immediately call pollAndProcessUntilTerminal again, see INPUT_REQUIRED,
				// process empty queue, and repeat - consuming 100% CPU.
				if (task.status() == McpSchema.TaskStatus.INPUT_REQUIRED) {
					return processAllQueuedMessages(ctx, taskId).then(Mono.delay(Duration.ofMillis(interval)))
						.then(Mono.defer(() -> pollAndProcessUntilTerminal(ctx, taskId, sessionId)));
				}

				// Otherwise wait for the poll interval and retry
				return Mono.delay(Duration.ofMillis(interval))
					.then(Mono.defer(() -> pollAndProcessUntilTerminal(ctx, taskId, sessionId)));
			})
			.timeout(this.pollTimeout)
			.onErrorResume(java.util.concurrent.TimeoutException.class,
					e -> Mono.error(McpError.builder(ErrorCodes.INTERNAL_ERROR)
						.message("Task did not complete within timeout")
						.data("Task ID: %s".formatted(taskId))
						.build()));
	}

	private Mono<Result> handleListTasks(JSONRPCRequest request, TaskManagerHost.TaskHandlerContext ctx) {
		if (this.taskStore == null) {
			return Mono.error(McpError.builder(ErrorCodes.INTERNAL_ERROR).message("TaskStore not configured").build());
		}

		// Extract cursor from request params (optional)
		String cursor = extractCursorFromParams(request.params());

		return this.taskStore.listTasks(cursor, ctx.sessionId()).map(result -> (Result) result);
	}

	private Mono<Result> handleCancelTask(JSONRPCRequest request, TaskManagerHost.TaskHandlerContext ctx) {
		if (this.taskStore == null) {
			return Mono.error(McpError.builder(ErrorCodes.INTERNAL_ERROR).message("TaskStore not configured").build());
		}

		// Extract taskId from request params
		String taskId = extractTaskIdFromParams(request.params());
		if (taskId == null) {
			return Mono.error(
					McpError.builder(ErrorCodes.INVALID_PARAMS).message("Missing required parameter: taskId").build());
		}

		return this.taskStore.requestCancellation(taskId, ctx.sessionId())
			.map(task -> (Result) McpSchema.CancelTaskResult.fromTask(task));
	}

	private String extractTaskIdFromParams(Object params) {
		return extractStringFromParams(params, "taskId");
	}

	private String extractCursorFromParams(Object params) {
		return extractStringFromParams(params, "cursor");
	}

	@SuppressWarnings("unchecked")
	private String extractStringFromParams(Object params, String key) {
		if (params == null) {
			return null;
		}
		try {
			if (params instanceof Map) {
				Map<String, Object> paramsMap = (Map<String, Object>) params;
				return (String) paramsMap.get(key);
			}
		}
		catch (ClassCastException e) {
			logger.debug("Failed to extract {} from params: {}", key, e.getMessage());
		}
		return null;
	}

}
