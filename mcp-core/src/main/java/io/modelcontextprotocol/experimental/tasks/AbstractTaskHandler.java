/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.util.function.BiFunction;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.Notification;
import io.modelcontextprotocol.spec.McpSchema.Request;
import io.modelcontextprotocol.spec.McpSchema.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Shared base class for task handler implementations on both client and server sides.
 *
 * <p>
 * This class encapsulates the common task lifecycle logic that is identical between
 * {@code ServerTaskToolHandler} and {@code ClientTaskHandler}:
 * <ul>
 * <li>TaskManager creation (DefaultTaskManager vs NullTaskManager) and binding</li>
 * <li>Handler registration via {@link TaskHandlerRegistry}</li>
 * <li>Custom task handler invocation with validation and error handling</li>
 * <li>Task store session validation</li>
 * <li>Close and graceful close lifecycle</li>
 * <li>Accessor methods for TaskStore and TaskManager</li>
 * </ul>
 *
 * <p>
 * Subclasses must implement the transport-specific methods {@link #request} and
 * {@link #notification} which differ between client (delegates to session) and server
 * (broadcasts to all clients or errors).
 *
 * <p>
 * The {@link #findAndInvokeCustomHandler} method is an overridable hook that defaults to
 * returning empty. The server overrides this to look up tool-specific custom handlers by
 * tool name from the originating request.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @param <S> the task store payload type (e.g., ServerTaskPayloadResult or
 * ClientTaskPayloadResult)
 * @see TaskManagerHost
 * @see TaskManager
 */
abstract class AbstractTaskHandler<S extends Result> implements TaskManagerHost {

	private static final Logger logger = LoggerFactory.getLogger(AbstractTaskHandler.class);

	/**
	 * Task store for managing long-running tasks. May be null if tasks are not
	 * configured.
	 */
	protected final TaskStore<S> taskStore;

	/**
	 * Task manager for task orchestration. Handles task lifecycle operations, message
	 * queuing, and handler registration.
	 */
	protected final TaskManager taskManager;

	/**
	 * Registry for task handlers registered by TaskManager during bind(). These are
	 * adapted to the appropriate handler type by subclasses.
	 */
	protected final TaskHandlerRegistry taskHandlerRegistry = new TaskHandlerRegistry();

	/**
	 * Creates a new AbstractTaskHandler.
	 * @param taskStore the task store for managing task state, or null if tasks are not
	 * configured
	 * @param taskOptions the task manager options, or null if tasks are not configured
	 */
	protected AbstractTaskHandler(TaskStore<S> taskStore, TaskManagerOptions taskOptions) {
		this.taskStore = taskStore;

		// Initialize TaskManager based on whether TaskOptions are configured
		if (taskOptions != null && taskStore != null) {
			this.taskManager = new DefaultTaskManager(taskOptions);
		}
		else {
			this.taskManager = NullTaskManager.getInstance();
		}

		// Bind the TaskManager to this handler so it can register handlers and send
		// requests
		this.taskManager.bind(this);
	}

	// --------------------------
	// TaskManagerHost Implementation
	// --------------------------

	@Override
	public void registerHandler(String method, TaskRequestHandler handler) {
		this.taskHandlerRegistry.registerHandler(method, handler);
	}

	@Override
	public <T extends Result> Mono<T> invokeCustomTaskHandler(String taskId, String method, Request request,
			TaskHandlerContext context, Class<T> resultType) {
		if (this.taskStore == null) {
			return Mono.empty();
		}
		return getTaskWithSessionValidation(taskId, context.sessionId())
			.flatMap(storeResult -> findAndInvokeCustomHandler(storeResult, method, request, context, resultType))
			.onErrorResume(e -> {
				logger.debug("invokeCustomTaskHandler: task lookup failed for taskId={}, returning empty", taskId, e);
				return Mono.empty();
			});
	}

	/**
	 * Overridable hook for subclass-specific custom handler lookup. The server overrides
	 * this to look up tool-specific custom handlers by tool name from the originating
	 * request. The client inherits the default which returns empty.
	 * @param <T> the result type
	 * @param storeResult the task store result containing the task and originating
	 * request
	 * @param method the request method (e.g., METHOD_TASKS_GET or METHOD_TASKS_RESULT)
	 * @param request the original request object
	 * @param context the handler context with session information
	 * @param resultType the expected result type class
	 * @return a Mono emitting the handler result, or empty if no custom handler found
	 */
	protected <T extends Result> Mono<T> findAndInvokeCustomHandler(GetTaskFromStoreResult storeResult, String method,
			Request request, TaskHandlerContext context, Class<T> resultType) {
		return Mono.empty();
	}

	// --------------------------
	// Session Validation
	// --------------------------

	/**
	 * Validates that a task exists and is accessible for the given session.
	 * @param taskId the task identifier
	 * @param sessionId the session ID for validation
	 * @return a Mono emitting the task store result, or error if not found
	 */
	protected Mono<GetTaskFromStoreResult> getTaskWithSessionValidation(String taskId, String sessionId) {
		return this.taskStore.getTask(taskId, sessionId)
			.switchIfEmpty(Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
				.message("Task not found (may have expired after TTL)")
				.data("Task ID: " + taskId)
				.build()));
	}

	// --------------------------
	// Handler Context Factory
	// --------------------------

	/**
	 * Creates a {@link TaskHandlerContext} that provides session identification and
	 * message routing capabilities to task request handlers.
	 *
	 * <p>
	 * Both client and server need to create context objects when adapting
	 * {@link TaskRequestHandler} instances to their respective request handler types.
	 * This factory centralizes that creation to avoid duplicating the anonymous class
	 * implementation.
	 * @param sessionId the session ID for the requesting client, or null if not
	 * applicable (e.g., client-side handlers)
	 * @param requestSender sends a request to the remote party; accepts the method name
	 * and params, returns a Mono of the result
	 * @param notificationSender sends a notification to the remote party; accepts the
	 * method name and notification object
	 * @return a new TaskHandlerContext backed by the provided functions
	 */
	protected static TaskHandlerContext createTaskHandlerContext(String sessionId,
			BiFunction<String, Object, Mono<? extends Result>> requestSender,
			BiFunction<String, Notification, Mono<Void>> notificationSender) {
		return new TaskHandlerContext() {
			@Override
			public String sessionId() {
				return sessionId;
			}

			@Override
			@SuppressWarnings("unchecked")
			public <R extends Result> Mono<R> sendRequest(String reqMethod, Object reqParams, Class<R> resultType) {
				return (Mono<R>) requestSender.apply(reqMethod, reqParams);
			}

			@Override
			public Mono<Void> sendNotification(String notifMethod, Notification notification) {
				return notificationSender.apply(notifMethod, notification);
			}
		};
	}

	// --------------------------
	// Lifecycle
	// --------------------------

	/**
	 * Cleanup on immediate close. Shuts down the TaskManager and TaskStore.
	 */
	public void close() {
		this.taskManager.onClose();
		if (this.taskStore != null) {
			this.taskStore.shutdown().block(Duration.ofSeconds(TaskDefaults.TASK_STORE_SHUTDOWN_TIMEOUT_SECONDS));
		}
	}

	/**
	 * Cleanup on graceful close.
	 * @return a Mono that completes when cleanup is done
	 */
	public Mono<Void> closeGracefully() {
		this.taskManager.onClose();
		return this.taskStore != null ? this.taskStore.shutdown() : Mono.empty();
	}

	// --------------------------
	// Accessors
	// --------------------------

	/**
	 * Get the task store used for managing long-running tasks.
	 * @return the task store, or null if tasks are not configured
	 */
	public TaskStore<S> getTaskStore() {
		return this.taskStore;
	}

	/**
	 * Returns the task manager for task orchestration operations.
	 * @return the task manager (never null; returns NullTaskManager if task support is
	 * not configured)
	 */
	public TaskManager taskManager() {
		return this.taskManager;
	}

}
