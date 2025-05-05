/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.schema.McpSchema;
import org.reactivestreams.Publisher;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * @author Aliaksei Darafeyeu
 */
public interface McpServer {

	interface AsyncPromptSpecification {

		McpSchema.Prompt prompt();

		BiFunction<McpServerExchange, McpSchema.GetPromptRequest, Publisher<McpSchema.GetPromptResult>> promptHandler();

	}

	interface AsyncResourceSpecification {

		McpSchema.Resource resource();

		BiFunction<McpServerExchange, McpSchema.ReadResourceRequest, Publisher<McpSchema.ReadResourceResult>> readHandler();

	}

	interface AsyncToolSpecification {

		McpSchema.Tool tool();

		BiFunction<McpServerExchange, Map<String, Object>, Publisher<McpSchema.CallToolResult>> call();

	}

	/**
	 * Get the server capabilities that define the supported features and functionality.
	 * @return The server capabilities
	 */
	McpSchema.ServerCapabilities getServerCapabilities();

	/**
	 * Get the server implementation information.
	 * @return The server implementation details
	 */
	McpSchema.Implementation getServerInfo();

	/**
	 * Gracefully closes the server, allowing any in-progress operations to complete.
	 * @return A Publisher that completes when the server has been closed
	 */
	Publisher<Void> closeGracefully();

	/**
	 * Close the server immediately.
	 */
	void close();

	/**
	 * Add a new tool specification at runtime.
	 * @param toolSpecification The tool specification to add
	 * @return Publisher that completes when clients have been notified of the change
	 */
	Publisher<Void> addTool(AsyncToolSpecification toolSpecification);

	/**
	 * Remove a tool handler at runtime.
	 * @param toolName The name of the tool handler to remove
	 * @return Publisher that completes when clients have been notified of the change
	 */
	Publisher<Void> removeTool(String toolName);

	/**
	 * Notifies clients that the list of available tools has changed.
	 * @return A Publisher that completes when all clients have been notified
	 */
	Publisher<Void> notifyToolsListChanged();

	/**
	 * Add a new resource handler at runtime.
	 * @param resourceHandler The resource handler to add
	 * @return Publisher that completes when clients have been notified of the change
	 */
	Publisher<Void> addResource(AsyncResourceSpecification resourceHandler);

	/**
	 * Remove a resource handler at runtime.
	 * @param resourceUri The URI of the resource handler to remove
	 * @return Publisher that completes when clients have been notified of the change
	 */
	Publisher<Void> removeResource(String resourceUri);

	/**
	 * Notifies clients that the list of available resources has changed.
	 * @return A Publisher that completes when all clients have been notified
	 */
	Publisher<Void> notifyResourcesListChanged();

	/**
	 * Add a new prompt handler at runtime.
	 * @param promptSpecification The prompt handler to add
	 * @return Publisher that completes when clients have been notified of the change
	 */
	Publisher<Void> addPrompt(AsyncPromptSpecification promptSpecification);

	/**
	 * Remove a prompt handler at runtime.
	 * @param promptName The name of the prompt handler to remove
	 * @return Publisher that completes when clients have been notified of the change
	 */
	Publisher<Void> removePrompt(String promptName);

	/**
	 * Notifies clients that the list of available prompts has changed.
	 * @return A Publisher that completes when all clients have been notified
	 */
	Publisher<Void> notifyPromptsListChanged();

	/**
	 * This implementation would, incorrectly, broadcast the logging message to all
	 * connected clients, using a single minLoggingLevel for all of them. Similar to the
	 * sampling and roots, the logging level should be set per client session and use the
	 * ServerExchange to send the logging message to the right client.
	 * @param loggingMessageNotification The logging message to send
	 * @return A Publisher that completes when the notification has been sent
	 */
	@Deprecated
	Publisher<Void> loggingNotification(McpSchema.LoggingMessageNotification loggingMessageNotification);

	/**
	 * This method is package-private and used for test only. Should not be called by user
	 * code.
	 * @param protocolVersions the Client supported protocol versions.
	 */
	void setProtocolVersions(List<String> protocolVersions);

}
