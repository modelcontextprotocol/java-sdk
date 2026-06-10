/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.Collections;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpLoggableSession;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
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

	private final String sessionId;

	private final McpLoggableSession session;

	private final McpSchema.ClientCapabilities clientCapabilities;

	private final McpSchema.Implementation clientInfo;

	private final McpTransportContext transportContext;

	private final JsonSchemaValidator jsonSchemaValidator;

	private final String negotiatedProtocolVersion;

	private static final TypeRef<McpSchema.CreateMessageResult> CREATE_MESSAGE_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<McpSchema.CreateMessageWithToolsResult> CREATE_MESSAGE_WITH_TOOLS_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<McpSchema.ListRootsResult> LIST_ROOTS_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<McpSchema.ElicitResult> ELICITATION_RESULT_TYPE_REF = new TypeRef<>() {
	};

	public static final TypeRef<Object> OBJECT_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * Create a new asynchronous exchange with the client.
	 * @param sessionId the session ID
	 * @param session The server session representing a 1-1 interaction.
	 * @param clientCapabilities The client capabilities that define the supported
	 * features and functionality.
	 * @param clientInfo The client implementation information.
	 * @param transportContext context associated with the client as extracted from the
	 * transport
	 * @param jsonSchemaValidator optional validator used to verify elicitation schemas
	 * @param negotiatedProtocolVersion the protocol version negotiated during
	 * initialization, or {@code null} if unknown
	 */
	public McpAsyncServerExchange(String sessionId, McpLoggableSession session,
			McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo,
			McpTransportContext transportContext, JsonSchemaValidator jsonSchemaValidator,
			String negotiatedProtocolVersion) {
		this.sessionId = sessionId;
		this.session = session;
		this.clientCapabilities = clientCapabilities;
		this.clientInfo = clientInfo;
		this.transportContext = transportContext;
		this.jsonSchemaValidator = jsonSchemaValidator;
		this.negotiatedProtocolVersion = negotiatedProtocolVersion;
	}

	/**
	 * Create a new asynchronous exchange with the client.
	 * @param sessionId the session ID
	 * @param session The server session representing a 1-1 interaction.
	 * @param clientCapabilities The client capabilities that define the supported
	 * features and functionality.
	 * @param clientInfo The client implementation information.
	 * @param transportContext context associated with the client as extracted from the
	 * transport
	 * @param jsonSchemaValidator optional validator used to verify elicitation schemas
	 */
	public McpAsyncServerExchange(String sessionId, McpLoggableSession session,
			McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo,
			McpTransportContext transportContext, JsonSchemaValidator jsonSchemaValidator) {
		this(sessionId, session, clientCapabilities, clientInfo, transportContext, jsonSchemaValidator, null);
	}

	/**
	 * Create a new asynchronous exchange with the client.
	 * @param sessionId the session ID
	 * @param session The server session representing a 1-1 interaction.
	 * @param clientCapabilities The client capabilities that define the supported
	 * features and functionality.
	 * @param clientInfo The client implementation information.
	 * @param transportContext context associated with the client as extracted from the
	 * transport
	 */
	public McpAsyncServerExchange(String sessionId, McpLoggableSession session,
			McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo,
			McpTransportContext transportContext) {
		this(sessionId, session, clientCapabilities, clientInfo, transportContext, null, null);
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
	 * Provides the {@link McpTransportContext} associated with the transport layer. For
	 * HTTP transports it can contain the metadata associated with the HTTP request that
	 * triggered the processing.
	 * @return the transport context object
	 */
	public McpTransportContext transportContext() {
		return this.transportContext;
	}

	/**
	 * Provides the Session ID.
	 * @return session ID string
	 */
	public String sessionId() {
		return this.sessionId;
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
			return Mono
				.error(new IllegalStateException("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.sampling() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with sampling capabilities"));
		}
		return this.session.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, createMessageRequest,
				CREATE_MESSAGE_RESULT_TYPE_REF);
	}

	/**
	 * Create a new message using the sampling-with-tools capabilities of the client
	 * (SEP-1577). The request may include tool definitions and a tool choice mode. The
	 * client drives its LLM with these tools and returns a
	 * {@link McpSchema.CreateMessageWithToolsResult} whose content may include
	 * {@link McpSchema.ToolUseContent} blocks.
	 *
	 * <p>
	 * <strong>Wire-format version gate:</strong> if the negotiated protocol version is
	 * older than {@link McpSchema#PROTOCOL_VERSION_SAMPLING_WITH_TOOLS}
	 * ({@code 2025-11-25}), this method rejects requests that contain {@code tools}, a
	 * {@code toolChoice}, or any message whose content list has more than one block. Such
	 * requests cannot be round-tripped by a legacy peer without data loss.
	 *
	 * <p>
	 * Note: the JSON-RPC method sent on the wire is the same
	 * {@code sampling/createMessage} as {@link #createMessage}. What differs is the
	 * richer V2 schema used to serialize the parameters and deserialize the result.
	 * @param request The sampling-with-tools request
	 * @return A Mono that emits the result when the client has responded
	 * @see McpSchema.CreateMessageWithToolsRequest
	 * @see McpSchema.CreateMessageWithToolsResult
	 */
	public Mono<McpSchema.CreateMessageWithToolsResult> createMessageWithTools(
			McpSchema.CreateMessageWithToolsRequest request) {
		if (this.clientCapabilities == null) {
			return Mono
				.error(new IllegalStateException("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.sampling() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with sampling capabilities"));
		}
		if (this.clientCapabilities.sampling().tools() == null) {
			return Mono
				.error(new IllegalStateException("Client must be configured with sampling-with-tools capabilities"));
		}

		// Version gate: refuse multi-content or tools if the peer is pre-2025-11-25
		if (isOlderThanSamplingWithToolsVersion(this.negotiatedProtocolVersion)) {
			if (request.tools() != null && !request.tools().isEmpty()) {
				return Mono.error(
						new IllegalStateException("Cannot send tools in sampling request: negotiated protocol version '"
								+ this.negotiatedProtocolVersion + "' is older than '"
								+ McpSchema.PROTOCOL_VERSION_SAMPLING_WITH_TOOLS + "'"));
			}
			if (request.toolChoice() != null) {
				return Mono.error(new IllegalStateException(
						"Cannot send toolChoice in sampling request: negotiated protocol version '"
								+ this.negotiatedProtocolVersion + "' is older than '"
								+ McpSchema.PROTOCOL_VERSION_SAMPLING_WITH_TOOLS + "'"));
			}
			for (int i = 0; i < request.messages().size(); i++) {
				if (request.messages().get(i).content().size() > 1) {
					return Mono.error(new IllegalStateException("Cannot send multi-content message at index " + i
							+ " in sampling request: negotiated protocol version '" + this.negotiatedProtocolVersion
							+ "' is older than '" + McpSchema.PROTOCOL_VERSION_SAMPLING_WITH_TOOLS + "'"));
				}
			}
		}

		return this.session.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, request,
				CREATE_MESSAGE_WITH_TOOLS_RESULT_TYPE_REF);
	}

	/**
	 * Returns {@code true} when the given version string is older than
	 * {@link McpSchema#PROTOCOL_VERSION_SAMPLING_WITH_TOOLS}, or when the version is
	 * {@code null} (unknown — treated as pre-SEP-1577).
	 */
	private static boolean isOlderThanSamplingWithToolsVersion(String negotiatedVersion) {
		if (negotiatedVersion == null) {
			return true;
		}
		return negotiatedVersion.compareTo(McpSchema.PROTOCOL_VERSION_SAMPLING_WITH_TOOLS) < 0;
	}

	/**
	 * Creates a new elicitation. MCP provides a standardized way for servers to request
	 * additional information from users through the client during interactions. This flow
	 * allows clients to maintain control over user interactions and data sharing while
	 * enabling servers to gather necessary information dynamically. Servers can request
	 * structured data from users with optional JSON schemas to validate responses.
	 * @param elicitRequest The request to create a new elicitation
	 * @return A Mono that completes when the elicitation has been resolved.
	 * @see McpSchema.ElicitRequest
	 * @see McpSchema.ElicitResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/elicitation/">Elicitation
	 * Specification</a>
	 */
	public Mono<McpSchema.ElicitResult> createElicitation(McpSchema.ElicitRequest elicitRequest) {
		if (this.clientCapabilities == null) {
			return Mono
				.error(new IllegalStateException("Client must be initialized. Call the initialize method first!"));
		}
		McpSchema.ClientCapabilities.Elicitation elicitation = this.clientCapabilities.elicitation();
		if (elicitation == null) {
			return Mono.error(new IllegalStateException("Client must be configured with elicitation capabilities"));
		}

		// elicitation: {} is equivalent to elicitation: { form: {} }
		boolean supportsForm = elicitation.form() != null || elicitation.url() == null;
		boolean supportsUrl = elicitation.url() != null;

		if (elicitRequest instanceof McpSchema.ElicitFormRequest && !supportsForm) {
			return Mono
				.error(new IllegalStateException("Client must be configured with form elicitation capabilities"));
		}

		if (elicitRequest instanceof McpSchema.ElicitUrlRequest && !supportsUrl) {
			return Mono.error(new IllegalStateException("Client must be configured with URL elicitation capabilities"));
		}

		if (this.jsonSchemaValidator != null && elicitRequest instanceof McpSchema.ElicitFormRequest formRequest) {
			try {
				this.jsonSchemaValidator.assertConforms("ElicitRequest requestedSchema", formRequest.requestedSchema());
			}
			catch (IllegalArgumentException e) {
				return Mono.error(e);
			}
		}
		return this.session.sendRequest(McpSchema.METHOD_ELICITATION_CREATE, elicitRequest,
				ELICITATION_RESULT_TYPE_REF);
	}

	/**
	 * Retrieves the list of all roots provided by the client.
	 * @return A Mono that emits the list of roots result.
	 */
	public Mono<McpSchema.ListRootsResult> listRoots() {

		// @formatter:off
		return this.listRoots(McpSchema.FIRST_PAGE)
			.expand(result -> (result.nextCursor() != null) ?
					this.listRoots(result.nextCursor()) : Mono.empty())
			.reduce(McpSchema.ListRootsResult.builder(new ArrayList<>()).build(),
				(allRootsResult, result) -> {
					allRootsResult.roots().addAll(result.roots());
					return allRootsResult;
				})
			.map(result -> McpSchema.ListRootsResult.builder(Collections.unmodifiableList(result.roots()))
					.nextCursor(result.nextCursor()).build());
		// @formatter:on
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
	 * Send a logging message notification to the client. Messages below the current
	 * minimum logging level will be filtered out.
	 * @param loggingMessageNotification The logging message to send
	 * @return A Mono that completes when the notification has been sent
	 */
	public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {

		if (loggingMessageNotification == null) {
			return Mono.error(new IllegalStateException("Logging message must not be null"));
		}

		return Mono.defer(() -> {
			if (this.session.isNotificationForLevelAllowed(loggingMessageNotification.level())) {
				return this.session.sendNotification(McpSchema.METHOD_NOTIFICATION_MESSAGE, loggingMessageNotification);
			}
			return Mono.empty();
		});
	}

	/**
	 * Sends a notification to the client that the current progress status has changed for
	 * long-running operations.
	 * @param progressNotification The progress notification to send
	 * @return A Mono that completes when the notification has been sent
	 */
	public Mono<Void> progressNotification(McpSchema.ProgressNotification progressNotification) {
		if (progressNotification == null) {
			return Mono.error(new IllegalStateException("Progress notification must not be null"));
		}
		return this.session.sendNotification(McpSchema.METHOD_NOTIFICATION_PROGRESS, progressNotification);
	}

	/**
	 * Sends a ping request to the client.
	 * @return A Mono that completes with clients's ping response
	 */
	public Mono<Object> ping() {
		return this.session.sendRequest(McpSchema.METHOD_PING, null, OBJECT_TYPE_REF);
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
