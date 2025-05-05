/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.schema.McpSchema;
import org.reactivestreams.Publisher;

/**
 * @author Aliaksei Darafeyeu
 */
public interface McpServerExchange {

	McpSchema.ClientCapabilities getClientCapabilities();

	/**
	 * Get the client implementation information.
	 * @return The client implementation details
	 */
	McpSchema.Implementation getClientInfo();

	/**
	 * Create a new message using the sampling capabilities of the client. The Model
	 * Context Protocol (MCP) provides a standardized way for servers to request LLM
	 * sampling (“completions” or “generations”) from language models via clients. This
	 * flow allows clients to maintain control over model access, selection, and
	 * permissions while enabling servers to leverage AI capabilities—with no server API
	 * keys necessary. Servers can request text or image-based interactions and optionally
	 * include context from MCP servers in their prompts.
	 * @param createMessageRequest The request to create a new message
	 * @return A Publisher that completes when the message has been created
	 * @see McpSchema.CreateMessageRequest
	 * @see McpSchema.CreateMessageResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
	 * Specification</a>
	 */
	Publisher<McpSchema.CreateMessageResult> createMessage(McpSchema.CreateMessageRequest createMessageRequest);

	/**
	 * Retrieves the list of all roots provided by the client.
	 * @return A Publisher that emits the list of roots result.
	 */
	Publisher<McpSchema.ListRootsResult> listRoots();

	/**
	 * Retrieves a paginated list of roots provided by the client.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Publisher that emits the list of roots result containing
	 */
	Publisher<McpSchema.ListRootsResult> listRoots(String cursor);

	/**
	 * Send a logging message notification to all connected clients. Messages below the
	 * current minimum logging level will be filtered out.
	 * @param loggingMessageNotification The logging message to send
	 * @return A Publisher that completes when the notification has been sent
	 */
	Publisher<Void> loggingNotification(McpSchema.LoggingMessageNotification loggingMessageNotification);

	/**
	 * Set the minimum logging level for the client. Messages below this level will be
	 * filtered out.
	 * @param minLoggingLevel The minimum logging level
	 */
	void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel);

}
