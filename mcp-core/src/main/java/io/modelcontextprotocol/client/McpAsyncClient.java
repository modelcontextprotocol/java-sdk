package io.modelcontextprotocol.client;

import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * The Model Context Protocol (MCP) client interface that provides asynchronous
 * communication with MCP servers using Project Reactor's Mono and Flux types.
 *
 * <p>
 * This client implements the MCP specification, enabling AI models to interact with
 * external tools and resources through a standardized interface. Key features include:
 * <ul>
 * <li>Asynchronous communication using reactive programming patterns
 * <li>Tool discovery and invocation for server-provided functionality
 * <li>Resource access and management with URI-based addressing
 * <li>Prompt template handling for standardized AI interactions
 * <li>Real-time notifications for tools, resources, and prompts changes
 * <li>Structured logging with configurable severity levels
 * <li>Message sampling for AI model interactions
 * </ul>
 *
 * <p>
 * The client follows a lifecycle:
 * <ol>
 * <li>Initialization - Establishes connection and negotiates capabilities
 * <li>Normal Operation - Handles requests and notifications
 * <li>Graceful Shutdown - Ensures clean connection termination
 * </ol>
 *
 * <p>
 * This interface uses Project Reactor for non-blocking operations, making it suitable for
 * high-throughput scenarios and reactive applications. All operations return Mono or Flux
 * types that can be composed into reactive pipelines.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @author Jihoon Kim
 * @author Anurag Pant
 * @author Pin He
 * @see McpClient
 * @see McpSchema
 * @see McpClientSession
 * @see McpClientTransport
 */
public interface McpAsyncClient {

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
	 * @return The server instructions
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

	/**
	 * Closes the client connection immediately.
	 */
	void close();

	/**
	 * Gracefully closes the client connection.
	 * @return A Mono that completes when the connection is closed
	 */
	Mono<Void> closeGracefully();

	// --------------------------
	// Initialization
	// --------------------------

	/**
	 * The initialization phase should be the first interaction between client and server.
	 * The client will ensure it happens in case it has not been explicitly called and in
	 * case of transport session invalidation.
	 * <p>
	 * During this phase, the client and server:
	 * <ul>
	 * <li>Establish protocol version compatibility</li>
	 * <li>Exchange and negotiate capabilities</li>
	 * <li>Share implementation details</li>
	 * </ul>
	 * <br/>
	 * The client MUST initiate this phase by sending an initialize request containing:
	 * The protocol version the client supports, client's capabilities and clients
	 * implementation information.
	 * <p>
	 * The server MUST respond with its own capabilities and information.
	 * </p>
	 * After successful initialization, the client MUST send an initialized notification
	 * to indicate it is ready to begin normal operations.
	 * @return the initialize result.
	 * @see <a href=
	 * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">MCP
	 * Initialization Spec</a>
	 * </p>
	 */
	Mono<McpSchema.InitializeResult> initialize();

	// --------------------------
	// Basic Utilities
	// --------------------------

	/**
	 * Sends a ping request to the server.
	 * @return A Mono that completes with the server's ping response
	 */
	Mono<Object> ping();

	// --------------------------
	// Roots
	// --------------------------

	/**
	 * Adds a new root to the client's root list.
	 * @param root The root to add.
	 * @return A Mono that completes when the root is added and notifications are sent.
	 */
	Mono<Void> addRoot(McpSchema.Root root);

	/**
	 * Removes a root from the client's root list.
	 * @param rootUri The URI of the root to remove.
	 * @return A Mono that completes when the root is removed and notifications are sent.
	 */
	Mono<Void> removeRoot(String rootUri);

	/**
	 * Manually sends a roots/list_changed notification. The addRoot and removeRoot
	 * methods automatically send the roots/list_changed notification if the client is in
	 * an initialized state.
	 * @return A Mono that completes when the notification is sent.
	 */
	Mono<Void> rootsListChangedNotification();

	// --------------------------
	// Tools
	// --------------------------

	/**
	 * Calls a tool provided by the server. Tools enable servers to expose executable
	 * functionality that can interact with external systems, perform computations, and
	 * take actions in the real world.
	 * @param callToolRequest The request containing the tool name and input parameters.
	 * @return A Mono that emits the result of the tool call, including the output and any
	 * errors.
	 * @see McpSchema.CallToolRequest
	 * @see McpSchema.CallToolResult
	 * @see #listTools()
	 */
	Mono<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest callToolRequest);

	/**
	 * Retrieves the list of all tools provided by the server.
	 * @return A Mono that emits the list of all tools result
	 */
	Mono<McpSchema.ListToolsResult> listTools();

	/**
	 * Retrieves a paginated list of tools provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that emits the list of tools result
	 */
	Mono<McpSchema.ListToolsResult> listTools(String cursor);

	// --------------------------
	// Resources
	// --------------------------

	/**
	 * Retrieves the list of all resources provided by the server. Resources represent any
	 * kind of UTF-8 encoded data that an MCP server makes available to clients, such as
	 * database records, API responses, log files, and more.
	 * @return A Mono that completes with the list of all resources result
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	Mono<McpSchema.ListResourcesResult> listResources();

	/**
	 * Retrieves a paginated list of resources provided by the server. Resources represent
	 * any kind of UTF-8 encoded data that an MCP server makes available to clients, such
	 * as database records, API responses, log files, and more.
	 * @param cursor Optional pagination cursor from a previous list request.
	 * @return A Mono that completes with the list of resources result.
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	Mono<McpSchema.ListResourcesResult> listResources(String cursor);

	/**
	 * Reads the content of a specific resource identified by the provided Resource
	 * object. This method fetches the actual data that the resource represents.
	 * @param resource The resource to read, containing the URI that identifies the
	 * resource.
	 * @return A Mono that completes with the resource content.
	 * @see McpSchema.Resource
	 * @see McpSchema.ReadResourceResult
	 */
	Mono<McpSchema.ReadResourceResult> readResource(McpSchema.Resource resource);

	/**
	 * Reads the content of a specific resource identified by the provided request. This
	 * method fetches the actual data that the resource represents.
	 * @param readResourceRequest The request containing the URI of the resource to read
	 * @return A Mono that completes with the resource content.
	 * @see McpSchema.ReadResourceRequest
	 * @see McpSchema.ReadResourceResult
	 */
	Mono<McpSchema.ReadResourceResult> readResource(McpSchema.ReadResourceRequest readResourceRequest);

	/**
	 * Retrieves the list of all resource templates provided by the server. Resource
	 * templates allow servers to expose parameterized resources using URI templates,
	 * enabling dynamic resource access based on variable parameters.
	 * @return A Mono that completes with the list of all resource templates result
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates();

	/**
	 * Retrieves a paginated list of resource templates provided by the server. Resource
	 * templates allow servers to expose parameterized resources using URI templates,
	 * enabling dynamic resource access based on variable parameters.
	 * @param cursor Optional pagination cursor from a previous list request.
	 * @return A Mono that completes with the list of resource templates result.
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates(String cursor);

	/**
	 * Subscribes to changes in a specific resource. When the resource changes on the
	 * server, the client will receive notifications through the resources change
	 * notification handler.
	 * @param subscribeRequest The subscribe request containing the URI of the resource.
	 * @return A Mono that completes when the subscription is complete.
	 * @see McpSchema.SubscribeRequest
	 * @see #unsubscribeResource(McpSchema.UnsubscribeRequest)
	 */
	Mono<Void> subscribeResource(McpSchema.SubscribeRequest subscribeRequest);

	/**
	 * Cancels an existing subscription to a resource. After unsubscribing, the client
	 * will no longer receive notifications when the resource changes.
	 * @param unsubscribeRequest The unsubscribe request containing the URI of the
	 * resource.
	 * @return A Mono that completes when the unsubscription is complete.
	 * @see McpSchema.UnsubscribeRequest
	 * @see #subscribeResource(McpSchema.SubscribeRequest)
	 */
	Mono<Void> unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest);

	// --------------------------
	// Prompts
	// --------------------------

	/**
	 * Retrieves the list of all prompts provided by the server.
	 * @return A Mono that completes with the list of all prompts result.
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(McpSchema.GetPromptRequest)
	 */
	Mono<McpSchema.ListPromptsResult> listPrompts();

	/**
	 * Retrieves a paginated list of prompts provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that completes with the list of prompts result.
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(McpSchema.GetPromptRequest)
	 */
	Mono<McpSchema.ListPromptsResult> listPrompts(String cursor);

	/**
	 * Retrieves a specific prompt by its ID. This provides the complete prompt template
	 * including all parameters and instructions for generating AI content.
	 * @param getPromptRequest The request containing the ID of the prompt to retrieve.
	 * @return A Mono that completes with the prompt result.
	 * @see McpSchema.GetPromptRequest
	 * @see McpSchema.GetPromptResult
	 * @see #listPrompts()
	 */
	Mono<McpSchema.GetPromptResult> getPrompt(McpSchema.GetPromptRequest getPromptRequest);

	// --------------------------
	// Logging
	// --------------------------

	/**
	 * Sets the minimum logging level for messages received from the server. The client
	 * will only receive log messages at or above the specified severity level.
	 * @param loggingLevel The minimum logging level to receive.
	 * @return A Mono that completes when the logging level is set.
	 * @see McpSchema.LoggingLevel
	 */
	Mono<Void> setLoggingLevel(McpSchema.LoggingLevel loggingLevel);

	// --------------------------
	// Completions
	// --------------------------
	/**
	 * Sends a completion/complete request to generate value suggestions based on a given
	 * reference and argument. This is typically used to provide auto-completion options
	 * for user input fields.
	 * @param completeRequest The request containing the prompt or resource reference and
	 * argument for which to generate completions.
	 * @return A Mono that completes with the result containing completion suggestions.
	 * @see McpSchema.CompleteRequest
	 * @see McpSchema.CompleteResult
	 */
	Mono<McpSchema.CompleteResult> completeCompletion(McpSchema.CompleteRequest completeRequest);

}
