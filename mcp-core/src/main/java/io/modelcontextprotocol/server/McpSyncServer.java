/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.concurrent.Executor;

import io.modelcontextprotocol.experimental.tasks.TaskAwareAsyncToolSpecification;
import io.modelcontextprotocol.experimental.tasks.TaskAwareSyncToolSpecification;
import io.modelcontextprotocol.experimental.tasks.TaskMessageQueue;
import io.modelcontextprotocol.experimental.tasks.TaskStore;
import reactor.core.scheduler.Schedulers;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.util.Assert;

/**
 * A synchronous implementation of the Model Context Protocol (MCP) server that wraps
 * {@link McpAsyncServer} to provide blocking operations. This class delegates all
 * operations to an underlying async server instance while providing a simpler,
 * synchronous API for scenarios where reactive programming is not required.
 *
 * <p>
 * The MCP server enables AI models to expose tools, resources, and prompts through a
 * standardized interface. Key features available through this synchronous API include:
 * <ul>
 * <li>Tool registration and management for extending AI model capabilities
 * <li>Resource handling with URI-based addressing for providing context
 * <li>Prompt template management for standardized interactions
 * <li>Real-time client notifications for state changes
 * <li>Structured logging with configurable severity levels
 * <li>Support for client-side AI model sampling
 * </ul>
 *
 * <p>
 * While {@link McpAsyncServer} uses Project Reactor's Mono and Flux types for
 * non-blocking operations, this class converts those into blocking calls, making it more
 * suitable for:
 * <ul>
 * <li>Traditional synchronous applications
 * <li>Simple scripting scenarios
 * <li>Testing and debugging
 * <li>Cases where reactive programming adds unnecessary complexity
 * </ul>
 *
 * <p>
 * The server supports runtime modification of its capabilities through methods like
 * {@link #addTool}, {@link #addResource}, and {@link #addPrompt}, automatically notifying
 * connected clients of changes when configured to do so.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncServer
 * @see McpSchema
 */
public class McpSyncServer {

	/**
	 * The async server to wrap.
	 */
	private final McpAsyncServer asyncServer;

	private final boolean immediateExecution;

	/**
	 * Creates a new synchronous server that wraps the provided async server.
	 * @param asyncServer The async server to wrap
	 */
	public McpSyncServer(McpAsyncServer asyncServer) {
		this(asyncServer, false);
	}

	/**
	 * Creates a new synchronous server that wraps the provided async server.
	 * @param asyncServer The async server to wrap
	 * @param immediateExecution Tools, prompts, and resources handlers execute work
	 * without blocking code offloading. Do NOT set to true if the {@code asyncServer}'s
	 * transport is non-blocking.
	 */
	public McpSyncServer(McpAsyncServer asyncServer, boolean immediateExecution) {
		Assert.notNull(asyncServer, "Async server must not be null");
		this.asyncServer = asyncServer;
		this.immediateExecution = immediateExecution;
	}

	/**
	 * Add a new tool handler.
	 * @param toolHandler The tool handler to add
	 */
	public void addTool(McpServerFeatures.SyncToolSpecification toolHandler) {
		this.asyncServer
			.addTool(McpServerFeatures.AsyncToolSpecification.fromSync(toolHandler, this.immediateExecution))
			.block();
	}

	/**
	 * List all registered tools.
	 * @return A list of all registered tools
	 */
	public List<McpSchema.Tool> listTools() {
		return this.asyncServer.listTools().collectList().block();
	}

	/**
	 * Remove a tool handler.
	 * @param toolName The name of the tool handler to remove
	 */
	public void removeTool(String toolName) {
		this.asyncServer.removeTool(toolName).block();
	}

	/**
	 * Add a new task-aware tool at runtime.
	 *
	 * <p>
	 * Task-aware tools support long-running operations with task lifecycle management
	 * (SEP-1686). The sync specification is converted to async and delegated to the
	 * underlying async server.
	 * @param taskToolSpecification The task-aware tool specification to add
	 */
	public void addTaskTool(TaskAwareSyncToolSpecification taskToolSpecification) {
		Executor executor = this.immediateExecution ? Runnable::run : Schedulers.boundedElastic()::schedule;
		TaskAwareAsyncToolSpecification asyncSpec = TaskAwareAsyncToolSpecification.fromSync(taskToolSpecification,
				executor);
		this.asyncServer.addTaskTool(asyncSpec).block();
	}

	/**
	 * List all registered task-aware tools.
	 * @return A list of all registered task-aware tools
	 */
	public List<McpSchema.Tool> listTaskTools() {
		return this.asyncServer.listTaskTools().collectList().block();
	}

	/**
	 * Remove a task-aware tool.
	 * @param toolName The name of the task-aware tool to remove
	 */
	public void removeTaskTool(String toolName) {
		this.asyncServer.removeTaskTool(toolName).block();
	}

	/**
	 * Sends a task status notification to the client.
	 * <p>
	 * <strong>Warning:</strong> This is an experimental API that may change in future
	 * releases. Use with caution in production environments.
	 * @param notification The task status notification to send
	 */
	public void notifyTaskStatus(McpSchema.TaskStatusNotification notification) {
		this.asyncServer.notifyTaskStatus(notification).block();
	}

	/**
	 * Get the task store used for managing long-running tasks.
	 * <p>
	 * <strong>Warning:</strong> This is an experimental API that may change in future
	 * releases. Use with caution in production environments.
	 * @return The task store, or null if tasks are not enabled
	 */
	public TaskStore<McpSchema.ServerTaskPayloadResult> getTaskStore() {
		return this.asyncServer.getTaskStore();
	}

	/**
	 * Get the task message queue used for task communication during input_required state.
	 * <p>
	 * <strong>Warning:</strong> This is an experimental API that may change in future
	 * releases. Use with caution in production environments.
	 * @return The task message queue, or null if not configured
	 */
	public TaskMessageQueue getTaskMessageQueue() {
		return this.asyncServer.getTaskMessageQueue();
	}

	/**
	 * Add a new resource handler.
	 * @param resourceSpecification The resource specification to add
	 */
	public void addResource(McpServerFeatures.SyncResourceSpecification resourceSpecification) {
		this.asyncServer
			.addResource(McpServerFeatures.AsyncResourceSpecification.fromSync(resourceSpecification,
					this.immediateExecution))
			.block();
	}

	/**
	 * List all registered resources.
	 * @return A list of all registered resources
	 */
	public List<McpSchema.Resource> listResources() {
		return this.asyncServer.listResources().collectList().block();
	}

	/**
	 * Remove a resource handler.
	 * @param resourceUri The URI of the resource handler to remove
	 */
	public void removeResource(String resourceUri) {
		this.asyncServer.removeResource(resourceUri).block();
	}

	/**
	 * Add a new resource template.
	 * @param resourceTemplateSpecification The resource template specification to add
	 */
	public void addResourceTemplate(McpServerFeatures.SyncResourceTemplateSpecification resourceTemplateSpecification) {
		this.asyncServer
			.addResourceTemplate(McpServerFeatures.AsyncResourceTemplateSpecification
				.fromSync(resourceTemplateSpecification, this.immediateExecution))
			.block();
	}

	/**
	 * List all registered resource templates.
	 * @return A list of all registered resource templates
	 */
	public List<McpSchema.ResourceTemplate> listResourceTemplates() {
		return this.asyncServer.listResourceTemplates().collectList().block();
	}

	/**
	 * Remove a resource template.
	 * @param uriTemplate The URI template of the resource template to remove
	 */
	public void removeResourceTemplate(String uriTemplate) {
		this.asyncServer.removeResourceTemplate(uriTemplate).block();
	}

	/**
	 * Add a new prompt handler.
	 * @param promptSpecification The prompt specification to add
	 */
	public void addPrompt(McpServerFeatures.SyncPromptSpecification promptSpecification) {
		this.asyncServer
			.addPrompt(
					McpServerFeatures.AsyncPromptSpecification.fromSync(promptSpecification, this.immediateExecution))
			.block();
	}

	/**
	 * List all registered prompts.
	 * @return A list of all registered prompts
	 */
	public List<McpSchema.Prompt> listPrompts() {
		return this.asyncServer.listPrompts().collectList().block();
	}

	/**
	 * Remove a prompt handler.
	 * @param promptName The name of the prompt handler to remove
	 */
	public void removePrompt(String promptName) {
		this.asyncServer.removePrompt(promptName).block();
	}

	/**
	 * Notify clients that the list of available tools has changed.
	 */
	public void notifyToolsListChanged() {
		this.asyncServer.notifyToolsListChanged().block();
	}

	/**
	 * Get the server capabilities that define the supported features and functionality.
	 * @return The server capabilities
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.asyncServer.getServerCapabilities();
	}

	/**
	 * Get the server implementation information.
	 * @return The server implementation details
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.asyncServer.getServerInfo();
	}

	/**
	 * Notify clients that the list of available resources has changed.
	 */
	public void notifyResourcesListChanged() {
		this.asyncServer.notifyResourcesListChanged().block();
	}

	/**
	 * Notify clients that the resources have updated.
	 */
	public void notifyResourcesUpdated(McpSchema.ResourcesUpdatedNotification resourcesUpdatedNotification) {
		this.asyncServer.notifyResourcesUpdated(resourcesUpdatedNotification).block();
	}

	/**
	 * Notify clients that the list of available prompts has changed.
	 */
	public void notifyPromptsListChanged() {
		this.asyncServer.notifyPromptsListChanged().block();
	}

	/**
	 * This implementation would, incorrectly, broadcast the logging message to all
	 * connected clients, using a single minLoggingLevel for all of them. Similar to the
	 * sampling and roots, the logging level should be set per client session and use the
	 * ServerExchange to send the logging message to the right client.
	 * @param loggingMessageNotification The logging message to send
	 * @deprecated Use
	 * {@link McpSyncServerExchange#loggingNotification(LoggingMessageNotification)}
	 * instead.
	 */
	@Deprecated
	public void loggingNotification(LoggingMessageNotification loggingMessageNotification) {
		this.asyncServer.loggingNotification(loggingMessageNotification).block();
	}

	/**
	 * Close the server gracefully.
	 */
	public void closeGracefully() {
		this.asyncServer.closeGracefully().block();
	}

	/**
	 * Close the server immediately.
	 */
	public void close() {
		this.asyncServer.close();
	}

	/**
	 * Get the underlying async server instance.
	 * @return The wrapped async server
	 */
	public McpAsyncServer getAsyncServer() {
		return this.asyncServer;
	}

}
