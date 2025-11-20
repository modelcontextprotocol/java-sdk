package io.modelcontextprotocol.client;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * A synchronous client interface for the Model Context Protocol (MCP) that wraps an
 * {@link McpAsyncClient} to provide blocking operations.
 *
 * <p>
 * This client interface the MCP specification by delegating to an asynchronous client and
 * blocking on the results. Key features include:
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
 * This implementation interface {@link AutoCloseable} for resource cleanup and provides
 * both immediate and graceful shutdown options. All operations block until completion or
 * timeout, making it suitable for traditional synchronous programming models.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @author Jihoon Kim
 * @author Pin He
 * @see McpClient
 * @see McpAsyncClient
 * @see McpSchema
 */
public interface McpSyncClient extends AutoCloseable {

	/**
	 * Get the current initialization result.
	 * @return the initialization result.
	 */
	McpSchema.InitializeResult getCurrentInitializationResult();

	/**
	 * Get the server capabilities that define the supported features and functionality.
	 * @return The server capabilities
	 */
	McpSchema.ServerCapabilities getServerCapabilities();

	/**
	 * Get the server instructions that provide guidance to the client on how to interact
	 * with this server.
	 * @return The instructions
	 */
	String getServerInstructions();

	/**
	 * Get the server implementation information.
	 * @return The server implementation details
	 */
	McpSchema.Implementation getServerInfo();

	/**
	 * Check if the client-server connection is initialized.
	 * @return true if the client-server connection is initialized
	 */
	boolean isInitialized();

	/**
	 * Get the client capabilities that define the supported features and functionality.
	 * @return The client capabilities
	 */
	McpSchema.ClientCapabilities getClientCapabilities();

	/**
	 * Get the client implementation information.
	 * @return The client implementation details
	 */
	McpSchema.Implementation getClientInfo();

	@Override
	void close();

	/**
	 * Gracefully closes the client connection.
	 * @return true if closed gracefully, false otherwise.
	 */
	boolean closeGracefully();

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
	McpSchema.InitializeResult initialize();

	/**
	 * Send a roots/list_changed notification.
	 */
	void rootsListChangedNotification();

	/**
	 * Add a roots dynamically.
	 */
	void addRoot(McpSchema.Root root);

	/**
	 * Remove a root dynamically.
	 */
	void removeRoot(String rootUri);

	/**
	 * Send a synchronous ping request.
	 * @return
	 */
	Object ping();

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
	McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest);

	/**
	 * Retrieves the list of all tools provided by the server.
	 * @return The list of all tools result containing: - tools: List of available tools,
	 * each with a name, description, and input schema - nextCursor: Optional cursor for
	 * pagination if more tools are available
	 */
	McpSchema.ListToolsResult listTools();

	/**
	 * Retrieves a paginated list of tools provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of tools result containing: - tools: List of available tools, each
	 * with a name, description, and input schema - nextCursor: Optional cursor for
	 * pagination if more tools are available
	 */
	McpSchema.ListToolsResult listTools(String cursor);

	// --------------------------
	// Resources
	// --------------------------

	/**
	 * Retrieves the list of all resources provided by the server.
	 * @return The list of all resources result
	 */
	McpSchema.ListResourcesResult listResources();

	/**
	 * Retrieves a paginated list of resources provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of resources result
	 */
	McpSchema.ListResourcesResult listResources(String cursor);

	/**
	 * Send a resources/read request.
	 * @param resource the resource to read
	 * @return the resource content.
	 */
	McpSchema.ReadResourceResult readResource(McpSchema.Resource resource);

	/**
	 * Send a resources/read request.
	 * @param readResourceRequest the read resource request.
	 * @return the resource content.
	 */
	McpSchema.ReadResourceResult readResource(McpSchema.ReadResourceRequest readResourceRequest);

	/**
	 * Retrieves the list of all resource templates provided by the server.
	 * @return The list of all resource templates result.
	 */
	McpSchema.ListResourceTemplatesResult listResourceTemplates();

	/**
	 * Resource templates allow servers to expose parameterized resources using URI
	 * templates. Arguments may be auto-completed through the completion API.
	 *
	 * Retrieves a paginated list of resource templates provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of resource templates result.
	 */
	McpSchema.ListResourceTemplatesResult listResourceTemplates(String cursor);

	/**
	 * Subscriptions. The protocol supports optional subscriptions to resource changes.
	 * Clients can subscribe to specific resources and receive notifications when they
	 * change.
	 *
	 * Send a resources/subscribe request.
	 * @param subscribeRequest the subscribe request contains the uri of the resource to
	 * subscribe to.
	 */
	void subscribeResource(McpSchema.SubscribeRequest subscribeRequest);

	/**
	 * Send a resources/unsubscribe request.
	 * @param unsubscribeRequest the unsubscribe request contains the uri of the resource
	 * to unsubscribe from.
	 */
	void unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest);

	// --------------------------
	// Prompts
	// --------------------------

	/**
	 * Retrieves the list of all prompts provided by the server.
	 * @return The list of all prompts result.
	 */
	McpSchema.ListPromptsResult listPrompts();

	/**
	 * Retrieves a paginated list of prompts provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return The list of prompts result.
	 */
	McpSchema.ListPromptsResult listPrompts(String cursor);

	McpSchema.GetPromptResult getPrompt(McpSchema.GetPromptRequest getPromptRequest);

	/**
	 * Client can set the minimum logging level it wants to receive from the server.
	 * @param loggingLevel the min logging level
	 */
	void setLoggingLevel(McpSchema.LoggingLevel loggingLevel);

	/**
	 * Send a completion/complete request.
	 * @param completeRequest the completion request contains the prompt or resource
	 * reference and arguments for generating suggestions.
	 * @return the completion result containing suggested values.
	 */
	McpSchema.CompleteResult completeCompletion(McpSchema.CompleteRequest completeRequest);

}
