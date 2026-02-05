/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.experimental.tasks.QueuedMessage;
import io.modelcontextprotocol.experimental.tasks.TaskDefaults;
import io.modelcontextprotocol.experimental.tasks.TaskMessageQueue;
import io.modelcontextprotocol.experimental.tasks.TaskStore;
import io.modelcontextprotocol.experimental.tasks.TaskTypeRefs;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpLoggableSession;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSession;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Represents an asynchronous exchange with a Model Context Protocol (MCP) client. The
 * exchange provides methods to interact with the client and query its capabilities.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 */
public class McpAsyncServerExchange {

	private static final Logger logger = LoggerFactory.getLogger(McpAsyncServerExchange.class);

	private final String sessionId;

	private final McpLoggableSession session;

	private final McpSchema.ClientCapabilities clientCapabilities;

	private final McpSchema.Implementation clientInfo;

	private final McpTransportContext transportContext;

	/**
	 * Optional message queue for enqueuing messages during task execution.
	 */
	private final TaskMessageQueue taskMessageQueue;

	/**
	 * Optional task store for updating task status during side-channeling.
	 */
	private final TaskStore<?> taskStore;

	private static final TypeRef<McpSchema.CreateMessageResult> CREATE_MESSAGE_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<McpSchema.ListRootsResult> LIST_ROOTS_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<McpSchema.ElicitResult> ELICITATION_RESULT_TYPE_REF = new TypeRef<>() {
	};

	public static final TypeRef<Object> OBJECT_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * Create a new asynchronous exchange with the client.
	 * @param session The server session representing a 1-1 interaction.
	 * @param clientCapabilities The client capabilities that define the supported
	 * features and functionality.
	 * @param clientInfo The client implementation information.
	 * @deprecated Use
	 * {@link #McpAsyncServerExchange(String, McpLoggableSession, McpSchema.ClientCapabilities, McpSchema.Implementation, McpTransportContext)}
	 */
	@Deprecated
	public McpAsyncServerExchange(McpSession session, McpSchema.ClientCapabilities clientCapabilities,
			McpSchema.Implementation clientInfo) {
		this.sessionId = null;
		if (!(session instanceof McpLoggableSession)) {
			throw new IllegalArgumentException("Expecting session to be a McpLoggableSession instance");
		}
		this.session = (McpLoggableSession) session;
		this.clientCapabilities = clientCapabilities;
		this.clientInfo = clientInfo;
		this.transportContext = McpTransportContext.EMPTY;
		this.taskMessageQueue = null;
		this.taskStore = null;
	}

	/**
	 * Create a new asynchronous exchange with the client.
	 * @param sessionId The session ID.
	 * @param session The server session representing a 1-1 interaction.
	 * @param clientCapabilities The client capabilities that define the supported
	 * features and functionality.
	 * @param clientInfo The client implementation information.
	 * @param transportContext context associated with the client as extracted from the
	 * transport
	 */
	public McpAsyncServerExchange(String sessionId, McpLoggableSession session,
			McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo,
			McpTransportContext transportContext) {
		this(sessionId, session, clientCapabilities, clientInfo, transportContext, null, null);
	}

	/**
	 * Create a new asynchronous exchange with the client and a task message queue.
	 * @param sessionId The session ID.
	 * @param session The server session representing a 1-1 interaction.
	 * @param clientCapabilities The client capabilities that define the supported
	 * features and functionality.
	 * @param clientInfo The client implementation information.
	 * @param transportContext context associated with the client as extracted from the
	 * transport
	 * @param taskMessageQueue Optional message queue for task message enqueuing
	 */
	public McpAsyncServerExchange(String sessionId, McpLoggableSession session,
			McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo,
			McpTransportContext transportContext, TaskMessageQueue taskMessageQueue) {
		this(sessionId, session, clientCapabilities, clientInfo, transportContext, taskMessageQueue, null);
	}

	/**
	 * Create a new asynchronous exchange with the client, task message queue, and task
	 * store.
	 * @param sessionId The session ID.
	 * @param session The server session representing a 1-1 interaction.
	 * @param clientCapabilities The client capabilities that define the supported
	 * features and functionality.
	 * @param clientInfo The client implementation information.
	 * @param transportContext context associated with the client as extracted from the
	 * transport
	 * @param taskMessageQueue Optional message queue for task message enqueuing
	 * @param taskStore Optional task store for updating task status during
	 * side-channeling
	 */
	public McpAsyncServerExchange(String sessionId, McpLoggableSession session,
			McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo,
			McpTransportContext transportContext, TaskMessageQueue taskMessageQueue, TaskStore<?> taskStore) {
		this.sessionId = sessionId;
		this.session = session;
		this.clientCapabilities = clientCapabilities;
		this.clientInfo = clientInfo;
		this.transportContext = transportContext;
		this.taskMessageQueue = taskMessageQueue;
		this.taskStore = taskStore;
	}

	/**
	 * Get the client capabilities that define the supported features and functionality.
	 * @return The client capabilities
	 */
	public McpSchema.ClientCapabilities getClientCapabilities() {
		return this.clientCapabilities;
	}

	/**
	 * Get the client implementation information.
	 * @return The client implementation details
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.clientInfo;
	}

	/**
	 * Provides the {@link McpTransportContext} associated with the transport layer. For
	 * HTTP transports it can contain the metadata associated with the HTTP request that
	 * triggered the processing.
	 * @return the transport context object
	 */
	public McpTransportContext transportContext() {
		return this.transportContext;
	}

	/**
	 * Provides the Session ID.
	 * @return session ID string
	 */
	public String sessionId() {
		return this.sessionId;
	}

	/**
	 * Get the underlying session for this exchange. This is used internally for
	 * side-channeling operations like sending requests during tasks/result handling.
	 * @return the session
	 */
	public McpLoggableSession getSession() {
		return this.session;
	}

	/**
	 * Create a new message using the sampling capabilities of the client. The Model
	 * Context Protocol (MCP) provides a standardized way for servers to request LLM
	 * sampling ("completions" or "generations") from language models via clients. This
	 * flow allows clients to maintain control over model access, selection, and
	 * permissions while enabling servers to leverage AI capabilities—with no server API
	 * keys necessary. Servers can request text or image-based interactions and optionally
	 * include context from MCP servers in their prompts.
	 * @param createMessageRequest The request to create a new message
	 * @return A Mono that completes when the message has been created
	 * @see McpSchema.CreateMessageRequest
	 * @see McpSchema.CreateMessageResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
	 * Specification</a>
	 */
	public Mono<McpSchema.CreateMessageResult> createMessage(McpSchema.CreateMessageRequest createMessageRequest) {
		return createMessage(createMessageRequest, null);
	}

	/**
	 * Create a new message using the sampling capabilities of the client within a task
	 * context. When a taskId is provided, the request uses side-channeling: the task
	 * status is set to INPUT_REQUIRED and the request is enqueued for delivery via
	 * tasks/result instead of being sent immediately.
	 * @param createMessageRequest The request to create a new message
	 * @param taskId The task ID for side-channeling, or null for immediate send
	 * @return A Mono that completes when the message has been created
	 * @see McpSchema.CreateMessageRequest
	 * @see McpSchema.CreateMessageResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
	 * Specification</a>
	 */
	public Mono<McpSchema.CreateMessageResult> createMessage(McpSchema.CreateMessageRequest createMessageRequest,
			String taskId) {
		if (this.clientCapabilities == null) {
			return Mono.error(new McpError("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.sampling() == null) {
			return Mono.error(new McpError("Client must be configured with sampling capabilities"));
		}

		// Generate a request ID for tracking
		String requestId = UUID.randomUUID().toString();

		// Add related task metadata to request if within task context
		McpSchema.CreateMessageRequest requestWithMeta = createMessageRequest;
		if (taskId != null) {
			Map<String, Object> meta = new java.util.HashMap<>();
			if (createMessageRequest.meta() != null) {
				meta.putAll(createMessageRequest.meta());
			}
			meta.put(McpSchema.RELATED_TASK_META_KEY, McpSchema.RelatedTaskMetadata.builder().taskId(taskId).build());
			requestWithMeta = new McpSchema.CreateMessageRequest(createMessageRequest.messages(),
					createMessageRequest.modelPreferences(), createMessageRequest.systemPrompt(),
					createMessageRequest.includeContext(), createMessageRequest.temperature(),
					createMessageRequest.maxTokens(), createMessageRequest.stopSequences(),
					createMessageRequest.metadata(), createMessageRequest.task(), meta);
		}

		final McpSchema.CreateMessageRequest finalRequest = requestWithMeta;

		// Side-channel flow: set INPUT_REQUIRED, enqueue request (not sent directly),
		// and wait for response via the message queue
		if (taskId != null && this.taskMessageQueue != null && this.taskStore != null) {
			QueuedMessage.Request queuedRequest = new QueuedMessage.Request(requestId,
					McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, finalRequest);

			return this.taskStore
				.updateTaskStatus(taskId, this.sessionId, McpSchema.TaskStatus.INPUT_REQUIRED, "Waiting for sampling")
				.then(this.taskMessageQueue.enqueue(taskId, queuedRequest))
				.then(this.taskMessageQueue.waitForResponse(taskId, requestId,
						Duration.ofMinutes(TaskDefaults.DEFAULT_SIDE_CHANNEL_TIMEOUT_MINUTES)))
				.map(response -> (McpSchema.CreateMessageResult) response.result());
		}

		// No task context or side-channeling not enabled: send immediately
		return this.session.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, finalRequest,
				CREATE_MESSAGE_RESULT_TYPE_REF);
	}

	/**
	 * Creates a new elicitation. MCP provides a standardized way for servers to request
	 * additional information from users through the client during interactions. This flow
	 * allows clients to maintain control over user interactions and data sharing while
	 * enabling servers to gather necessary information dynamically. Servers can request
	 * structured data from users with optional JSON schemas to validate responses.
	 * @param elicitRequest The request to create a new elicitation
	 * @return A Mono that completes when the elicitation has been resolved.
	 * @see McpSchema.ElicitRequest
	 * @see McpSchema.ElicitResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/elicitation/">Elicitation
	 * Specification</a>
	 */
	public Mono<McpSchema.ElicitResult> createElicitation(McpSchema.ElicitRequest elicitRequest) {
		return createElicitation(elicitRequest, null);
	}

	/**
	 * Creates a new elicitation within a task context. When a taskId is provided, the
	 * request uses side-channeling: the task status is set to INPUT_REQUIRED and the
	 * request is enqueued for delivery via tasks/result instead of being sent
	 * immediately.
	 * @param elicitRequest The request to create a new elicitation
	 * @param taskId The task ID for side-channeling, or null for immediate send
	 * @return A Mono that completes when the elicitation has been resolved.
	 * @see McpSchema.ElicitRequest
	 * @see McpSchema.ElicitResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/elicitation/">Elicitation
	 * Specification</a>
	 */
	public Mono<McpSchema.ElicitResult> createElicitation(McpSchema.ElicitRequest elicitRequest, String taskId) {
		if (this.clientCapabilities == null) {
			return Mono.error(new McpError("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.elicitation() == null) {
			return Mono.error(new McpError("Client must be configured with elicitation capabilities"));
		}

		// Generate a request ID for tracking
		String requestId = UUID.randomUUID().toString();

		// Add related task metadata to request if within task context
		McpSchema.ElicitRequest requestWithMeta = elicitRequest;
		if (taskId != null) {
			Map<String, Object> meta = new java.util.HashMap<>();
			if (elicitRequest.meta() != null) {
				meta.putAll(elicitRequest.meta());
			}
			meta.put(McpSchema.RELATED_TASK_META_KEY, McpSchema.RelatedTaskMetadata.builder().taskId(taskId).build());
			requestWithMeta = new McpSchema.ElicitRequest(elicitRequest.message(), elicitRequest.requestedSchema(),
					elicitRequest.task(), meta);
		}

		final McpSchema.ElicitRequest finalRequest = requestWithMeta;

		// Side-channel flow: set INPUT_REQUIRED, enqueue request (not sent directly),
		// and wait for response via the message queue
		if (taskId != null && this.taskMessageQueue != null && this.taskStore != null) {
			logger.debug("createElicitation: Using side-channel flow for task {}", taskId);
			QueuedMessage.Request queuedRequest = new QueuedMessage.Request(requestId,
					McpSchema.METHOD_ELICITATION_CREATE, finalRequest);

			return this.taskStore
				.updateTaskStatus(taskId, this.sessionId, McpSchema.TaskStatus.INPUT_REQUIRED,
						"Waiting for elicitation")
				.doOnSuccess(v -> logger.debug("createElicitation: Set INPUT_REQUIRED for task {}", taskId))
				.then(this.taskMessageQueue.enqueue(taskId, queuedRequest))
				.doOnSuccess(v -> logger.debug("createElicitation: Enqueued request {} for task {}", requestId, taskId))
				.then(this.taskMessageQueue.waitForResponse(taskId, requestId,
						Duration.ofMinutes(TaskDefaults.DEFAULT_SIDE_CHANNEL_TIMEOUT_MINUTES)))
				.doOnSuccess(v -> logger.debug("createElicitation: Got response for request {} on task {}", requestId,
						taskId))
				.map(response -> (McpSchema.ElicitResult) response.result());
		}

		// No task context or side-channeling not enabled: send immediately
		return this.session.sendRequest(McpSchema.METHOD_ELICITATION_CREATE, finalRequest, ELICITATION_RESULT_TYPE_REF);
	}

	/**
	 * Retrieves the list of all roots provided by the client.
	 * @return A Mono that emits the list of roots result.
	 */
	public Mono<McpSchema.ListRootsResult> listRoots() {

		// @formatter:off
		return this.listRoots(McpSchema.FIRST_PAGE)
			.expand(result -> (result.nextCursor() != null) ?
					this.listRoots(result.nextCursor()) : Mono.empty())
			.reduce(new McpSchema.ListRootsResult(new ArrayList<>(), null),
				(allRootsResult, result) -> {
					allRootsResult.roots().addAll(result.roots());
					return allRootsResult;
				})
			.map(result -> new McpSchema.ListRootsResult(Collections.unmodifiableList(result.roots()),
					result.nextCursor()));
		// @formatter:on
	}

	/**
	 * Retrieves a paginated list of roots provided by the client.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that emits the list of roots result containing
	 */
	public Mono<McpSchema.ListRootsResult> listRoots(String cursor) {
		return this.session.sendRequest(McpSchema.METHOD_ROOTS_LIST, new McpSchema.PaginatedRequest(cursor),
				LIST_ROOTS_RESULT_TYPE_REF);
	}

	/**
	 * Send a logging message notification to the client. Messages below the current
	 * minimum logging level will be filtered out.
	 * @param loggingMessageNotification The logging message to send
	 * @return A Mono that completes when the notification has been sent
	 */
	public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {
		return loggingNotification(loggingMessageNotification, null);
	}

	/**
	 * Send a logging message notification to the client. Messages below the current
	 * minimum logging level will be filtered out.
	 *
	 * <p>
	 * When a taskId is provided, the notification is enqueued for delivery via the
	 * side-channel (tasks/result) instead of being sent immediately.
	 * @param loggingMessageNotification The logging message to send
	 * @param taskId The task ID for side-channeling, or null for immediate send
	 * @return A Mono that completes when the notification has been sent or enqueued
	 */
	public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification, String taskId) {

		if (loggingMessageNotification == null) {
			return Mono.error(new McpError("Logging message must not be null"));
		}

		return Mono.defer(() -> {
			if (this.session.isNotificationForLevelAllowed(loggingMessageNotification.level())) {
				return sendOrEnqueueNotification(McpSchema.METHOD_NOTIFICATION_MESSAGE, loggingMessageNotification,
						taskId);
			}
			return Mono.empty();
		});
	}

	/**
	 * Sends a notification to the client that the current progress status has changed for
	 * long-running operations.
	 * @param progressNotification The progress notification to send
	 * @return A Mono that completes when the notification has been sent
	 */
	public Mono<Void> progressNotification(McpSchema.ProgressNotification progressNotification) {
		return progressNotification(progressNotification, null);
	}

	/**
	 * Sends a notification to the client that the current progress status has changed for
	 * long-running operations.
	 *
	 * <p>
	 * When a taskId is provided, the notification is enqueued for delivery via the
	 * side-channel (tasks/result) instead of being sent immediately.
	 * @param progressNotification The progress notification to send
	 * @param taskId The task ID for side-channeling, or null for immediate send
	 * @return A Mono that completes when the notification has been sent or enqueued
	 */
	public Mono<Void> progressNotification(McpSchema.ProgressNotification progressNotification, String taskId) {
		if (progressNotification == null) {
			return Mono.error(new McpError("Progress notification must not be null"));
		}

		return sendOrEnqueueNotification(McpSchema.METHOD_NOTIFICATION_PROGRESS, progressNotification, taskId);
	}

	/**
	 * Sends a task status notification to THIS client only.
	 *
	 * <p>
	 * This method sends a notification to the specific client associated with this
	 * exchange. Use this for targeted notifications when a tool handler needs to update a
	 * specific client about task progress.
	 *
	 * <p>
	 * For broadcasting task status to ALL connected clients, use
	 * {@link McpAsyncServer#notifyTaskStatus(McpSchema.TaskStatusNotification)} instead.
	 * @param notification The task status notification to send
	 * @return A Mono that completes when the notification has been sent
	 * @see McpAsyncServer#notifyTaskStatus(McpSchema.TaskStatusNotification) for
	 * broadcasting to all clients
	 */
	public Mono<Void> taskStatusNotification(McpSchema.TaskStatusNotification notification) {
		return taskStatusNotification(notification, null);
	}

	/**
	 * Sends a task status notification to THIS client only.
	 *
	 * <p>
	 * This method sends a notification to the specific client associated with this
	 * exchange. Use this for targeted notifications when a tool handler needs to update a
	 * specific client about task progress.
	 *
	 * <p>
	 * When a taskId is provided, the notification is enqueued for delivery via the
	 * side-channel (tasks/result) instead of being sent immediately.
	 *
	 * <p>
	 * For broadcasting task status to ALL connected clients, use
	 * {@link McpAsyncServer#notifyTaskStatus(McpSchema.TaskStatusNotification)} instead.
	 * @param notification The task status notification to send
	 * @param taskId The task ID for side-channeling, or null for immediate send
	 * @return A Mono that completes when the notification has been sent or enqueued
	 * @see McpAsyncServer#notifyTaskStatus(McpSchema.TaskStatusNotification) for
	 * broadcasting to all clients
	 */
	public Mono<Void> taskStatusNotification(McpSchema.TaskStatusNotification notification, String taskId) {
		if (notification == null) {
			return Mono.error(new IllegalStateException("Task status notification must not be null"));
		}

		return sendOrEnqueueNotification(McpSchema.METHOD_NOTIFICATION_TASKS_STATUS, notification, taskId);
	}

	/**
	 * Dispatches a notification either by enqueuing it for side-channel delivery (when in
	 * a task context) or by sending it immediately through the session.
	 * @param method the JSON-RPC notification method name
	 * @param notification the notification payload
	 * @param taskId the task ID for side-channeling, or null for immediate send
	 * @return a Mono that completes when the notification has been sent or enqueued
	 */
	private Mono<Void> sendOrEnqueueNotification(String method, McpSchema.Notification notification, String taskId) {
		if (taskId != null && this.taskMessageQueue != null) {
			return this.taskMessageQueue.enqueue(taskId, new QueuedMessage.Notification(method, notification));
		}
		return this.session.sendNotification(method, notification);
	}

	/**
	 * Sends a ping request to the client.
	 * @return A Mono that completes with the client's ping response
	 */
	public Mono<Object> ping() {
		return this.session.sendRequest(McpSchema.METHOD_PING, null, OBJECT_TYPE_REF);
	}

	/**
	 * Set the minimum logging level for the client. Messages below this level will be
	 * filtered out.
	 * @param minLoggingLevel The minimum logging level
	 */
	void setMinLoggingLevel(LoggingLevel minLoggingLevel) {
		Assert.notNull(minLoggingLevel, "minLoggingLevel must not be null");
		this.session.setMinLoggingLevel(minLoggingLevel);
	}

	// --------------------------
	// Client Task Operations
	// --------------------------

	/**
	 * Retrieves a task previously initiated by the server with the client.
	 *
	 * <p>
	 * This method mirrors
	 * {@link io.modelcontextprotocol.client.McpAsyncClient#getTask(McpSchema.GetTaskRequest)},
	 * which is used for when the client has initiated a task with the server.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * var result = exchange.getTask(GetTaskRequest.builder()
	 *     .taskId(taskId)
	 *     .build())
	 *     .block();
	 * }</pre>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param getTaskRequest The request containing the task ID.
	 * @return A Mono that completes with the task information.
	 * @see McpSchema.GetTaskRequest
	 * @see McpSchema.GetTaskResult
	 */
	public Mono<McpSchema.GetTaskResult> getTask(McpSchema.GetTaskRequest getTaskRequest) {
		if (this.clientCapabilities == null) {
			return Mono
				.error(new IllegalStateException("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.tasks() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with tasks capabilities"));
		}
		return this.session.sendRequest(McpSchema.METHOD_TASKS_GET, getTaskRequest, TaskTypeRefs.GET_TASK_RESULT);
	}

	/**
	 * Retrieves a task previously initiated by the server with the client by its ID.
	 *
	 * <p>
	 * This method mirrors
	 * {@link io.modelcontextprotocol.client.McpAsyncClient#getTask(String)}, which is
	 * used for when the client has initiated a task with the server.
	 *
	 * <p>
	 * This is a convenience overload that creates a {@link McpSchema.GetTaskRequest} with
	 * the given task ID.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * var result = exchange.getTask(taskId);
	 * }</pre>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param taskId The task identifier to query.
	 * @return A Mono that completes with the task status and metadata.
	 */
	public Mono<McpSchema.GetTaskResult> getTask(String taskId) {
		return this.getTask(McpSchema.GetTaskRequest.builder().taskId(taskId).build());
	}

	/**
	 * Get the result of a completed task previously initiated by the server with the
	 * client.
	 *
	 * <p>
	 * The result type depends on the original request that created the task. For sampling
	 * requests, use {@code new TypeRef<McpSchema.CreateMessageResult>(){}}. For
	 * elicitation requests, use {@code new TypeRef<McpSchema.ElicitResult>(){}}.
	 *
	 * <p>
	 * This method mirrors
	 * {@link io.modelcontextprotocol.client.McpAsyncClient#getTaskResult(McpSchema.GetTaskPayloadRequest, TypeRef)},
	 * which is used for when the client has initiated a task with the server.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * // For sampling task results:
	 * var result = exchange.getTaskResult(
	 *     new GetTaskPayloadRequest(taskId, null),
	 *     new TypeRef<McpSchema.CreateMessageResult>(){})
	 *     .block();
	 * }</pre>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param <T> The expected result type, must extend
	 * {@link McpSchema.ClientTaskPayloadResult}
	 * @param getTaskPayloadRequest The request containing the task ID.
	 * @param resultTypeRef Type reference for deserializing the result.
	 * @return A Mono that completes with the task result.
	 * @see McpSchema.GetTaskPayloadRequest
	 * @see McpSchema.ClientTaskPayloadResult
	 */
	public <T extends McpSchema.ClientTaskPayloadResult> Mono<T> getTaskResult(
			McpSchema.GetTaskPayloadRequest getTaskPayloadRequest, TypeRef<T> resultTypeRef) {
		if (this.clientCapabilities == null) {
			return Mono
				.error(new IllegalStateException("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.tasks() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with tasks capabilities"));
		}
		return this.session.sendRequest(McpSchema.METHOD_TASKS_RESULT, getTaskPayloadRequest, resultTypeRef);
	}

	/**
	 * Get the result of a completed task previously initiated by the server with the
	 * client by its task ID.
	 *
	 * <p>
	 * This is a convenience overload that creates a
	 * {@link McpSchema.GetTaskPayloadRequest} from the task ID.
	 *
	 * <p>
	 * The result type depends on the original request that created the task. For sampling
	 * requests, use {@code new TypeRef<McpSchema.CreateMessageResult>(){}}. For
	 * elicitation requests, use {@code new TypeRef<McpSchema.ElicitResult>(){}}.
	 *
	 * <p>
	 * This method mirrors
	 * {@link io.modelcontextprotocol.client.McpAsyncClient#getTaskResult(String, TypeRef)},
	 * which is used for when the client has initiated a task with the server.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * // For sampling task results:
	 * var result = exchange.getTaskResult(
	 *     taskId,
	 *     new TypeRef<McpSchema.CreateMessageResult>(){})
	 *     .block();
	 * }</pre>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param <T> The expected result type, must extend
	 * {@link McpSchema.ClientTaskPayloadResult}
	 * @param taskId The task identifier.
	 * @param resultTypeRef Type reference for deserializing the result.
	 * @return A Mono that completes with the task result.
	 * @see McpSchema.GetTaskPayloadRequest
	 * @see McpSchema.ClientTaskPayloadResult
	 */
	public <T extends McpSchema.ClientTaskPayloadResult> Mono<T> getTaskResult(String taskId,
			TypeRef<T> resultTypeRef) {
		return this.getTaskResult(McpSchema.GetTaskPayloadRequest.builder().taskId(taskId).build(), resultTypeRef);
	}

	/**
	 * List all tasks hosted by the client.
	 *
	 * <p>
	 * This method automatically handles pagination, fetching all pages and combining them
	 * into a single result with an unmodifiable list.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @return A Mono that emits the list of all client tasks
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
	 * List tasks hosted by the client with pagination support.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param cursor Pagination cursor from a previous list request
	 * @return A Mono that emits a page of client tasks
	 */
	public Mono<McpSchema.ListTasksResult> listTasks(String cursor) {
		if (this.clientCapabilities == null) {
			return Mono
				.error(new IllegalStateException("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.tasks() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with tasks capabilities"));
		}
		if (this.clientCapabilities.tasks().list() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with tasks.list capability"));
		}
		return this.session.sendRequest(McpSchema.METHOD_TASKS_LIST, new McpSchema.PaginatedRequest(cursor),
				TaskTypeRefs.LIST_TASKS_RESULT);
	}

	/**
	 * Request cancellation of a task hosted by the client.
	 *
	 * <p>
	 * Note that cancellation is cooperative - the client may not honor the cancellation
	 * request, or may take some time to cancel the task.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param cancelTaskRequest The request containing the task ID
	 * @return A Mono that emits the updated task status
	 */
	public Mono<McpSchema.CancelTaskResult> cancelTask(McpSchema.CancelTaskRequest cancelTaskRequest) {
		if (this.clientCapabilities == null) {
			return Mono
				.error(new IllegalStateException("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.tasks() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with tasks capabilities"));
		}
		if (this.clientCapabilities.tasks().cancel() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with tasks.cancel capability"));
		}
		return this.session.sendRequest(McpSchema.METHOD_TASKS_CANCEL, cancelTaskRequest,
				TaskTypeRefs.CANCEL_TASK_RESULT);
	}

	/**
	 * Request cancellation of a task hosted by the client by task ID.
	 *
	 * <p>
	 * This is a convenience overload that creates a {@link McpSchema.CancelTaskRequest}
	 * with the given task ID.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param taskId The task identifier to cancel
	 * @return A Mono that emits the updated task status
	 */
	public Mono<McpSchema.CancelTaskResult> cancelTask(String taskId) {
		Assert.hasText(taskId, "Task ID must not be null or empty");
		return cancelTask(McpSchema.CancelTaskRequest.builder().taskId(taskId).build());
	}

	// --------------------------
	// Task-Augmented Sampling
	// --------------------------

	/**
	 * Low-level method to create a new message using task-augmented sampling. The client
	 * will process the request as a long-running task, allowing the server to poll for
	 * status updates.
	 *
	 * <p>
	 * <strong>Recommendation:</strong> For most use cases, prefer
	 * {@link #createMessageStream} which provides a unified streaming interface that
	 * handles both regular and task-augmented sampling automatically, including polling
	 * and result retrieval.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param createMessageRequest The request to create a new message (must have task
	 * metadata)
	 * @return A Mono that emits the task creation result
	 * @see #createMessageStream
	 */
	public Mono<McpSchema.CreateTaskResult> createMessageTask(McpSchema.CreateMessageRequest createMessageRequest) {
		if (createMessageRequest.task() == null) {
			return Mono.error(new IllegalArgumentException(
					"Task metadata is required for task-augmented sampling. Use createMessage() for regular requests."));
		}
		if (this.clientCapabilities == null) {
			return Mono
				.error(new IllegalStateException("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.sampling() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with sampling capabilities"));
		}
		if (this.clientCapabilities.tasks() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with tasks capabilities"));
		}
		return this.session.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, createMessageRequest,
				TaskTypeRefs.CREATE_TASK_RESULT);
	}

	/**
	 * Create a message and return a stream of response messages, handling both regular
	 * and task-augmented requests automatically.
	 *
	 * <p>
	 * This method provides a unified streaming interface for sampling:
	 * <ul>
	 * <li>For <strong>non-task</strong> requests (when {@code task} field is null):
	 * yields a single {@link McpSchema.ResultMessage} or {@link McpSchema.ErrorMessage}
	 * <li>For <strong>task-augmented</strong> requests: yields
	 * {@link McpSchema.TaskCreatedMessage} → zero or more
	 * {@link McpSchema.TaskStatusMessage} → {@link McpSchema.ResultMessage} or
	 * {@link McpSchema.ErrorMessage}
	 * </ul>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param createMessageRequest The request containing the sampling parameters. If the
	 * {@code task} field is set, the call will be task-augmented.
	 * @return A Flux that emits {@link McpSchema.ResponseMessage} instances
	 */
	public Flux<McpSchema.ResponseMessage<McpSchema.CreateMessageResult>> createMessageStream(
			McpSchema.CreateMessageRequest createMessageRequest) {
		// For non-task requests, just wrap the result in a single message
		if (createMessageRequest.task() == null) {
			return this
				.createMessage(createMessageRequest).<McpSchema.ResponseMessage<McpSchema
						.CreateMessageResult>>map(McpSchema.ResultMessage::of)
				.onErrorResume(error -> {
					McpError mcpError = (error instanceof McpError) ? (McpError) error
							: McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(error.getMessage()).build();
					return Mono.just(McpSchema.ErrorMessage.of(mcpError));
				})
				.flux();
		}

		// For task-augmented requests, handle the full lifecycle
		return Flux.create(sink -> {
			this.createMessageTask(createMessageRequest).subscribe(createResult -> {
				McpSchema.Task task = createResult.task();
				if (task == null) {
					sink.error(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
						.message("Task creation did not return a task")
						.build());
					return;
				}

				// Emit taskCreated message
				sink.next(McpSchema.TaskCreatedMessage.of(task));

				// Start polling for task status
				pollTaskUntilTerminal(task.taskId(), sink, Instant.now(), CREATE_MESSAGE_RESULT_TYPE_REF);
			}, error -> {
				McpError mcpError = (error instanceof McpError) ? (McpError) error
						: McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(error.getMessage()).build();
				sink.next(McpSchema.ErrorMessage.of(mcpError));
				sink.complete();
			});
		});
	}

	// --------------------------
	// Task-Augmented Elicitation
	// --------------------------

	/**
	 * Low-level method to create a new elicitation using task-augmented processing. The
	 * client will process the request as a long-running task, allowing the server to poll
	 * for status updates.
	 *
	 * <p>
	 * <strong>Recommendation:</strong> For most use cases, prefer
	 * {@link #createElicitationStream} which provides a unified streaming interface that
	 * handles both regular and task-augmented elicitation automatically, including
	 * polling and result retrieval.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param elicitRequest The elicitation request (must have task metadata)
	 * @return A Mono that emits the task creation result
	 * @see #createElicitationStream
	 */
	public Mono<McpSchema.CreateTaskResult> createElicitationTask(McpSchema.ElicitRequest elicitRequest) {
		if (elicitRequest.task() == null) {
			return Mono.error(new IllegalArgumentException(
					"Task metadata is required for task-augmented elicitation. Use createElicitation() for regular requests."));
		}
		if (this.clientCapabilities == null) {
			return Mono
				.error(new IllegalStateException("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.elicitation() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with elicitation capabilities"));
		}
		if (this.clientCapabilities.tasks() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with tasks capabilities"));
		}
		return this.session.sendRequest(McpSchema.METHOD_ELICITATION_CREATE, elicitRequest,
				TaskTypeRefs.CREATE_TASK_RESULT);
	}

	/**
	 * Create an elicitation and return a stream of response messages, handling both
	 * regular and task-augmented requests automatically.
	 *
	 * <p>
	 * This method provides a unified streaming interface for elicitation:
	 * <ul>
	 * <li>For <strong>non-task</strong> requests (when {@code task} field is null):
	 * yields a single {@link McpSchema.ResultMessage} or {@link McpSchema.ErrorMessage}
	 * <li>For <strong>task-augmented</strong> requests: yields
	 * {@link McpSchema.TaskCreatedMessage} → zero or more
	 * {@link McpSchema.TaskStatusMessage} → {@link McpSchema.ResultMessage} or
	 * {@link McpSchema.ErrorMessage}
	 * </ul>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param elicitRequest The request containing the elicitation parameters. If the
	 * {@code task} field is set, the call will be task-augmented.
	 * @return A Flux that emits {@link McpSchema.ResponseMessage} instances
	 */
	public Flux<McpSchema.ResponseMessage<McpSchema.ElicitResult>> createElicitationStream(
			McpSchema.ElicitRequest elicitRequest) {
		// For non-task requests, just wrap the result in a single message
		if (elicitRequest.task() == null) {
			return this
				.createElicitation(elicitRequest).<McpSchema.ResponseMessage<McpSchema
						.ElicitResult>>map(McpSchema.ResultMessage::of)
				.onErrorResume(error -> {
					McpError mcpError = (error instanceof McpError) ? (McpError) error
							: McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(error.getMessage()).build();
					return Mono.just(McpSchema.ErrorMessage.of(mcpError));
				})
				.flux();
		}

		// For task-augmented requests, handle the full lifecycle
		return Flux.create(sink -> {
			this.createElicitationTask(elicitRequest).subscribe(createResult -> {
				McpSchema.Task task = createResult.task();
				if (task == null) {
					sink.error(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
						.message("Task creation did not return a task")
						.build());
					return;
				}

				// Emit taskCreated message
				sink.next(McpSchema.TaskCreatedMessage.of(task));

				// Start polling for task status
				pollTaskUntilTerminal(task.taskId(), sink, Instant.now(), ELICITATION_RESULT_TYPE_REF);
			}, error -> {
				McpError mcpError = (error instanceof McpError) ? (McpError) error
						: McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(error.getMessage()).build();
				sink.next(McpSchema.ErrorMessage.of(mcpError));
				sink.complete();
			});
		});
	}

	// --------------------------
	// Task Polling Helpers
	// --------------------------

	/**
	 * Polls client task status until it reaches a terminal state, emitting status updates
	 * and final result.
	 *
	 * <p>
	 * Uses proper reactive composition with {@link Flux#interval} and
	 * {@link Flux#takeUntil} to avoid unbounded subscription chains from recursive
	 * subscribe patterns. The timeout is dynamically calculated based on the task's poll
	 * interval.
	 */
	private <T extends McpSchema.ClientTaskPayloadResult> void pollTaskUntilTerminal(String taskId,
			reactor.core.publisher.FluxSink<McpSchema.ResponseMessage<T>> sink, Instant startTime,
			TypeRef<T> resultTypeRef) {

		// First fetch to get initial state and poll interval
		this.getTask(McpSchema.GetTaskRequest.builder().taskId(taskId).build()).subscribe(initialResult -> {
			McpSchema.Task initialTask = initialResult.toTask();

			// Emit initial status
			sink.next(McpSchema.TaskStatusMessage.of(initialTask));

			// Handle already terminal task
			if (initialTask.isTerminal()) {
				handleTerminalTask(taskId, initialTask, sink, resultTypeRef);
				return;
			}

			// Handle INPUT_REQUIRED - fetch result which blocks until terminal
			if (initialTask.status() == McpSchema.TaskStatus.INPUT_REQUIRED) {
				fetchTaskResultAndComplete(taskId, sink, resultTypeRef);
				return;
			}

			// Set up polling using proper reactive composition
			long pollInterval = initialTask.pollInterval() != null ? initialTask.pollInterval()
					: TaskDefaults.DEFAULT_POLL_INTERVAL_MS;
			Duration timeout = TaskDefaults.calculateTimeout(pollInterval);

			// Use Flux.interval + takeUntil instead of recursive subscribe
			reactor.core.Disposable pollSubscription = Flux.interval(Duration.ofMillis(pollInterval))
				.flatMap(tick -> getTask(McpSchema.GetTaskRequest.builder().taskId(taskId).build()))
				.takeUntil(taskResult -> {
					McpSchema.Task task = taskResult.toTask();
					// Emit status update for each poll
					sink.next(McpSchema.TaskStatusMessage.of(task));
					// Stop when terminal or input_required
					return task.isTerminal() || task.status() == McpSchema.TaskStatus.INPUT_REQUIRED;
				})
				.timeout(timeout)
				.last()
				.subscribe(finalResult -> {
					McpSchema.Task task = finalResult.toTask();
					if (task.isTerminal()) {
						handleTerminalTask(taskId, task, sink, resultTypeRef);
					}
					else if (task.status() == McpSchema.TaskStatus.INPUT_REQUIRED) {
						fetchTaskResultAndComplete(taskId, sink, resultTypeRef);
					}
				}, error -> {
					String errorMsg = error.getMessage() != null ? error.getMessage()
							: "Task polling failed: " + error.getClass().getSimpleName();
					if (error instanceof java.util.concurrent.TimeoutException) {
						errorMsg = "Task polling timed out after " + timeout;
					}
					sink.next(McpSchema.ErrorMessage
						.of(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(errorMsg).build()));
					sink.complete();
				});

			// Register disposal handler for proper cleanup when sink is cancelled
			sink.onDispose(pollSubscription);

		}, error -> {
			String errorMsg = error.getMessage() != null ? error.getMessage()
					: "Failed to get task: " + error.getClass().getSimpleName();
			McpError mcpError = (error instanceof McpError) ? (McpError) error
					: McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(errorMsg).build();
			sink.next(McpSchema.ErrorMessage.of(mcpError));
			sink.complete();
		});
	}

	/**
	 * Handles a client task that has reached a terminal state.
	 */
	private <T extends McpSchema.ClientTaskPayloadResult> void handleTerminalTask(String taskId, McpSchema.Task task,
			reactor.core.publisher.FluxSink<McpSchema.ResponseMessage<T>> sink, TypeRef<T> resultTypeRef) {
		if (task.status() == McpSchema.TaskStatus.COMPLETED) {
			fetchTaskResultAndComplete(taskId, sink, resultTypeRef);
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

	/**
	 * Fetches the client task result and completes the stream.
	 */
	private <T extends McpSchema.ClientTaskPayloadResult> void fetchTaskResultAndComplete(String taskId,
			reactor.core.publisher.FluxSink<McpSchema.ResponseMessage<T>> sink, TypeRef<T> resultTypeRef) {
		this.getTaskResult(McpSchema.GetTaskPayloadRequest.builder().taskId(taskId).build(), resultTypeRef)
			.subscribe(result -> {
				sink.next(McpSchema.ResultMessage.of(result));
				sink.complete();
			}, error -> {
				McpError mcpError = (error instanceof McpError) ? (McpError) error
						: McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(error.getMessage()).build();
				sink.next(McpSchema.ErrorMessage.of(mcpError));
				sink.complete();
			});
	}

}
