/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.experimental.tasks.TaskMessageQueue;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;

import java.util.List;

/**
 * Represents a synchronous exchange with a Model Context Protocol (MCP) client. The
 * exchange provides methods to interact with the client and query its capabilities.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 */
public class McpSyncServerExchange {

	private final McpAsyncServerExchange exchange;

	/**
	 * Create a new synchronous exchange with the client using the provided asynchronous
	 * implementation as a delegate.
	 * @param exchange The asynchronous exchange to delegate to.
	 */
	public McpSyncServerExchange(McpAsyncServerExchange exchange) {
		this.exchange = exchange;
	}

	/**
	 * Provides the Session ID
	 * @return session ID
	 */
	public String sessionId() {
		return this.exchange.sessionId();
	}

	/**
	 * Get the client capabilities that define the supported features and functionality.
	 * @return The client capabilities
	 */
	public McpSchema.ClientCapabilities getClientCapabilities() {
		return this.exchange.getClientCapabilities();
	}

	/**
	 * Get the client implementation information.
	 * @return The client implementation details
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.exchange.getClientInfo();
	}

	/**
	 * Provides the {@link McpTransportContext} associated with the transport layer. For
	 * HTTP transports it can contain the metadata associated with the HTTP request that
	 * triggered the processing.
	 * @return the transport context object
	 */
	public McpTransportContext transportContext() {
		return this.exchange.transportContext();
	}

	/**
	 * Create a new message using the sampling capabilities of the client. The Model
	 * Context Protocol (MCP) provides a standardized way for servers to request LLM
	 * sampling (“completions” or “generations”) from language models via clients. This
	 * flow allows clients to maintain control over model access, selection, and
	 * permissions while enabling servers to leverage AI capabilities—with no server API
	 * keys necessary. Servers can request text or image-based interactions and optionally
	 * include context from MCP servers in their prompts.
	 * @param createMessageRequest The request to create a new message
	 * @return A result containing the details of the sampling response
	 * @see McpSchema.CreateMessageRequest
	 * @see McpSchema.CreateMessageResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
	 * Specification</a>
	 */
	public McpSchema.CreateMessageResult createMessage(McpSchema.CreateMessageRequest createMessageRequest) {
		return this.exchange.createMessage(createMessageRequest).block();
	}

	/**
	 * Creates a new elicitation. MCP provides a standardized way for servers to request
	 * additional information from users through the client during interactions. This flow
	 * allows clients to maintain control over user interactions and data sharing while
	 * enabling servers to gather necessary information dynamically. Servers can request
	 * structured data from users with optional JSON schemas to validate responses.
	 * @param elicitRequest The request to create a new elicitation
	 * @return A result containing the elicitation response.
	 * @see McpSchema.ElicitRequest
	 * @see McpSchema.ElicitResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/elicitation/">Elicitation
	 * Specification</a>
	 */
	public McpSchema.ElicitResult createElicitation(McpSchema.ElicitRequest elicitRequest) {
		return this.exchange.createElicitation(elicitRequest).block();
	}

	/**
	 * Retrieves the list of all roots provided by the client.
	 * @return The list of roots result.
	 */
	public McpSchema.ListRootsResult listRoots() {
		return this.exchange.listRoots().block();
	}

	/**
	 * Retrieves a paginated list of roots provided by the client.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of roots result
	 */
	public McpSchema.ListRootsResult listRoots(String cursor) {
		return this.exchange.listRoots(cursor).block();
	}

	/**
	 * Send a logging message notification to the client. Messages below the current
	 * minimum logging level will be filtered out.
	 * @param loggingMessageNotification The logging message to send
	 */
	public void loggingNotification(LoggingMessageNotification loggingMessageNotification) {
		this.exchange.loggingNotification(loggingMessageNotification).block();
	}

	/**
	 * Sends a notification to the client that the current progress status has changed for
	 * long-running operations.
	 * @param progressNotification The progress notification to send
	 */
	public void progressNotification(McpSchema.ProgressNotification progressNotification) {
		this.exchange.progressNotification(progressNotification).block();
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
	 * {@link McpSyncServer#notifyTaskStatus(McpSchema.TaskStatusNotification)} instead.
	 * @param notification The task status notification to send
	 * @see McpSyncServer#notifyTaskStatus(McpSchema.TaskStatusNotification) for
	 * broadcasting to all clients
	 */
	public void notifyTaskStatus(McpSchema.TaskStatusNotification notification) {
		this.exchange.notifyTaskStatus(notification).block();
	}

	/**
	 * Gets the current task ID if this exchange is operating within a task context.
	 * <p>
	 * <strong>Warning:</strong> This is an experimental API that may change in future
	 * releases. Use with caution in production environments.
	 * @return The current task ID, or null if not in a task context
	 */
	public String getCurrentTaskId() {
		return this.exchange.getCurrentTaskId();
	}

	/**
	 * Creates a new exchange scoped to the specified task ID.
	 * <p>
	 * The returned exchange will have its task context set, making
	 * {@link #getCurrentTaskId()} return the specified task ID.
	 * <p>
	 * <strong>Warning:</strong> This is an experimental API that may change in future
	 * releases. Use with caution in production environments.
	 * @param taskId The task ID to scope this exchange to
	 * @return A new exchange with task context set
	 */
	public McpSyncServerExchange withTaskContext(String taskId) {
		return new McpSyncServerExchange(this.exchange.withTaskContext(taskId));
	}

	/**
	 * Creates a new exchange scoped to the specified task ID and message queue.
	 * <p>
	 * The returned exchange will have its task context and message queue set.
	 * <p>
	 * <strong>Warning:</strong> This is an experimental API that may change in future
	 * releases. Use with caution in production environments.
	 * @param taskId The task ID to scope this exchange to
	 * @param queue The message queue for task communication
	 * @return A new exchange with task context and message queue set
	 */
	public McpSyncServerExchange withTaskContext(String taskId, TaskMessageQueue queue) {
		return new McpSyncServerExchange(this.exchange.withTaskContext(taskId, queue));
	}

	/**
	 * Sends a synchronous ping request to the client.
	 * @return The ping response from the client
	 */
	public Object ping() {
		return this.exchange.ping().block();
	}

	// --------------------------
	// Client Task Operations
	// --------------------------

	/**
	 * Get the status of a task hosted by the client. This is used when the server has
	 * sent a task-augmented request to the client and needs to poll for status updates.
	 * @param getTaskRequest The request containing the task ID
	 * @return The task status
	 */
	public McpSchema.GetTaskResult getTask(McpSchema.GetTaskRequest getTaskRequest) {
		return this.exchange.getTask(getTaskRequest).block();
	}

	/**
	 * Get the result of a completed task hosted by the client.
	 * @param <T> The expected result type
	 * @param getTaskPayloadRequest The request containing the task ID
	 * @param resultTypeRef Type reference for deserializing the result
	 * @return The task result
	 */
	public <T extends McpSchema.ClientTaskPayloadResult> T getTaskResult(
			McpSchema.GetTaskPayloadRequest getTaskPayloadRequest, TypeRef<T> resultTypeRef) {
		return this.exchange.getTaskResult(getTaskPayloadRequest, resultTypeRef).block();
	}

	/**
	 * List all tasks hosted by the client.
	 *
	 * <p>
	 * This method automatically handles pagination, fetching all pages and combining them
	 * into a single result.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @return The list of all client tasks
	 */
	public McpSchema.ListTasksResult listTasks() {
		return this.exchange.listTasks().block();
	}

	/**
	 * List tasks hosted by the client with pagination support.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param cursor Pagination cursor from a previous list request
	 * @return A page of client tasks
	 */
	public McpSchema.ListTasksResult listTasks(String cursor) {
		return this.exchange.listTasks(cursor).block();
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
	 * @return The updated task status
	 */
	public McpSchema.CancelTaskResult cancelTask(McpSchema.CancelTaskRequest cancelTaskRequest) {
		return this.exchange.cancelTask(cancelTaskRequest).block();
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
	 * @return The updated task status
	 */
	public McpSchema.CancelTaskResult cancelTask(String taskId) {
		return this.exchange.cancelTask(taskId).block();
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
	 * {@link #createMessageStream} which provides a unified interface that handles both
	 * regular and task-augmented sampling automatically, including polling and result
	 * retrieval.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param createMessageRequest The request to create a new message (must have task
	 * metadata)
	 * @return The task creation result
	 * @see #createMessageStream
	 */
	public McpSchema.CreateTaskResult createMessageTask(McpSchema.CreateMessageRequest createMessageRequest) {
		return this.exchange.createMessageTask(createMessageRequest).block();
	}

	/**
	 * Create a message and return a list of response messages, handling both regular and
	 * task-augmented requests automatically.
	 *
	 * <p>
	 * This method blocks until the sampling completes. For non-blocking streaming, use
	 * the async exchange's
	 * {@link McpAsyncServerExchange#createMessageStream(McpSchema.CreateMessageRequest)}
	 * method.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param createMessageRequest The request containing the sampling parameters. If the
	 * {@code task} field is set, the call will be task-augmented.
	 * @return A list of {@link McpSchema.ResponseMessage} instances representing the
	 * progress and result
	 */
	public List<McpSchema.ResponseMessage<McpSchema.CreateMessageResult>> createMessageStream(
			McpSchema.CreateMessageRequest createMessageRequest) {
		return this.exchange.createMessageStream(createMessageRequest).collectList().block();
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
	 * {@link #createElicitationStream} which provides a unified interface that handles
	 * both regular and task-augmented elicitation automatically, including polling and
	 * result retrieval.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param elicitRequest The elicitation request (must have task metadata)
	 * @return The task creation result
	 * @see #createElicitationStream
	 */
	public McpSchema.CreateTaskResult createElicitationTask(McpSchema.ElicitRequest elicitRequest) {
		return this.exchange.createElicitationTask(elicitRequest).block();
	}

	/**
	 * Create an elicitation and return a list of response messages, handling both regular
	 * and task-augmented requests automatically.
	 *
	 * <p>
	 * This method blocks until the elicitation completes. For non-blocking streaming, use
	 * the async exchange's
	 * {@link McpAsyncServerExchange#createElicitationStream(McpSchema.ElicitRequest)}
	 * method.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param elicitRequest The request containing the elicitation parameters. If the
	 * {@code task} field is set, the call will be task-augmented.
	 * @return A list of {@link McpSchema.ResponseMessage} instances representing the
	 * progress and result
	 */
	public List<McpSchema.ResponseMessage<McpSchema.ElicitResult>> createElicitationStream(
			McpSchema.ElicitRequest elicitRequest) {
		return this.exchange.createElicitationStream(elicitRequest).collectList().block();
	}

}
