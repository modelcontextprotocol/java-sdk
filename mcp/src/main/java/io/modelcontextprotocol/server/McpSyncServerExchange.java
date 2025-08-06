/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;

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
	 * Sends a synchronous ping request to the client.
	 * @return
	 */
	public Object ping() {
		return this.exchange.ping().block();
	}

}
