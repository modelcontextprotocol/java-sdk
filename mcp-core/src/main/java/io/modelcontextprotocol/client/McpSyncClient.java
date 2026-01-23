/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.experimental.tasks.TaskStore;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A synchronous client implementation for the Model Context Protocol (MCP) that wraps an
 * {@link McpAsyncClient} to provide blocking operations.
 *
 * <p>
 * This client implements the MCP specification by delegating to an asynchronous client
 * and blocking on the results. Key features include:
 * <ul>
 * <li>Synchronous, blocking API for simpler integration in non-reactive applications
 * <li>Tool discovery and invocation for server-provided functionality
 * <li>Resource access and management with URI-based addressing
 * <li>Prompt template handling for standardized AI interactions
 * <li>Real-time notifications for tools, resources, and prompts changes
 * <li>Structured logging with configurable severity levels
 * </ul>
 *
 * <p>
 * The client follows the same lifecycle as its async counterpart:
 * <ol>
 * <li>Initialization - Establishes connection and negotiates capabilities
 * <li>Normal Operation - Handles requests and notifications
 * <li>Graceful Shutdown - Ensures clean connection termination
 * </ol>
 *
 * <p>
 * This implementation implements {@link AutoCloseable} for resource cleanup and provides
 * both immediate and graceful shutdown options. All operations block until completion or
 * timeout, making it suitable for traditional synchronous programming models.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @author Jihoon Kim
 * @see McpClient
 * @see McpAsyncClient
 * @see McpSchema
 */
public class McpSyncClient implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(McpSyncClient.class);

	// TODO: Consider providing a client config to set this properly
	// this is currently a concern only because AutoCloseable is used - perhaps it
	// is not a requirement?
	private static final long DEFAULT_CLOSE_TIMEOUT_MS = 10_000L;

	private final McpAsyncClient delegate;

	private final Supplier<McpTransportContext> contextProvider;

	/**
	 * Create a new McpSyncClient with the given delegate.
	 * @param delegate the asynchronous kernel on top of which this synchronous client
	 * provides a blocking API.
	 * @param contextProvider the supplier of context before calling any non-blocking
	 * operation on underlying delegate
	 */
	McpSyncClient(McpAsyncClient delegate, Supplier<McpTransportContext> contextProvider) {
		Assert.notNull(delegate, "The delegate can not be null");
		Assert.notNull(contextProvider, "The contextProvider can not be null");
		this.delegate = delegate;
		this.contextProvider = contextProvider;
	}

	/**
	 * Get the current initialization result.
	 * @return the initialization result.
	 */
	public McpSchema.InitializeResult getCurrentInitializationResult() {
		return this.delegate.getCurrentInitializationResult();
	}

	/**
	 * Get the server capabilities that define the supported features and functionality.
	 * @return The server capabilities
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.delegate.getServerCapabilities();
	}

	/**
	 * Get the server instructions that provide guidance to the client on how to interact
	 * with this server.
	 * @return The instructions
	 */
	public String getServerInstructions() {
		return this.delegate.getServerInstructions();
	}

	/**
	 * Get the server implementation information.
	 * @return The server implementation details
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.delegate.getServerInfo();
	}

	/**
	 * Check if the client-server connection is initialized.
	 * @return true if the client-server connection is initialized
	 */
	public boolean isInitialized() {
		return this.delegate.isInitialized();
	}

	/**
	 * Get the client capabilities that define the supported features and functionality.
	 * @return The client capabilities
	 */
	public ClientCapabilities getClientCapabilities() {
		return this.delegate.getClientCapabilities();
	}

	/**
	 * Get the client implementation information.
	 * @return The client implementation details
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.delegate.getClientInfo();
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	public boolean closeGracefully() {
		try {
			this.delegate.closeGracefully().block(Duration.ofMillis(DEFAULT_CLOSE_TIMEOUT_MS));
		}
		catch (RuntimeException e) {
			logger.warn("Client didn't close within timeout of {} ms.", DEFAULT_CLOSE_TIMEOUT_MS, e);
			return false;
		}
		return true;
	}

	/**
	 * The initialization phase MUST be the first interaction between client and server.
	 * During this phase, the client and server:
	 * <ul>
	 * <li>Establish protocol version compatibility</li>
	 * <li>Exchange and negotiate capabilities</li>
	 * <li>Share implementation details</li>
	 * </ul>
	 * <br/>
	 * The client MUST initiate this phase by sending an initialize request containing:
	 * <ul>
	 * <li>The protocol version the client supports</li>
	 * <li>The client's capabilities</li>
	 * <li>Client implementation information</li>
	 * </ul>
	 *
	 * The server MUST respond with its own capabilities and information:
	 * {@link McpSchema.ServerCapabilities}. <br/>
	 * After successful initialization, the client MUST send an initialized notification
	 * to indicate it is ready to begin normal operations.
	 *
	 * <br/>
	 *
	 * <a href=
	 * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">Initialization
	 * Spec</a>
	 * @return the initialize result.
	 */
	public McpSchema.InitializeResult initialize() {
		// TODO: block takes no argument here as we assume the async client is
		// configured with a requestTimeout at all times
		return withProvidedContext(this.delegate.initialize()).block();
	}

	/**
	 * Send a roots/list_changed notification.
	 */
	public void rootsListChangedNotification() {
		withProvidedContext(this.delegate.rootsListChangedNotification()).block();
	}

	/**
	 * Add a roots dynamically.
	 */
	public void addRoot(McpSchema.Root root) {
		withProvidedContext(this.delegate.addRoot(root)).block();
	}

	/**
	 * Remove a root dynamically.
	 */
	public void removeRoot(String rootUri) {
		withProvidedContext(this.delegate.removeRoot(rootUri)).block();
	}

	/**
	 * Send a synchronous ping request.
	 * @return
	 */
	public Object ping() {
		return withProvidedContext(this.delegate.ping()).block();
	}

	// --------------------------
	// Tools
	// --------------------------
	/**
	 * Calls a tool provided by the server. Tools enable servers to expose executable
	 * functionality that can interact with external systems, perform computations, and
	 * take actions in the real world.
	 * @param callToolRequest The request containing: - name: The name of the tool to call
	 * (must match a tool name from tools/list) - arguments: Arguments that conform to the
	 * tool's input schema
	 * @return The tool execution result containing: - content: List of content items
	 * (text, images, or embedded resources) representing the tool's output - isError:
	 * Boolean indicating if the execution failed (true) or succeeded (false/absent)
	 */
	public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
		return withProvidedContext(this.delegate.callTool(callToolRequest)).block();

	}

	/**
	 * Low-level method that invokes a tool with task augmentation, creating a background
	 * task for long-running operations.
	 *
	 * <p>
	 * <strong>Recommendation:</strong> For most use cases, prefer {@link #callToolStream}
	 * which provides a unified interface that handles both regular and task-augmented
	 * tool calls automatically, including polling and result retrieval.
	 *
	 * <p>
	 * When calling a tool with task augmentation, the server creates a task and returns
	 * immediately with a {@link McpSchema.CreateTaskResult} containing the task ID. The
	 * actual tool execution happens asynchronously. Use {@link #getTask} to poll for task
	 * status and {@link #getTaskResult} to retrieve the result once completed.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param callToolRequest The request containing the tool name, parameters, and task
	 * metadata. The {@code task} field must be non-null.
	 * @return The task creation result containing the task ID and initial status.
	 * @throws IllegalArgumentException if the request does not include task metadata
	 * @see #callToolStream
	 * @see McpSchema.CallToolRequest
	 * @see McpSchema.CreateTaskResult
	 * @see McpSchema.TaskMetadata
	 * @see #getTask
	 * @see #getTaskResult
	 */
	public McpSchema.CreateTaskResult callToolTask(McpSchema.CallToolRequest callToolRequest) {
		return withProvidedContext(this.delegate.callToolTask(callToolRequest)).block();
	}

	/**
	 * Calls a tool and returns a list of response messages, handling both regular and
	 * task-augmented tool calls automatically.
	 *
	 * <p>
	 * This method provides a unified interface for tool execution:
	 * <ul>
	 * <li>For <strong>non-task</strong> requests (when {@code task} field is null):
	 * returns a list with a single {@link McpSchema.ResultMessage} or
	 * {@link McpSchema.ErrorMessage}
	 * <li>For <strong>task-augmented</strong> requests: returns a list containing
	 * {@link McpSchema.TaskCreatedMessage} followed by zero or more
	 * {@link McpSchema.TaskStatusMessage} and ending with {@link McpSchema.ResultMessage}
	 * or {@link McpSchema.ErrorMessage}
	 * </ul>
	 *
	 * <p>
	 * This is the recommended way to call tools when you want to support both regular and
	 * long-running tool executions without having to handle the decision logic yourself.
	 *
	 * <p>
	 * <strong>Note:</strong> This method blocks until the tool execution completes. For
	 * non-blocking streaming, use the async client's
	 * {@link McpAsyncClient#callToolStream(McpSchema.CallToolRequest)} method.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * var request = new CallToolRequest("my-tool", Map.of("arg", "value"),
	 *     new TaskMetadata(60000L), null);  // Optional task metadata
	 *
	 * client.callToolStream(request).forEach(message -> {
	 *     switch (message) {
	 *         case TaskCreatedMessage<CallToolResult> tc ->
	 *             System.out.println("Task created: " + tc.task().taskId());
	 *         case TaskStatusMessage<CallToolResult> ts ->
	 *             System.out.println("Status: " + ts.task().status());
	 *         case ResultMessage<CallToolResult> r ->
	 *             System.out.println("Result: " + r.result());
	 *         case ErrorMessage<CallToolResult> e ->
	 *             System.err.println("Error: " + e.error().getMessage());
	 *     }
	 * });
	 * }</pre>
	 * @param callToolRequest The request containing the tool name and arguments. If the
	 * {@code task} field is set, the call will be task-augmented.
	 * @return A stream of {@link McpSchema.ResponseMessage} instances representing the
	 * progress and result of the tool call.
	 * @see McpSchema.ResponseMessage
	 * @see McpSchema.TaskCreatedMessage
	 * @see McpSchema.TaskStatusMessage
	 * @see McpSchema.ResultMessage
	 * @see McpSchema.ErrorMessage
	 */
	public Stream<McpSchema.ResponseMessage<McpSchema.CallToolResult>> callToolStream(
			McpSchema.CallToolRequest callToolRequest) {
		return withProvidedContextFlux(this.delegate.callToolStream(callToolRequest)).toStream();
	}

	/**
	 * Retrieves the list of all tools provided by the server.
	 * @return The list of all tools result containing: - tools: List of available tools,
	 * each with a name, description, and input schema - nextCursor: Optional cursor for
	 * pagination if more tools are available
	 */
	public McpSchema.ListToolsResult listTools() {
		return withProvidedContext(this.delegate.listTools()).block();
	}

	/**
	 * Retrieves a paginated list of tools provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of tools result containing: - tools: List of available tools, each
	 * with a name, description, and input schema - nextCursor: Optional cursor for
	 * pagination if more tools are available
	 */
	public McpSchema.ListToolsResult listTools(String cursor) {
		return withProvidedContext(this.delegate.listTools(cursor)).block();

	}

	// --------------------------
	// Resources
	// --------------------------

	/**
	 * Retrieves the list of all resources provided by the server.
	 * @return The list of all resources result
	 */
	public McpSchema.ListResourcesResult listResources() {
		return withProvidedContext(this.delegate.listResources()).block();

	}

	/**
	 * Retrieves a paginated list of resources provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of resources result
	 */
	public McpSchema.ListResourcesResult listResources(String cursor) {
		return withProvidedContext(this.delegate.listResources(cursor)).block();

	}

	/**
	 * Send a resources/read request.
	 * @param resource the resource to read
	 * @return the resource content.
	 */
	public McpSchema.ReadResourceResult readResource(McpSchema.Resource resource) {
		return withProvidedContext(this.delegate.readResource(resource)).block();

	}

	/**
	 * Send a resources/read request.
	 * @param readResourceRequest the read resource request.
	 * @return the resource content.
	 */
	public McpSchema.ReadResourceResult readResource(McpSchema.ReadResourceRequest readResourceRequest) {
		return withProvidedContext(this.delegate.readResource(readResourceRequest)).block();

	}

	/**
	 * Retrieves the list of all resource templates provided by the server.
	 * @return The list of all resource templates result.
	 */
	public McpSchema.ListResourceTemplatesResult listResourceTemplates() {
		return withProvidedContext(this.delegate.listResourceTemplates()).block();

	}

	/**
	 * Resource templates allow servers to expose parameterized resources using URI
	 * templates. Arguments may be auto-completed through the completion API.
	 *
	 * Retrieves a paginated list of resource templates provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of resource templates result.
	 */
	public McpSchema.ListResourceTemplatesResult listResourceTemplates(String cursor) {
		return withProvidedContext(this.delegate.listResourceTemplates(cursor)).block();

	}

	/**
	 * Subscriptions. The protocol supports optional subscriptions to resource changes.
	 * Clients can subscribe to specific resources and receive notifications when they
	 * change.
	 *
	 * Send a resources/subscribe request.
	 * @param subscribeRequest the subscribe request contains the uri of the resource to
	 * subscribe to.
	 */
	public void subscribeResource(McpSchema.SubscribeRequest subscribeRequest) {
		withProvidedContext(this.delegate.subscribeResource(subscribeRequest)).block();

	}

	/**
	 * Send a resources/unsubscribe request.
	 * @param unsubscribeRequest the unsubscribe request contains the uri of the resource
	 * to unsubscribe from.
	 */
	public void unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest) {
		withProvidedContext(this.delegate.unsubscribeResource(unsubscribeRequest)).block();

	}

	// --------------------------
	// Prompts
	// --------------------------

	/**
	 * Retrieves the list of all prompts provided by the server.
	 * @return The list of all prompts result.
	 */
	public ListPromptsResult listPrompts() {
		return withProvidedContext(this.delegate.listPrompts()).block();
	}

	/**
	 * Retrieves a paginated list of prompts provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of prompts result.
	 */
	public ListPromptsResult listPrompts(String cursor) {
		return withProvidedContext(this.delegate.listPrompts(cursor)).block();

	}

	public GetPromptResult getPrompt(GetPromptRequest getPromptRequest) {
		return withProvidedContext(this.delegate.getPrompt(getPromptRequest)).block();
	}

	/**
	 * Client can set the minimum logging level it wants to receive from the server.
	 * @param loggingLevel the min logging level
	 */
	public void setLoggingLevel(McpSchema.LoggingLevel loggingLevel) {
		withProvidedContext(this.delegate.setLoggingLevel(loggingLevel)).block();

	}

	/**
	 * Send a completion/complete request.
	 * @param completeRequest the completion request contains the prompt or resource
	 * reference and arguments for generating suggestions.
	 * @return the completion result containing suggested values.
	 */
	public McpSchema.CompleteResult completeCompletion(McpSchema.CompleteRequest completeRequest) {
		return withProvidedContext(this.delegate.completeCompletion(completeRequest)).block();

	}

	// ---------------------------------------
	// Tasks (Experimental)
	// ---------------------------------------

	/**
	 * Returns the task store used for client-side task hosting.
	 * @return the task store, or null if client-side task hosting is not configured
	 */
	public TaskStore<McpSchema.ClientTaskPayloadResult> getTaskStore() {
		return this.delegate.getTaskStore();
	}

	/**
	 * Retrieves a task previously initiated by the client with the server.
	 *
	 * <p>
	 * This method mirrors
	 * {@link io.modelcontextprotocol.server.McpSyncServerExchange#getTask(McpSchema.GetTaskRequest)},
	 * which is used for when the server has initiated a task with the client.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * var result = client.getTask(GetTaskRequest.builder()
	 *     .taskId(taskId)
	 *     .build());
	 * }</pre>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param getTaskRequest The request containing the task ID.
	 * @return The task information.
	 * @see McpSchema.GetTaskRequest
	 * @see McpSchema.GetTaskResult
	 */
	public McpSchema.GetTaskResult getTask(McpSchema.GetTaskRequest getTaskRequest) {
		return withProvidedContext(this.delegate.getTask(getTaskRequest)).block();
	}

	/**
	 * Retrieves a task previously initiated by the client with the server by its ID.
	 *
	 * <p>
	 * This method mirrors
	 * {@link io.modelcontextprotocol.server.McpSyncServerExchange#getTask(String)}, which
	 * is used for when the server has initiated a task with the client.
	 *
	 * <p>
	 * This is a convenience overload that creates a {@link McpSchema.GetTaskRequest} with
	 * the given task ID.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * var result = client.getTask(taskId);
	 * }</pre>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param taskId The task identifier to query.
	 * @return The task information.
	 */
	public McpSchema.GetTaskResult getTask(String taskId) {
		Assert.hasText(taskId, "Task ID must not be null or empty");
		return withProvidedContext(this.delegate.getTask(taskId)).block();
	}

	/**
	 * Get the result of a completed task previously initiated by the client with the
	 * server.
	 *
	 * <p>
	 * The result type depends on the original request that created the task. For tool
	 * calls, use {@code new TypeRef<McpSchema.CallToolResult>(){}}.
	 *
	 * <p>
	 * This method mirrors
	 * {@link io.modelcontextprotocol.server.McpSyncServerExchange#getTaskResult(McpSchema.GetTaskPayloadRequest, TypeRef)},
	 * which is used for when the server has initiated a task with the client.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * // For tool task results:
	 * var result = client.getTaskResult(
	 *     GetTaskPayloadRequest.builder().taskId(taskId).build(),
	 *     new TypeRef<McpSchema.CallToolResult>(){});
	 * }</pre>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param <T> The expected result type, must extend
	 * {@link McpSchema.ServerTaskPayloadResult}
	 * @param getTaskPayloadRequest The request containing the task ID.
	 * @param resultTypeRef Type reference for deserializing the result.
	 * @return The task result.
	 * @see McpSchema.GetTaskPayloadRequest
	 * @see McpSchema.ServerTaskPayloadResult
	 */
	public <T extends McpSchema.ServerTaskPayloadResult> T getTaskResult(
			McpSchema.GetTaskPayloadRequest getTaskPayloadRequest, TypeRef<T> resultTypeRef) {
		return withProvidedContext(this.delegate.getTaskResult(getTaskPayloadRequest, resultTypeRef)).block();
	}

	/**
	 * Get the result of a completed task previously initiated by the client with the
	 * server by its task ID.
	 *
	 * <p>
	 * This is a convenience overload that creates a
	 * {@link McpSchema.GetTaskPayloadRequest} from the task ID.
	 *
	 * <p>
	 * The result type depends on the original request that created the task. For tool
	 * calls, use {@code new TypeRef<McpSchema.CallToolResult>(){}}.
	 *
	 * <p>
	 * This method mirrors
	 * {@link io.modelcontextprotocol.server.McpSyncServerExchange#getTaskResult(String, TypeRef)},
	 * which is used for when the server has initiated a task with the client.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * // For tool task results:
	 * var result = client.getTaskResult(
	 *     taskId,
	 *     new TypeRef<McpSchema.CallToolResult>(){});
	 * }</pre>
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param <T> The expected result type, must extend
	 * {@link McpSchema.ServerTaskPayloadResult}
	 * @param taskId The task identifier.
	 * @param resultTypeRef Type reference for deserializing the result.
	 * @return The task result.
	 * @see McpSchema.GetTaskPayloadRequest
	 * @see McpSchema.ServerTaskPayloadResult
	 */
	public <T extends McpSchema.ServerTaskPayloadResult> T getTaskResult(String taskId, TypeRef<T> resultTypeRef) {
		Assert.hasText(taskId, "Task ID must not be null or empty");
		return withProvidedContext(this.delegate.getTaskResult(taskId, resultTypeRef)).block();
	}

	/**
	 * List all tasks known by the server.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @return The list of all tasks.
	 * @see McpSchema.ListTasksResult
	 */
	public McpSchema.ListTasksResult listTasks() {
		return withProvidedContext(this.delegate.listTasks()).block();
	}

	/**
	 * List tasks known by the server with pagination support.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param cursor Pagination cursor from a previous list request.
	 * @return A page of tasks.
	 * @see McpSchema.ListTasksResult
	 */
	public McpSchema.ListTasksResult listTasks(String cursor) {
		return withProvidedContext(this.delegate.listTasks(cursor)).block();
	}

	/**
	 * Request cancellation of a task.
	 *
	 * <p>
	 * Note that cancellation is cooperative - the server may not honor the cancellation
	 * request, or may take some time to cancel the task.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param cancelTaskRequest The request containing the task ID.
	 * @return The updated task status.
	 * @see McpSchema.CancelTaskRequest
	 * @see McpSchema.CancelTaskResult
	 */
	public McpSchema.CancelTaskResult cancelTask(McpSchema.CancelTaskRequest cancelTaskRequest) {
		return withProvidedContext(this.delegate.cancelTask(cancelTaskRequest)).block();
	}

	/**
	 * Request cancellation of a task by ID.
	 *
	 * <p>
	 * This is a convenience overload that creates a {@link McpSchema.CancelTaskRequest}
	 * with the given task ID.
	 *
	 * <p>
	 * <strong>Note:</strong> This is an experimental feature that may change in future
	 * releases.
	 * @param taskId The task identifier to cancel.
	 * @return The updated task status.
	 */
	public McpSchema.CancelTaskResult cancelTask(String taskId) {
		Assert.hasText(taskId, "Task ID must not be null or empty");
		return cancelTask(McpSchema.CancelTaskRequest.builder().taskId(taskId).build());
	}

	/**
	 * For a given action, on assembly, capture the "context" via the
	 * {@link #contextProvider} and store it in the Reactor context.
	 * @param action the action to perform
	 * @return the result of the action
	 */
	private <T> Mono<T> withProvidedContext(Mono<T> action) {
		return action.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, this.contextProvider.get()));
	}

	/**
	 * For a given Flux action, on assembly, capture the "context" via the
	 * {@link #contextProvider} and store it in the Reactor context.
	 * @param action the flux action to perform
	 * @return the flux with context applied
	 */
	private <T> Flux<T> withProvidedContextFlux(Flux<T> action) {
		return action.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, this.contextProvider.get()));
	}

}
