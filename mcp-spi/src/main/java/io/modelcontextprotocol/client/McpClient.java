/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.schema.McpSchema;
import org.reactivestreams.Publisher;

import java.util.List;

/**
 * @author Aliaksei Darafeyeu
 */
public interface McpClient {

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
	 * @return A Publisher that completes when the connection is closed
	 */
	Publisher<Void> closeGracefully();

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
	 */
	Publisher<McpSchema.InitializeResult> initialize();

	/**
	 * Sends a ping request to the server.
	 * @return A Publisher that completes with the server's ping response
	 */
	Publisher<Object> ping();

	/**
	 * Adds a new root to the client's root list.
	 * @param root The root to add.
	 * @return A Publisher that completes when the root is added and notifications are
	 * sent.
	 */
	Publisher<Void> addRoot(McpSchema.Root root);

	/**
	 * Removes a root from the client's root list.
	 * @param rootUri The URI of the root to remove.
	 * @return A Publisher that completes when the root is removed and notifications are
	 * sent.
	 */
	Publisher<Void> removeRoot(String rootUri);

	/**
	 * Manually sends a roots/list_changed notification. The addRoot and removeRoot
	 * methods automatically send the roots/list_changed notification if the client is in
	 * an initialized state.
	 * @return A Publisher that completes when the notification is sent.
	 */
	Publisher<Void> rootsListChangedNotification();

	/**
	 * Calls a tool provided by the server. Tools enable servers to expose executable
	 * functionality that can interact with external systems, perform computations, and
	 * take actions in the real world.
	 * @param callToolRequest The request containing the tool name and input parameters.
	 * @return A Publisher that emits the result of the tool call, including the output
	 * and any errors.
	 * @see McpSchema.CallToolRequest
	 * @see McpSchema.CallToolResult
	 * @see #listTools()
	 */
	Publisher<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest callToolRequest);

	/**
	 * Retrieves the list of all tools provided by the server.
	 * @return A Publisher that emits the list of tools result.
	 */
	Publisher<McpSchema.ListToolsResult> listTools();

	/**
	 * Retrieves a paginated list of tools provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Publisher that emits the list of tools result
	 */
	Publisher<McpSchema.ListToolsResult> listTools(String cursor);

	/**
	 * Retrieves the list of all resources provided by the server. Resources represent any
	 * kind of UTF-8 encoded data that an MCP server makes available to clients, such as
	 * database records, API responses, log files, and more.
	 * @return A Publisher that completes with the list of resources result.
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	Publisher<McpSchema.ListResourcesResult> listResources();

	/**
	 * Retrieves a paginated list of resources provided by the server. Resources represent
	 * any kind of UTF-8 encoded data that an MCP server makes available to clients, such
	 * as database records, API responses, log files, and more.
	 * @param cursor Optional pagination cursor from a previous list request.
	 * @return A Publisher that completes with the list of resources result.
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	Publisher<McpSchema.ListResourcesResult> listResources(String cursor);

	/**
	 * Reads the content of a specific resource identified by the provided Resource
	 * object. This method fetches the actual data that the resource represents.
	 * @param resource The resource to read, containing the URI that identifies the
	 * resource.
	 * @return A Publisher that completes with the resource content.
	 * @see McpSchema.Resource
	 * @see McpSchema.ReadResourceResult
	 */
	Publisher<McpSchema.ReadResourceResult> readResource(McpSchema.Resource resource);

	/**
	 * Reads the content of a specific resource identified by the provided request. This
	 * method fetches the actual data that the resource represents.
	 * @param readResourceRequest The request containing the URI of the resource to read
	 * @return A Publisher that completes with the resource content.
	 * @see McpSchema.ReadResourceRequest
	 * @see McpSchema.ReadResourceResult
	 */
	Publisher<McpSchema.ReadResourceResult> readResource(McpSchema.ReadResourceRequest readResourceRequest);

	/**
	 * Retrieves the list of all resource templates provided by the server. Resource
	 * templates allow servers to expose parameterized resources using URI templates,
	 * enabling dynamic resource access based on variable parameters.
	 * @return A Publisher that completes with the list of resource templates result.
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	Publisher<McpSchema.ListResourceTemplatesResult> listResourceTemplates();

	/**
	 * Retrieves a paginated list of resource templates provided by the server. Resource
	 * templates allow servers to expose parameterized resources using URI templates,
	 * enabling dynamic resource access based on variable parameters.
	 * @param cursor Optional pagination cursor from a previous list request.
	 * @return A Publisher that completes with the list of resource templates result.
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	Publisher<McpSchema.ListResourceTemplatesResult> listResourceTemplates(String cursor);

	/**
	 * Subscribes to changes in a specific resource. When the resource changes on the
	 * server, the client will receive notifications through the resources change
	 * notification handler.
	 * @param subscribeRequest The subscribe request containing the URI of the resource.
	 * @return A Publisher that completes when the subscription is complete.
	 * @see McpSchema.SubscribeRequest
	 * @see #unsubscribeResource(McpSchema.UnsubscribeRequest)
	 */
	Publisher<Void> subscribeResource(McpSchema.SubscribeRequest subscribeRequest);

	/**
	 * Cancels an existing subscription to a resource. After unsubscribing, the client
	 * will no longer receive notifications when the resource changes.
	 * @param unsubscribeRequest The unsubscribe request containing the URI of the
	 * resource.
	 * @return A Publisher that completes when the unsubscription is complete.
	 * @see McpSchema.UnsubscribeRequest
	 * @see #subscribeResource(McpSchema.SubscribeRequest)
	 */
	Publisher<Void> unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest);

	/**
	 * Retrieves the list of all prompts provided by the server.
	 * @return A Publisher that completes with the list of prompts result.
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(McpSchema.GetPromptRequest)
	 */
	Publisher<McpSchema.ListPromptsResult> listPrompts();

	/**
	 * Retrieves a paginated list of prompts provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Publisher that completes with the list of prompts result.
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(McpSchema.GetPromptRequest)
	 */
	Publisher<McpSchema.ListPromptsResult> listPrompts(String cursor);

	/**
	 * Retrieves a specific prompt by its ID. This provides the complete prompt template
	 * including all parameters and instructions for generating AI content.
	 * @param getPromptRequest The request containing the ID of the prompt to retrieve.
	 * @return A Publisher that completes with the prompt result.
	 * @see McpSchema.GetPromptRequest
	 * @see McpSchema.GetPromptResult
	 * @see #listPrompts()
	 */
	Publisher<McpSchema.GetPromptResult> getPrompt(McpSchema.GetPromptRequest getPromptRequest);

	/**
	 * Sets the minimum logging level for messages received from the server. The client
	 * will only receive log messages at or above the specified severity level.
	 * @param loggingLevel The minimum logging level to receive.
	 * @return A Publisher that completes when the logging level is set.
	 * @see McpSchema.LoggingLevel
	 */
	Publisher<Void> setLoggingLevel(McpSchema.LoggingLevel loggingLevel);

	/**
	 * This method is package-private and used for test only. Should not be called by user
	 * code.
	 * @param protocolVersions the Client supported protocol versions.
	 */
	void setProtocolVersions(List<String> protocolVersions);

	/**
	 * Sends a completion/complete request to generate value suggestions based on a given
	 * reference and argument. This is typically used to provide auto-completion options
	 * for user input fields.
	 * @param completeRequest The request containing the prompt or resource reference and
	 * argument for which to generate completions.
	 * @return A Publisher that completes with the result containing completion
	 * suggestions.
	 * @see McpSchema.CompleteRequest
	 * @see McpSchema.CompleteResult
	 */
	Publisher<McpSchema.CompleteResult> completeCompletion(McpSchema.CompleteRequest completeRequest);

}
