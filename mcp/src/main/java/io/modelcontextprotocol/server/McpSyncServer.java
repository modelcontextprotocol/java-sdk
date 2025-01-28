/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
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

	/**
	 * Creates a new synchronous server that wraps the provided async server.
	 * @param asyncServer The async server to wrap
	 */
	public McpSyncServer(McpAsyncServer asyncServer) {
		Assert.notNull(asyncServer, "Async server must not be null");
		this.asyncServer = asyncServer;
	}

	/**
	 * Retrieves the list of all roots provided by the client.
	 * @return The list of roots
	 */
	public McpSchema.ListRootsResult listRoots() {
		return this.listRoots(null);
	}

	/**
	 * Retrieves a paginated list of roots provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of roots
	 */
	public McpSchema.ListRootsResult listRoots(String cursor) {
		return this.asyncServer.listRoots(cursor).block();
	}

	/**
	 * Add a new tool handler.
	 * @param toolHandler The tool handler to add
	 */
	public void addTool(McpServerFeatures.SyncToolRegistration toolHandler) {
		this.asyncServer.addTool(McpServerFeatures.AsyncToolRegistration.fromSync(toolHandler)).block();
	}

	/**
	 * Remove a tool handler.
	 * @param toolName The name of the tool handler to remove
	 */
	public void removeTool(String toolName) {
		this.asyncServer.removeTool(toolName).block();
	}

	/**
	 * Add a new resource handler.
	 * @param resourceHandler The resource handler to add
	 */
	public void addResource(McpServerFeatures.SyncResourceRegistration resourceHandler) {
		this.asyncServer.addResource(McpServerFeatures.AsyncResourceRegistration.fromSync(resourceHandler)).block();
	}

	/**
	 * Remove a resource handler.
	 * @param resourceUri The URI of the resource handler to remove
	 */
	public void removeResource(String resourceUri) {
		this.asyncServer.removeResource(resourceUri).block();
	}

	/**
	 * Add a new prompt handler.
	 * @param promptRegistration The prompt registration to add
	 */
	public void addPrompt(McpServerFeatures.SyncPromptRegistration promptRegistration) {
		this.asyncServer.addPrompt(McpServerFeatures.AsyncPromptRegistration.fromSync(promptRegistration)).block();
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
	 * Get the client capabilities that define the supported features and functionality.
	 * @return The client capabilities
	 */
	public ClientCapabilities getClientCapabilities() {
		return this.asyncServer.getClientCapabilities();
	}

	/**
	 * Get the client implementation information.
	 * @return The client implementation details
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.asyncServer.getClientInfo();
	}

	/**
	 * Notify clients that the list of available resources has changed.
	 */
	public void notifyResourcesListChanged() {
		this.asyncServer.notifyResourcesListChanged().block();
	}

	/**
	 * Notify clients that the list of available prompts has changed.
	 */
	public void notifyPromptsListChanged() {
		this.asyncServer.notifyPromptsListChanged().block();
	}

	/**
	 * Send a logging message notification to all clients.
	 * @param loggingMessageNotification The logging message notification to send
	 */
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

	/**
	 * Create a new message using the sampling capabilities of the client. The Model
	 * Context Protocol (MCP) provides a standardized way for servers to request LLM
	 * sampling ("completions" or "generations") from language models via clients.
	 *
	 * <p>
	 * This flow allows clients to maintain control over model access, selection, and
	 * permissions while enabling servers to leverage AI capabilities—with no server API
	 * keys necessary. Servers can request text or image-based interactions and optionally
	 * include context from MCP servers in their prompts.
	 *
	 * <p>
	 * Unlike its async counterpart, this method blocks until the message creation is
	 * complete, making it easier to use in synchronous code paths.
	 * @param createMessageRequest The request to create a new message
	 * @return The result of the message creation
	 * @throws McpError if the client has not been initialized or does not support
	 * sampling capabilities
	 * @throws McpError if the client does not support the createMessage method
	 * @see McpSchema.CreateMessageRequest
	 * @see McpSchema.CreateMessageResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
	 * Specification</a>
	 */
	public McpSchema.CreateMessageResult createMessage(McpSchema.CreateMessageRequest createMessageRequest) {
		return this.asyncServer.createMessage(createMessageRequest).block();
	}

}
