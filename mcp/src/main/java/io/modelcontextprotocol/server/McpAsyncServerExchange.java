/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * Represents an asynchronous exchange with a Model Context Protocol (MCP) client. The
 * exchange provides methods to interact with the client and query its capabilities.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 */
public class McpAsyncServerExchange {

	private final McpServerSession session;

	private final McpSchema.ClientCapabilities clientCapabilities;

	private final McpSchema.Implementation clientInfo;

	private static final TypeReference<McpSchema.CreateMessageResult> CREATE_MESSAGE_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.ListRootsResult> LIST_ROOTS_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * Create a new asynchronous exchange with the client.
	 * @param session The server session representing a 1-1 interaction.
	 * @param clientCapabilities The client capabilities that define the supported
	 * features and functionality.
	 * @param clientInfo The client implementation information.
	 */
	public McpAsyncServerExchange(McpServerSession session, McpSchema.ClientCapabilities clientCapabilities,
			McpSchema.Implementation clientInfo) {
		this.session = session;
		this.clientCapabilities = clientCapabilities;
		this.clientInfo = clientInfo;
	}

	/**
	 * Get the client capabilities that define the supported features and functionality.
	 * @return The client capabilities
	 */
	public McpSchema.ClientCapabilities getClientCapabilities() {
		return this.clientCapabilities;
	}

	/**
	 * Get the client implementation information.
	 * @return The client implementation details
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.clientInfo;
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
	 * @return A Mono that completes when the message has been created
	 * @see McpSchema.CreateMessageRequest
	 * @see McpSchema.CreateMessageResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
	 * Specification</a>
	 */
	public Mono<McpSchema.CreateMessageResult> createMessage(McpSchema.CreateMessageRequest createMessageRequest) {
		if (this.clientCapabilities == null) {
			return Mono.error(new McpError("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.sampling() == null) {
			return Mono.error(new McpError("Client must be configured with sampling capabilities"));
		}
		return this.session.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, createMessageRequest,
				CREATE_MESSAGE_RESULT_TYPE_REF);
	}

	/**
	 * Retrieves the list of all roots provided by the client.
	 * @return A Mono that emits the list of roots result.
	 */
	public Mono<McpSchema.ListRootsResult> listRoots() {
		return this.listRoots(null);
	}

	/**
	 * Retrieves a paginated list of roots provided by the client.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that emits the list of roots result containing
	 */
	public Mono<McpSchema.ListRootsResult> listRoots(String cursor) {
		return this.session.sendRequest(McpSchema.METHOD_ROOTS_LIST, new McpSchema.PaginatedRequest(cursor),
				LIST_ROOTS_RESULT_TYPE_REF);
	}

	/**
	 * Send a logging message notification to all connected clients. Messages below the
	 * current minimum logging level will be filtered out.
	 * @param loggingMessageNotification The logging message to send
	 * @return A Mono that completes when the notification has been sent
	 */
	public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {

		if (loggingMessageNotification == null) {
			return Mono.error(new McpError("Logging message must not be null"));
		}

		return Mono.defer(() -> {
			if (!this.session.isLoingLevelEnabled(loggingMessageNotification.level())) {
				return Mono.empty();
			}

			return this.session.sendNotification(McpSchema.METHOD_NOTIFICATION_MESSAGE, loggingMessageNotification);
		});
	}

	/**
	 * Set the minimum logging level for the client. Messages below this level will be
	 * filtered out.
	 * @param minLoggingLevel The minimum logging level
	 */
	void setMinLoggingLevel(LoggingLevel minLoggingLevel) {
		Assert.notNull(minLoggingLevel, "minLoggingLevel must not be null");
		this.session.setMinLoggingLevel(minLoggingLevel);
	}

}
