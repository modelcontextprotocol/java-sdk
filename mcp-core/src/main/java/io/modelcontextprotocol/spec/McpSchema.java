/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import reactor.util.annotation.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Based on the <a href="http://www.jsonrpc.org/specification">JSON-RPC 2.0
 * specification</a> and the <a href=
 * "https://github.com/modelcontextprotocol/specification/blob/main/schema/2024-11-05/schema.ts">Model
 * Context Protocol Schema</a>.
 *
 * @author Christian Tzolov
 * @author Luca Chang
 * @author Surbhi Bansal
 * @author Anurag Pant
 */
public final class McpSchema {

	private static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

	private McpSchema() {
	}

	@Deprecated
	public static final String LATEST_PROTOCOL_VERSION = ProtocolVersions.MCP_2025_11_25;

	public static final String JSONRPC_VERSION = "2.0";

	public static final String FIRST_PAGE = null;

	// ---------------------------
	// Method Names
	// ---------------------------

	// Lifecycle Methods
	public static final String METHOD_INITIALIZE = "initialize";

	public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";

	public static final String METHOD_PING = "ping";

	public static final String METHOD_NOTIFICATION_PROGRESS = "notifications/progress";

	// Tool Methods
	public static final String METHOD_TOOLS_LIST = "tools/list";

	public static final String METHOD_TOOLS_CALL = "tools/call";

	public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

	// Resources Methods
	public static final String METHOD_RESOURCES_LIST = "resources/list";

	public static final String METHOD_RESOURCES_READ = "resources/read";

	public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";

	public static final String METHOD_NOTIFICATION_RESOURCES_UPDATED = "notifications/resources/updated";

	public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";

	public static final String METHOD_RESOURCES_SUBSCRIBE = "resources/subscribe";

	public static final String METHOD_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";

	// Prompt Methods
	public static final String METHOD_PROMPT_LIST = "prompts/list";

	public static final String METHOD_PROMPT_GET = "prompts/get";

	public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

	public static final String METHOD_COMPLETION_COMPLETE = "completion/complete";

	// Logging Methods
	public static final String METHOD_LOGGING_SET_LEVEL = "logging/setLevel";

	public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";

	// Roots Methods
	public static final String METHOD_ROOTS_LIST = "roots/list";

	public static final String METHOD_NOTIFICATION_ROOTS_LIST_CHANGED = "notifications/roots/list_changed";

	// Sampling Methods
	public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";

	// Elicitation Methods
	public static final String METHOD_ELICITATION_CREATE = "elicitation/create";

	// Tasks Methods
	public static final String METHOD_TASKS_GET = "tasks/get";

	public static final String METHOD_TASKS_RESULT = "tasks/result";

	public static final String METHOD_TASKS_CANCEL = "tasks/cancel";

	public static final String METHOD_TASKS_LIST = "tasks/list";

	public static final String METHOD_NOTIFICATION_TASKS_STATUS = "notifications/tasks/status";

	// ---------------------------
	// JSON-RPC Error Codes
	// ---------------------------
	/**
	 * Standard error codes used in MCP JSON-RPC responses.
	 */
	public static final class ErrorCodes {

		/**
		 * Invalid JSON was received by the server.
		 */
		public static final int PARSE_ERROR = -32700;

		/**
		 * The JSON sent is not a valid Request object.
		 */
		public static final int INVALID_REQUEST = -32600;

		/**
		 * The method does not exist / is not available.
		 */
		public static final int METHOD_NOT_FOUND = -32601;

		/**
		 * Invalid method parameter(s).
		 */
		public static final int INVALID_PARAMS = -32602;

		/**
		 * Internal JSON-RPC error.
		 */
		public static final int INTERNAL_ERROR = -32603;

		/**
		 * Resource not found.
		 */
		public static final int RESOURCE_NOT_FOUND = -32002;

	}

	/**
	 * Base interface for MCP objects that include optional metadata in the `_meta` field.
	 */
	public interface Meta {

		/**
		 * @see <a href=
		 * "https://modelcontextprotocol.io/specification/2025-06-18/basic/index#meta">Specification</a>
		 * for notes on _meta usage
		 * @return additional metadata related to this resource.
		 */
		Map<String, Object> meta();

	}

	public sealed interface Request extends Meta permits InitializeRequest, CallToolRequest, CreateMessageRequest,
			ElicitRequest, CompleteRequest, GetPromptRequest, ReadResourceRequest, SubscribeRequest, UnsubscribeRequest,
			PaginatedRequest, GetTaskRequest, GetTaskPayloadRequest, CancelTaskRequest {

		/**
		 * Returns the MCP method string for a given request type.
		 *
		 * <p>
		 * This mirrors {@link Notification#getNotificationMethod(Notification)} and
		 * provides a single source of truth for request-type-to-method mapping.
		 *
		 * <p>
		 * Note: {@link PaginatedRequest} is used for multiple methods (tools/list,
		 * prompts/list, resources/list, tasks/list, etc.) and cannot be unambiguously
		 * resolved. Callers must specify the method string directly for paginated
		 * requests.
		 * @param request the request to resolve
		 * @return the MCP method string
		 * @throws IllegalArgumentException if the request type cannot be unambiguously
		 * mapped
		 */
		static String getRequestMethod(Request request) {
			if (request instanceof McpSchema.InitializeRequest) {
				return McpSchema.METHOD_INITIALIZE;
			}
			else if (request instanceof McpSchema.CallToolRequest) {
				return McpSchema.METHOD_TOOLS_CALL;
			}
			else if (request instanceof McpSchema.CreateMessageRequest) {
				return McpSchema.METHOD_SAMPLING_CREATE_MESSAGE;
			}
			else if (request instanceof McpSchema.ElicitRequest) {
				return McpSchema.METHOD_ELICITATION_CREATE;
			}
			else if (request instanceof McpSchema.CompleteRequest) {
				return McpSchema.METHOD_COMPLETION_COMPLETE;
			}
			else if (request instanceof McpSchema.GetPromptRequest) {
				return McpSchema.METHOD_PROMPT_GET;
			}
			else if (request instanceof McpSchema.ReadResourceRequest) {
				return McpSchema.METHOD_RESOURCES_READ;
			}
			else if (request instanceof McpSchema.SubscribeRequest) {
				return McpSchema.METHOD_RESOURCES_SUBSCRIBE;
			}
			else if (request instanceof McpSchema.UnsubscribeRequest) {
				return McpSchema.METHOD_RESOURCES_UNSUBSCRIBE;
			}
			else if (request instanceof McpSchema.GetTaskRequest) {
				return McpSchema.METHOD_TASKS_GET;
			}
			else if (request instanceof McpSchema.GetTaskPayloadRequest) {
				return McpSchema.METHOD_TASKS_RESULT;
			}
			else if (request instanceof McpSchema.CancelTaskRequest) {
				return McpSchema.METHOD_TASKS_CANCEL;
			}
			else if (request instanceof McpSchema.PaginatedRequest) {
				throw new IllegalArgumentException(
						"PaginatedRequest is used for multiple methods (tools/list, prompts/list, "
								+ "resources/list, tasks/list, etc.) and cannot be unambiguously resolved. "
								+ "Specify the method string directly.");
			}
			throw new IllegalArgumentException("Unknown request type: " + request.getClass().getName());
		}

		default Object progressToken() {
			if (meta() != null && meta().containsKey("progressToken")) {
				return meta().get("progressToken");
			}
			return null;
		}

	}

	public sealed interface Result extends Meta
			permits InitializeResult, ListResourcesResult, ListResourceTemplatesResult, ReadResourceResult,
			ListPromptsResult, GetPromptResult, ListToolsResult, CompleteResult, ListRootsResult, GetTaskResult,
			CancelTaskResult, ListTasksResult, CreateTaskResult, ServerTaskPayloadResult, ClientTaskPayloadResult {

	}

	/**
	 * Sealed interface for results that servers produce from task-augmented operations.
	 * When a client calls a server's tool in task mode, the server produces a
	 * {@link CallToolResult}.
	 *
	 * <p>
	 * This interface provides type safety for server-side task result handling.
	 */
	public sealed interface ServerTaskPayloadResult extends Result permits CallToolResult {

	}

	/**
	 * Sealed interface for results that clients produce from task-augmented operations.
	 * When a server requests sampling or elicitation from a client in task mode, the
	 * client produces either a {@link CreateMessageResult} or {@link ElicitResult}.
	 *
	 * <p>
	 * This interface provides type safety for client-side task result handling.
	 */
	public sealed interface ClientTaskPayloadResult extends Result permits CreateMessageResult, ElicitResult {

	}

	public sealed interface Notification extends Meta permits ProgressNotification, LoggingMessageNotification,
			ResourcesUpdatedNotification, TaskStatusNotification {

		static String getNotificationMethod(Notification notification) {
			if (notification instanceof McpSchema.ProgressNotification) {
				return McpSchema.METHOD_NOTIFICATION_PROGRESS;
			}
			else if (notification instanceof McpSchema.LoggingMessageNotification) {
				return McpSchema.METHOD_NOTIFICATION_MESSAGE;
			}
			else if (notification instanceof McpSchema.TaskStatusNotification) {
				return McpSchema.METHOD_NOTIFICATION_TASKS_STATUS;
			}
			else if (notification instanceof McpSchema.ResourcesUpdatedNotification) {
				return McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED;
			}
			throw new IllegalArgumentException("Unknown notification type: " + notification.getClass().getName());
		}

	}

	private static final TypeRef<HashMap<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * Deserializes a JSON string into a JSONRPCMessage object.
	 * @param jsonMapper The JsonMapper instance to use for deserialization
	 * @param jsonText The JSON string to deserialize
	 * @return A JSONRPCMessage instance using either the {@link JSONRPCRequest},
	 * {@link JSONRPCNotification}, or {@link JSONRPCResponse} classes.
	 * @throws IOException If there's an error during deserialization
	 * @throws IllegalArgumentException If the JSON structure doesn't match any known
	 * message type
	 */
	public static JSONRPCMessage deserializeJsonRpcMessage(McpJsonMapper jsonMapper, String jsonText)
			throws IOException {

		logger.debug("Received JSON message: {}", jsonText);

		var map = jsonMapper.readValue(jsonText, MAP_TYPE_REF);

		// Determine message type based on specific JSON structure
		if (map.containsKey("method") && map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCRequest.class);
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCNotification.class);
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			return jsonMapper.convertValue(map, JSONRPCResponse.class);
		}

		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

	// ---------------------------
	// JSON-RPC Message Types
	// ---------------------------
	public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCNotification, JSONRPCResponse {

		String jsonrpc();

	}

	/**
	 * A request that expects a response.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param method The name of the method to be invoked
	 * @param id A unique identifier for the request
	 * @param params Parameters for the method call
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	public record JSONRPCRequest( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("method") String method,
		@JsonProperty("id") Object id,
		@JsonProperty("params") Object params) implements JSONRPCMessage { // @formatter:on

		/**
		 * Constructor that validates MCP-specific ID requirements. Unlike base JSON-RPC,
		 * MCP requires that: (1) Requests MUST include a string or integer ID; (2) The ID
		 * MUST NOT be null
		 */
		public JSONRPCRequest {
			Assert.notNull(id, "MCP requests MUST include an ID - null IDs are not allowed");
			Assert.isTrue(id instanceof String || id instanceof Integer || id instanceof Long,
					"MCP requests MUST have an ID that is either a string or integer");
		}
	}

	/**
	 * A notification which does not expect a response.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param method The name of the method being notified
	 * @param params Parameters for the notification
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	// TODO: batching support
	// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	public record JSONRPCNotification( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("method") String method,
		@JsonProperty("params") Object params) implements JSONRPCMessage { // @formatter:on
	}

	/**
	 * A response to a request (successful, or error).
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param id The request identifier that this response corresponds to
	 * @param result The result of the successful request; null if error
	 * @param error Error information if the request failed; null if has result
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	// TODO: batching support
	// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	public record JSONRPCResponse( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("id") Object id,
		@JsonProperty("result") Object result,
		@JsonProperty("error") JSONRPCError error) implements JSONRPCMessage { // @formatter:on

		/**
		 * A response to a request that indicates an error occurred.
		 *
		 * @param code The error type that occurred
		 * @param message A short description of the error. The message SHOULD be limited
		 * to a concise single sentence
		 * @param data Additional information about the error. The value of this member is
		 * defined by the sender (e.g. detailed error information, nested errors etc.)
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record JSONRPCError( // @formatter:off
			@JsonProperty("code") Integer code,
			@JsonProperty("message") String message,
			@JsonProperty("data") Object data) { // @formatter:on
		}
	}

	// ---------------------------
	// Initialization
	// ---------------------------
	/**
	 * This request is sent from the client to the server when it first connects, asking
	 * it to begin initialization.
	 *
	 * @param protocolVersion The latest version of the Model Context Protocol that the
	 * client supports. The client MAY decide to support older versions as well
	 * @param capabilities The capabilities that the client supports
	 * @param clientInfo Information about the client implementation
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record InitializeRequest( // @formatter:off
		@JsonProperty("protocolVersion") String protocolVersion,
		@JsonProperty("capabilities") ClientCapabilities capabilities,
		@JsonProperty("clientInfo") Implementation clientInfo,
		@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

		public InitializeRequest(String protocolVersion, ClientCapabilities capabilities, Implementation clientInfo) {
			this(protocolVersion, capabilities, clientInfo, null);
		}
	}

	/**
	 * After receiving an initialize request from the client, the server sends this
	 * response.
	 *
	 * @param protocolVersion The version of the Model Context Protocol that the server
	 * wants to use. This may not match the version that the client requested. If the
	 * client cannot support this version, it MUST disconnect
	 * @param capabilities The capabilities that the server supports
	 * @param serverInfo Information about the server implementation
	 * @param instructions Instructions describing how to use the server and its features.
	 * This can be used by clients to improve the LLM's understanding of available tools,
	 * resources, etc. It can be thought of like a "hint" to the model. For example, this
	 * information MAY be added to the system prompt
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record InitializeResult( // @formatter:off
		@JsonProperty("protocolVersion") String protocolVersion,
		@JsonProperty("capabilities") ServerCapabilities capabilities,
		@JsonProperty("serverInfo") Implementation serverInfo,
		@JsonProperty("instructions") String instructions,
		@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

		public InitializeResult(String protocolVersion, ServerCapabilities capabilities, Implementation serverInfo,
				String instructions) {
			this(protocolVersion, capabilities, serverInfo, instructions, null);
		}
	}

	/**
	 * Capabilities a client may support. Known capabilities are defined here, in this
	 * schema, but this is not a closed set: any client can define its own, additional
	 * capabilities.
	 *
	 * @param experimental Experimental, non-standard capabilities that the client
	 * supports
	 * @param roots Present if the client supports listing roots
	 * @param sampling Present if the client supports sampling from an LLM
	 * @param elicitation Present if the client supports elicitation from the server
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ClientCapabilities( // @formatter:off
		@JsonProperty("experimental") Map<String, Object> experimental,
		@JsonProperty("roots") RootCapabilities roots,
		@JsonProperty("sampling") Sampling sampling,
		@JsonProperty("elicitation") Elicitation elicitation,
		@JsonProperty("tasks") ClientTaskCapabilities tasks) { // @formatter:on

		/**
		 * Present if the client supports listing roots.
		 *
		 * @param listChanged Whether the client supports notifications for changes to the
		 * roots list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record RootCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
		}

		/**
		 * Provides a standardized way for servers to request LLM sampling ("completions"
		 * or "generations") from language models via clients. This flow allows clients to
		 * maintain control over model access, selection, and permissions while enabling
		 * servers to leverage AI capabilities—with no server API keys necessary. Servers
		 * can request text or image-based interactions and optionally include context
		 * from MCP servers in their prompts.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public record Sampling() {
		}

		/**
		 * Provides a standardized way for servers to request additional information from
		 * users through the client during interactions. This flow allows clients to
		 * maintain control over user interactions and data sharing while enabling servers
		 * to gather necessary information dynamically. Servers can request structured
		 * data from users with optional JSON schemas to validate responses.
		 *
		 * <p>
		 * Per the 2025-11-25 spec, clients can declare support for specific elicitation
		 * modes:
		 * <ul>
		 * <li>{@code form} - In-band structured data collection with optional schema
		 * validation</li>
		 * <li>{@code url} - Out-of-band interaction via URL navigation</li>
		 * </ul>
		 *
		 * <p>
		 * For backward compatibility, an empty elicitation object {@code {}} is
		 * equivalent to declaring support for form mode only.
		 *
		 * @param form support for in-band form-based elicitation
		 * @param url support for out-of-band URL-based elicitation
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public record Elicitation(@JsonProperty("form") Form form, @JsonProperty("url") Url url) {

			/**
			 * Marker record indicating support for form-based elicitation mode.
			 */
			@JsonInclude(JsonInclude.Include.NON_ABSENT)
			public record Form() {
			}

			/**
			 * Marker record indicating support for URL-based elicitation mode.
			 */
			@JsonInclude(JsonInclude.Include.NON_ABSENT)
			public record Url() {
			}

			/**
			 * Creates an Elicitation with default settings (backward compatible, produces
			 * empty JSON object).
			 */
			public Elicitation() {
				this(null, null);
			}
		}

		/**
		 * Present if the client supports task-augmented requests.
		 */
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class ClientTaskCapabilities {

			private final ListTaskCapability list;

			private final CancelTaskCapability cancel;

			private final ClientTaskRequestCapabilities requests;

			@JsonCreator
			private ClientTaskCapabilities(@JsonProperty("list") ListTaskCapability list,
					@JsonProperty("cancel") CancelTaskCapability cancel,
					@JsonProperty("requests") ClientTaskRequestCapabilities requests) {
				this.list = list;
				this.cancel = cancel;
				this.requests = requests;
			}

			/**
			 * Returns whether the client supports tasks/list requests.
			 * @return the list capability, or null
			 */
			@JsonProperty("list")
			public ListTaskCapability list() {
				return this.list;
			}

			/**
			 * Returns whether the client supports tasks/cancel requests.
			 * @return the cancel capability, or null
			 */
			@JsonProperty("cancel")
			public CancelTaskCapability cancel() {
				return this.cancel;
			}

			/**
			 * Returns which request types can be augmented with tasks.
			 * @return the request capabilities, or null
			 */
			@JsonProperty("requests")
			public ClientTaskRequestCapabilities requests() {
				return this.requests;
			}

			/**
			 * Marker class indicating support for tasks/list.
			 */
			@JsonIgnoreProperties(ignoreUnknown = true)
			public static final class ListTaskCapability {

				@JsonCreator
				ListTaskCapability() {
				}

			}

			/**
			 * Marker class indicating support for tasks/cancel.
			 */
			@JsonIgnoreProperties(ignoreUnknown = true)
			public static final class CancelTaskCapability {

				@JsonCreator
				CancelTaskCapability() {
				}

			}

			/**
			 * Specifies which request types can be augmented with tasks.
			 */
			@JsonIgnoreProperties(ignoreUnknown = true)
			public static final class ClientTaskRequestCapabilities {

				private final SamplingTaskCapabilities sampling;

				private final ElicitationTaskCapabilities elicitation;

				@JsonCreator
				ClientTaskRequestCapabilities(@JsonProperty("sampling") SamplingTaskCapabilities sampling,
						@JsonProperty("elicitation") ElicitationTaskCapabilities elicitation) {
					this.sampling = sampling;
					this.elicitation = elicitation;
				}

				/**
				 * Returns the task support for sampling-related requests.
				 * @return the sampling capabilities, or null
				 */
				@JsonProperty("sampling")
				public SamplingTaskCapabilities sampling() {
					return this.sampling;
				}

				/**
				 * Returns the task support for elicitation-related requests.
				 * @return the elicitation capabilities, or null
				 */
				@JsonProperty("elicitation")
				public ElicitationTaskCapabilities elicitation() {
					return this.elicitation;
				}

				/**
				 * Task support for sampling-related requests.
				 */
				@JsonIgnoreProperties(ignoreUnknown = true)
				public static final class SamplingTaskCapabilities {

					private final CreateMessageTaskCapability createMessage;

					@JsonCreator
					SamplingTaskCapabilities(@JsonProperty("createMessage") CreateMessageTaskCapability createMessage) {
						this.createMessage = createMessage;
					}

					/**
					 * Returns whether the client supports task-augmented
					 * sampling/createMessage requests.
					 * @return the createMessage capability, or null
					 */
					@JsonProperty("createMessage")
					public CreateMessageTaskCapability createMessage() {
						return this.createMessage;
					}

					/**
					 * Marker class indicating support for task-augmented
					 * sampling/createMessage.
					 */
					@JsonIgnoreProperties(ignoreUnknown = true)
					public static final class CreateMessageTaskCapability {

						@JsonCreator
						CreateMessageTaskCapability() {
						}

					}

				}

				/**
				 * Task support for elicitation-related requests.
				 */
				@JsonIgnoreProperties(ignoreUnknown = true)
				public static final class ElicitationTaskCapabilities {

					private final CreateTaskCapability create;

					@JsonCreator
					ElicitationTaskCapabilities(@JsonProperty("create") CreateTaskCapability create) {
						this.create = create;
					}

					/**
					 * Returns whether the client supports task-augmented
					 * elicitation/create requests.
					 * @return the create capability, or null
					 */
					@JsonProperty("create")
					public CreateTaskCapability create() {
						return this.create;
					}

					/**
					 * Marker class indicating support for task-augmented
					 * elicitation/create.
					 */
					@JsonIgnoreProperties(ignoreUnknown = true)
					public static final class CreateTaskCapability {

						@JsonCreator
						CreateTaskCapability() {
						}

					}

				}

			}

			public static Builder builder() {
				return new Builder();
			}

			public static class Builder {

				private ListTaskCapability list;

				private CancelTaskCapability cancel;

				private ClientTaskRequestCapabilities requests;

				public Builder list() {
					this.list = new ListTaskCapability();
					return this;
				}

				public Builder cancel() {
					this.cancel = new CancelTaskCapability();
					return this;
				}

				public Builder samplingCreateMessage() {
					if (this.requests == null) {
						this.requests = new ClientTaskRequestCapabilities(null, null);
					}
					this.requests = new ClientTaskRequestCapabilities(
							new ClientTaskRequestCapabilities.SamplingTaskCapabilities(
									new ClientTaskRequestCapabilities.SamplingTaskCapabilities.CreateMessageTaskCapability()),
							this.requests.elicitation());
					return this;
				}

				public Builder elicitationCreate() {
					if (this.requests == null) {
						this.requests = new ClientTaskRequestCapabilities(null, null);
					}
					this.requests = new ClientTaskRequestCapabilities(this.requests.sampling(),
							new ClientTaskRequestCapabilities.ElicitationTaskCapabilities(
									new ClientTaskRequestCapabilities.ElicitationTaskCapabilities.CreateTaskCapability()));
					return this;
				}

				public ClientTaskCapabilities build() {
					return new ClientTaskCapabilities(list, cancel, requests);
				}

			}

		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Map<String, Object> experimental;

			private RootCapabilities roots;

			private Sampling sampling;

			private Elicitation elicitation;

			private ClientTaskCapabilities tasks;

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder roots(Boolean listChanged) {
				this.roots = new RootCapabilities(listChanged);
				return this;
			}

			public Builder sampling() {
				this.sampling = new Sampling();
				return this;
			}

			/**
			 * Enables elicitation capability with default settings (backward compatible,
			 * produces empty JSON object).
			 * @return this builder
			 */
			public Builder elicitation() {
				this.elicitation = new Elicitation();
				return this;
			}

			/**
			 * Enables elicitation capability with explicit form and/or url mode support.
			 * @param form whether to support form-based elicitation
			 * @param url whether to support URL-based elicitation
			 * @return this builder
			 */
			public Builder elicitation(boolean form, boolean url) {
				this.elicitation = new Elicitation(form ? new Elicitation.Form() : null,
						url ? new Elicitation.Url() : null);
				return this;
			}

			/**
			 * Enables task capabilities with the provided configuration.
			 * @param tasks the task capabilities
			 * @return this builder
			 */
			public Builder tasks(ClientTaskCapabilities tasks) {
				this.tasks = tasks;
				return this;
			}

			public ClientCapabilities build() {
				return new ClientCapabilities(experimental, roots, sampling, elicitation, tasks);
			}

		}
	}

	/**
	 * Capabilities that a server may support. Known capabilities are defined here, in
	 * this schema, but this is not a closed set: any server can define its own,
	 * additional capabilities.
	 *
	 * @param completions Present if the server supports argument autocompletion
	 * suggestions
	 * @param experimental Experimental, non-standard capabilities that the server
	 * supports
	 * @param logging Present if the server supports sending log messages to the client
	 * @param prompts Present if the server offers any prompt templates
	 * @param resources Present if the server offers any resources to read
	 * @param tools Present if the server offers any tools to call
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ServerCapabilities( // @formatter:off
		@JsonProperty("completions") CompletionCapabilities completions,
		@JsonProperty("experimental") Map<String, Object> experimental,
		@JsonProperty("logging") LoggingCapabilities logging,
		@JsonProperty("prompts") PromptCapabilities prompts,
		@JsonProperty("resources") ResourceCapabilities resources,
		@JsonProperty("tools") ToolCapabilities tools,
		@JsonProperty("tasks") ServerTaskCapabilities tasks) { // @formatter:on

		/**
		 * Present if the server supports argument autocompletion suggestions.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public record CompletionCapabilities() {
		}

		/**
		 * Present if the server supports sending log messages to the client.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public record LoggingCapabilities() {
		}

		/**
		 * Present if the server offers any prompt templates.
		 *
		 * @param listChanged Whether this server supports notifications for changes to
		 * the prompt list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public record PromptCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
		}

		/**
		 * Present if the server offers any resources to read.
		 *
		 * @param subscribe Whether this server supports subscribing to resource updates
		 * @param listChanged Whether this server supports notifications for changes to
		 * the resource list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public record ResourceCapabilities(@JsonProperty("subscribe") Boolean subscribe,
				@JsonProperty("listChanged") Boolean listChanged) {
		}

		/**
		 * Present if the server offers any tools to call.
		 *
		 * @param listChanged Whether this server supports notifications for changes to
		 * the tool list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public record ToolCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
		}

		/**
		 * Present if the server supports task-augmented requests.
		 */
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class ServerTaskCapabilities {

			private final ListTaskCapability list;

			private final CancelTaskCapability cancel;

			private final ServerTaskRequestCapabilities requests;

			@JsonCreator
			private ServerTaskCapabilities(@JsonProperty("list") ListTaskCapability list,
					@JsonProperty("cancel") CancelTaskCapability cancel,
					@JsonProperty("requests") ServerTaskRequestCapabilities requests) {
				this.list = list;
				this.cancel = cancel;
				this.requests = requests;
			}

			/**
			 * Returns whether the server supports tasks/list requests.
			 * @return the list capability, or null
			 */
			@JsonProperty("list")
			public ListTaskCapability list() {
				return this.list;
			}

			/**
			 * Returns whether the server supports tasks/cancel requests.
			 * @return the cancel capability, or null
			 */
			@JsonProperty("cancel")
			public CancelTaskCapability cancel() {
				return this.cancel;
			}

			/**
			 * Returns which request types can be augmented with tasks.
			 * @return the request capabilities, or null
			 */
			@JsonProperty("requests")
			public ServerTaskRequestCapabilities requests() {
				return this.requests;
			}

			/**
			 * Marker class indicating support for tasks/list.
			 */
			@JsonIgnoreProperties(ignoreUnknown = true)
			public static final class ListTaskCapability {

				@JsonCreator
				ListTaskCapability() {
				}

			}

			/**
			 * Marker class indicating support for tasks/cancel.
			 */
			@JsonIgnoreProperties(ignoreUnknown = true)
			public static final class CancelTaskCapability {

				@JsonCreator
				CancelTaskCapability() {
				}

			}

			/**
			 * Specifies which request types can be augmented with tasks.
			 */
			@JsonIgnoreProperties(ignoreUnknown = true)
			public static final class ServerTaskRequestCapabilities {

				private final ToolsTaskCapabilities tools;

				@JsonCreator
				ServerTaskRequestCapabilities(@JsonProperty("tools") ToolsTaskCapabilities tools) {
					this.tools = tools;
				}

				/**
				 * Returns the task support for tool-related requests.
				 * @return the tools capabilities, or null
				 */
				@JsonProperty("tools")
				public ToolsTaskCapabilities tools() {
					return this.tools;
				}

				/**
				 * Task support for tool-related requests.
				 */
				@JsonIgnoreProperties(ignoreUnknown = true)
				public static final class ToolsTaskCapabilities {

					private final CallTaskCapability call;

					@JsonCreator
					ToolsTaskCapabilities(@JsonProperty("call") CallTaskCapability call) {
						this.call = call;
					}

					/**
					 * Returns whether the server supports task-augmented tools/call
					 * requests.
					 * @return the call capability, or null
					 */
					@JsonProperty("call")
					public CallTaskCapability call() {
						return this.call;
					}

					/**
					 * Marker class indicating support for task-augmented tools/call.
					 */
					@JsonIgnoreProperties(ignoreUnknown = true)
					public static final class CallTaskCapability {

						@JsonCreator
						CallTaskCapability() {
						}

					}

				}

			}

			public static Builder builder() {
				return new Builder();
			}

			public static class Builder {

				private ListTaskCapability list;

				private CancelTaskCapability cancel;

				private ServerTaskRequestCapabilities requests;

				public Builder list() {
					this.list = new ListTaskCapability();
					return this;
				}

				public Builder cancel() {
					this.cancel = new CancelTaskCapability();
					return this;
				}

				public Builder toolsCall() {
					this.requests = new ServerTaskRequestCapabilities(
							new ServerTaskRequestCapabilities.ToolsTaskCapabilities(
									new ServerTaskRequestCapabilities.ToolsTaskCapabilities.CallTaskCapability()));
					return this;
				}

				public ServerTaskCapabilities build() {
					return new ServerTaskCapabilities(list, cancel, requests);
				}

			}

		}

		/**
		 * Create a mutated copy of this object with the specified changes.
		 * @return A new Builder instance with the same values as this object.
		 */
		public Builder mutate() {
			var builder = new Builder();
			builder.completions = this.completions;
			builder.experimental = this.experimental;
			builder.logging = this.logging;
			builder.prompts = this.prompts;
			builder.resources = this.resources;
			builder.tools = this.tools;
			builder.tasks = this.tasks;
			return builder;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private CompletionCapabilities completions;

			private Map<String, Object> experimental;

			private LoggingCapabilities logging;

			private PromptCapabilities prompts;

			private ResourceCapabilities resources;

			private ToolCapabilities tools;

			private ServerTaskCapabilities tasks;

			public Builder completions() {
				this.completions = new CompletionCapabilities();
				return this;
			}

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder logging() {
				this.logging = new LoggingCapabilities();
				return this;
			}

			public Builder prompts(Boolean listChanged) {
				this.prompts = new PromptCapabilities(listChanged);
				return this;
			}

			public Builder resources(Boolean subscribe, Boolean listChanged) {
				this.resources = new ResourceCapabilities(subscribe, listChanged);
				return this;
			}

			public Builder tools(Boolean listChanged) {
				this.tools = new ToolCapabilities(listChanged);
				return this;
			}

			/**
			 * Enables task capabilities with the provided configuration.
			 * @param tasks the task capabilities
			 * @return this builder
			 */
			public Builder tasks(ServerTaskCapabilities tasks) {
				this.tasks = tasks;
				return this;
			}

			public ServerCapabilities build() {
				return new ServerCapabilities(completions, experimental, logging, prompts, resources, tools, tasks);
			}

		}
	}

	/**
	 * Describes the name and version of an MCP implementation, with an optional title for
	 * UI representation.
	 *
	 * @param name Intended for programmatic or logical use, but used as a display name in
	 * past specs or fallback (if title isn't present).
	 * @param title Intended for UI and end-user contexts
	 * @param version The version of the implementation.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Implementation( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("version") String version) implements Identifier { // @formatter:on			

		public Implementation(String name, String version) {
			this(name, null, version);
		}
	}

	// Existing Enums and Base Types (from previous implementation)
	public enum Role {

	// @formatter:off
		@JsonProperty("user") USER,
		@JsonProperty("assistant") ASSISTANT
	} // @formatter:on

	/**
	 * The status of a task.
	 */
	public enum TaskStatus {

	// @formatter:off
		/**
		 * The request is currently being processed.
		 */
		@JsonProperty("working") WORKING,
		/**
		 * The task is waiting for input (e.g., elicitation or sampling).
		 */
		@JsonProperty("input_required") INPUT_REQUIRED,
		/**
		 * The request completed successfully and results are available.
		 */
		@JsonProperty("completed") COMPLETED,
		/**
		 * The associated request did not complete successfully. For tool calls specifically,
		 * this includes cases where the tool call result has isError set to true.
		 */
		@JsonProperty("failed") FAILED,
		/**
		 * The request was cancelled before completion.
		 */
		@JsonProperty("cancelled") CANCELLED;
		// @formatter:on

		/**
		 * Checks if this status represents a terminal state.
		 * <p>
		 * Terminal states are those where the task has finished processing and will not
		 * change further: COMPLETED, FAILED, or CANCELLED.
		 * @return true if this status is a terminal state
		 */
		public boolean isTerminal() {
			return this == COMPLETED || this == FAILED || this == CANCELLED;
		}

	}

	/**
	 * Represents the state and metadata of an asynchronous operation tracked by the MCP
	 * task system. Tasks are created when a client requests task-augmented execution of a
	 * tool, sampling, or elicitation operation.
	 *
	 * <p>
	 * A task progresses through various states ({@link TaskStatus}) and can include
	 * optional metadata such as TTL, poll interval, and status messages.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see TaskStatus
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Task {

		private final String taskId;

		private final TaskStatus status;

		private final String statusMessage;

		private final String createdAt;

		private final String lastUpdatedAt;

		private final Long ttl;

		private final Long pollInterval;

		@JsonCreator
		private Task( // @formatter:off
				@JsonProperty("taskId") String taskId,
				@JsonProperty("status") TaskStatus status,
				@JsonProperty("statusMessage") @Nullable String statusMessage,
				@JsonProperty("createdAt") String createdAt,
				@JsonProperty("lastUpdatedAt") String lastUpdatedAt,
				@JsonProperty("ttl") @Nullable Long ttl,
				@JsonProperty("pollInterval") @Nullable Long pollInterval) { // @formatter:on
			Assert.hasText(taskId, "taskId must not be empty");
			Assert.notNull(status, "status must not be null");
			Assert.hasText(createdAt, "createdAt must not be empty");
			Assert.hasText(lastUpdatedAt, "lastUpdatedAt must not be empty");
			// ttl and pollInterval can be null (unlimited/default)
			this.taskId = taskId;
			this.status = status;
			this.statusMessage = statusMessage;
			this.createdAt = createdAt;
			this.lastUpdatedAt = lastUpdatedAt;
			this.ttl = ttl;
			this.pollInterval = pollInterval;
		}

		/**
		 * Returns the task identifier.
		 * @return the task identifier
		 */
		@JsonProperty("taskId")
		public String taskId() {
			return this.taskId;
		}

		/**
		 * Returns the task status.
		 * @return the task status
		 */
		@JsonProperty("status")
		public TaskStatus status() {
			return this.status;
		}

		/**
		 * Returns the optional status message.
		 * @return the status message, or null
		 */
		@JsonProperty("statusMessage")
		@Nullable
		public String statusMessage() {
			return this.statusMessage;
		}

		/**
		 * Returns the creation timestamp.
		 * @return the ISO 8601 creation timestamp
		 */
		@JsonProperty("createdAt")
		public String createdAt() {
			return this.createdAt;
		}

		/**
		 * Returns the last updated timestamp.
		 * @return the ISO 8601 last updated timestamp
		 */
		@JsonProperty("lastUpdatedAt")
		public String lastUpdatedAt() {
			return this.lastUpdatedAt;
		}

		/**
		 * Returns the TTL (time-to-live) in milliseconds.
		 * @return the TTL, or null for unlimited
		 */
		@JsonProperty("ttl")
		@Nullable
		public Long ttl() {
			return this.ttl;
		}

		/**
		 * Returns the suggested polling interval in milliseconds.
		 * @return the polling interval, or null
		 */
		@JsonProperty("pollInterval")
		@Nullable
		public Long pollInterval() {
			return this.pollInterval;
		}

		/**
		 * Checks if the task is in a terminal state (completed, failed, or cancelled).
		 * @return true if the task is in a terminal state
		 */
		public boolean isTerminal() {
			return status.isTerminal();
		}

		/**
		 * Creates a new builder for Task.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link Task}.
		 */
		public static class Builder {

			private String taskId;

			private TaskStatus status;

			private String statusMessage;

			private String createdAt;

			private String lastUpdatedAt;

			private Long ttl;

			private Long pollInterval;

			/**
			 * Sets the task identifier.
			 * @param taskId the task identifier
			 * @return this builder
			 */
			public Builder taskId(String taskId) {
				this.taskId = taskId;
				return this;
			}

			/**
			 * Sets the task status.
			 * @param status the task status
			 * @return this builder
			 */
			public Builder status(TaskStatus status) {
				this.status = status;
				return this;
			}

			/**
			 * Sets the optional status message.
			 * @param statusMessage human-readable status message
			 * @return this builder
			 */
			public Builder statusMessage(String statusMessage) {
				this.statusMessage = statusMessage;
				return this;
			}

			/**
			 * Sets the creation timestamp.
			 * @param createdAt ISO 8601 timestamp when the task was created
			 * @return this builder
			 */
			public Builder createdAt(String createdAt) {
				this.createdAt = createdAt;
				return this;
			}

			/**
			 * Sets the last updated timestamp.
			 * @param lastUpdatedAt ISO 8601 timestamp when the task was last updated
			 * @return this builder
			 */
			public Builder lastUpdatedAt(String lastUpdatedAt) {
				this.lastUpdatedAt = lastUpdatedAt;
				return this;
			}

			/**
			 * Sets both createdAt and lastUpdatedAt to the current time in ISO 8601
			 * format.
			 *
			 * <p>
			 * <strong>Note:</strong> Timestamps must be valid ISO 8601 format strings.
			 * This method uses {@code Instant.now().toString()} which produces compliant
			 * output.
			 * @return this builder
			 */
			public Builder timestamps() {
				String now = java.time.Instant.now().toString();
				this.createdAt = now;
				this.lastUpdatedAt = now;
				return this;
			}

			/**
			 * Sets the TTL (time-to-live) in milliseconds.
			 * @param ttl retention duration from creation in milliseconds, null for
			 * unlimited
			 * @return this builder
			 */
			public Builder ttl(Long ttl) {
				this.ttl = ttl;
				return this;
			}

			/**
			 * Sets the suggested polling interval in milliseconds.
			 * @param pollInterval polling interval in milliseconds
			 * @return this builder
			 */
			public Builder pollInterval(Long pollInterval) {
				this.pollInterval = pollInterval;
				return this;
			}

			/**
			 * Builds a new {@link Task} instance.
			 * @return a new Task instance
			 */
			public Task build() {
				return new Task(taskId, status, statusMessage, createdAt, lastUpdatedAt, ttl, pollInterval);
			}

		}

	}

	/**
	 * Metadata for augmenting a request with task execution. Include this in the
	 * {@code task} field of the request parameters to indicate that the operation should
	 * be executed as a background task rather than synchronously.
	 *
	 * <p>
	 * When present, the server creates a task and returns immediately with task
	 * information, allowing the client to poll for status and retrieve results later.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Task
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TaskMetadata {

		private final Long ttl;

		@JsonCreator
		private TaskMetadata(@JsonProperty("ttl") Long ttl) {
			if (ttl != null && ttl < 0) {
				throw new IllegalArgumentException("ttl must not be negative");
			}
			this.ttl = ttl;
		}

		/**
		 * Returns the TTL (time-to-live) in milliseconds.
		 * @return the TTL, or null for no specific retention
		 */
		@JsonProperty("ttl")
		public Long ttl() {
			return this.ttl;
		}

		/**
		 * Returns the TTL as a Duration, or null if not set.
		 * @return the TTL duration, or null
		 */
		@JsonIgnore
		public Duration ttlAsDuration() {
			return ttl != null ? Duration.ofMillis(ttl) : null;
		}

		/**
		 * Creates a new builder for TaskMetadata.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for creating TaskMetadata instances with Duration-based TTL.
		 */
		public static class Builder {

			private Duration ttl;

			/**
			 * Sets the TTL (time-to-live) for the task.
			 * @param ttl the duration to retain the task, converted to milliseconds
			 * @return this builder
			 */
			public Builder ttl(Duration ttl) {
				this.ttl = ttl;
				return this;
			}

			/**
			 * Builds the TaskMetadata instance.
			 * @return a new TaskMetadata
			 * @throws IllegalArgumentException if TTL is negative
			 */
			public TaskMetadata build() {
				Long ttlMs = this.ttl != null ? this.ttl.toMillis() : null;
				return new TaskMetadata(ttlMs);
			}

		}

	}

	/**
	 * The well-known key for related task metadata in the _meta field.
	 */
	public static final String RELATED_TASK_META_KEY = "io.modelcontextprotocol/related-task";

	/**
	 * Metadata for associating messages with a task. Include this in the {@code _meta}
	 * field under the key {@link #RELATED_TASK_META_KEY} to indicate that a notification
	 * or other message is related to a specific task.
	 *
	 * <p>
	 * This enables correlation of progress notifications, logging messages, and other
	 * communications with their originating task context.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Task
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class RelatedTaskMetadata {

		private final String taskId;

		@JsonCreator
		private RelatedTaskMetadata(@JsonProperty("taskId") String taskId) {
			Assert.hasText(taskId, "taskId must not be empty");
			this.taskId = taskId;
		}

		/**
		 * Returns the task identifier.
		 * @return the task identifier
		 */
		@JsonProperty("taskId")
		public String taskId() {
			return this.taskId;
		}

		/**
		 * Creates a new builder for RelatedTaskMetadata.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link RelatedTaskMetadata}.
		 */
		public static class Builder {

			private String taskId;

			/**
			 * Sets the task identifier.
			 * @param taskId the task identifier
			 * @return this builder
			 */
			public Builder taskId(String taskId) {
				this.taskId = taskId;
				return this;
			}

			/**
			 * Builds a new {@link RelatedTaskMetadata} instance.
			 * @return a new RelatedTaskMetadata instance
			 */
			public RelatedTaskMetadata build() {
				return new RelatedTaskMetadata(taskId);
			}

		}

	}

	// ---------------------------
	// Resource Interfaces
	// ---------------------------
	/**
	 * Base for objects that include optional annotations for the client. The client can
	 * use annotations to inform how objects are used or displayed
	 */
	public interface Annotated {

		Annotations annotations();

	}

	/**
	 * Optional annotations for the client. The client can use annotations to inform how
	 * objects are used or displayed.
	 *
	 * @param audience Describes who the intended customer of this object or data is. It
	 * can include multiple entries to indicate content useful for multiple audiences
	 * (e.g., `["user", "assistant"]`).
	 * @param priority Describes how important this data is for operating the server. A
	 * value of 1 means "most important," and indicates that the data is effectively
	 * required, while 0 means "least important," and indicates that the data is entirely
	 * optional. It is a number between 0 and 1.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Annotations( // @formatter:off
		@JsonProperty("audience") List<Role> audience,
		@JsonProperty("priority") Double priority,
		@JsonProperty("lastModified") String lastModified
		) { // @formatter:on

		public Annotations(List<Role> audience, Double priority) {
			this(audience, priority, null);
		}
	}

	/**
	 * A common interface for resource content, which includes metadata about the resource
	 * such as its URI, name, description, MIME type, size, and annotations. This
	 * interface is implemented by both {@link Resource} and {@link ResourceLink} to
	 * provide a consistent way to access resource metadata.
	 */
	public interface ResourceContent extends Identifier, Annotated, Meta {

		// name & title from Identifier

		String uri();

		String description();

		String mimeType();

		Long size();

		// annotations from Annotated
		// meta from Meta

	}

	/**
	 * Base interface with name (identifier) and title (display name) properties.
	 */
	public interface Identifier {

		/**
		 * Intended for programmatic or logical use, but used as a display name in past
		 * specs or fallback (if title isn't present).
		 */
		String name();

		/**
		 * Intended for UI and end-user contexts — optimized to be human-readable and
		 * easily understood, even by those unfamiliar with domain-specific terminology.
		 *
		 * If not provided, the name should be used for display.
		 */
		String title();

	}

	/**
	 * A known resource that the server is capable of reading.
	 *
	 * @param uri the URI of the resource.
	 * @param name A human-readable name for this resource. This can be used by clients to
	 * populate UI elements.
	 * @param title An optional title for this resource.
	 * @param description A description of what this resource represents. This can be used
	 * by clients to improve the LLM's understanding of available resources. It can be
	 * thought of like a "hint" to the model.
	 * @param mimeType The MIME type of this resource, if known.
	 * @param size The size of the raw resource content, in bytes (i.e., before base64
	 * encoding or any tokenization), if known. This can be used by Hosts to display file
	 * sizes and estimate context window usage.
	 * @param annotations Optional annotations for the client. The client can use
	 * annotations to inform how objects are used or displayed.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Resource( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("size") Long size,
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("_meta") Map<String, Object> meta) implements ResourceContent { // @formatter:on

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link Resource#builder()} instead.
		 */
		@Deprecated
		public Resource(String uri, String name, String title, String description, String mimeType, Long size,
				Annotations annotations) {
			this(uri, name, title, description, mimeType, size, annotations, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link Resource#builder()} instead.
		 */
		@Deprecated
		public Resource(String uri, String name, String description, String mimeType, Long size,
				Annotations annotations) {
			this(uri, name, null, description, mimeType, size, annotations, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link Resource#builder()} instead.
		 */
		@Deprecated
		public Resource(String uri, String name, String description, String mimeType, Annotations annotations) {
			this(uri, name, null, description, mimeType, null, annotations, null);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String uri;

			private String name;

			private String title;

			private String description;

			private String mimeType;

			private Long size;

			private Annotations annotations;

			private Map<String, Object> meta;

			public Builder uri(String uri) {
				this.uri = uri;
				return this;
			}

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder size(Long size) {
				this.size = size;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Resource build() {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");

				return new Resource(uri, name, title, description, mimeType, size, annotations, meta);
			}

		}
	}

	/**
	 * Resource templates allow servers to expose parameterized resources using URI
	 *
	 * @param uriTemplate A URI template that can be used to generate URIs for this
	 * resource.
	 * @param name A human-readable name for this resource. This can be used by clients to
	 * populate UI elements.
	 * @param title An optional title for this resource.
	 * @param description A description of what this resource represents. This can be used
	 * by clients to improve the LLM's understanding of available resources. It can be
	 * thought of like a "hint" to the model.
	 * @param mimeType The MIME type of this resource, if known.
	 * @param annotations Optional annotations for the client. The client can use
	 * annotations to inform how objects are used or displayed.
	 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6570">RFC 6570</a>
	 * @param meta See specification for notes on _meta usage
	 *
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceTemplate( // @formatter:off
		@JsonProperty("uriTemplate") String uriTemplate,
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("_meta") Map<String, Object> meta) implements Annotated, Identifier, Meta { // @formatter:on

		public ResourceTemplate(String uriTemplate, String name, String title, String description, String mimeType,
				Annotations annotations) {
			this(uriTemplate, name, title, description, mimeType, annotations, null);
		}

		public ResourceTemplate(String uriTemplate, String name, String description, String mimeType,
				Annotations annotations) {
			this(uriTemplate, name, null, description, mimeType, annotations);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String uriTemplate;

			private String name;

			private String title;

			private String description;

			private String mimeType;

			private Annotations annotations;

			private Map<String, Object> meta;

			public Builder uriTemplate(String uri) {
				this.uriTemplate = uri;
				return this;
			}

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceTemplate build() {
				Assert.hasText(uriTemplate, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");

				return new ResourceTemplate(uriTemplate, name, title, description, mimeType, annotations, meta);
			}

		}
	}

	/**
	 * The server's response to a resources/list request from the client.
	 *
	 * @param resources A list of resources that the server provides
	 * @param nextCursor An opaque token representing the pagination position after the
	 * last returned result. If present, there may be more results available
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListResourcesResult( // @formatter:off
		@JsonProperty("resources") List<Resource> resources,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

		public ListResourcesResult(List<Resource> resources, String nextCursor) {
			this(resources, nextCursor, null);
		}
	}

	/**
	 * The server's response to a resources/templates/list request from the client.
	 *
	 * @param resourceTemplates A list of resource templates that the server provides
	 * @param nextCursor An opaque token representing the pagination position after the
	 * last returned result. If present, there may be more results available
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListResourceTemplatesResult( // @formatter:off
		@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

		public ListResourceTemplatesResult(List<ResourceTemplate> resourceTemplates, String nextCursor) {
			this(resourceTemplates, nextCursor, null);
		}
	}

	/**
	 * Sent from the client to the server, to read a specific resource URI.
	 *
	 * @param uri The URI of the resource to read. The URI can use any protocol; it is up
	 * to the server how to interpret it
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadResourceRequest( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

		public ReadResourceRequest(String uri) {
			this(uri, null);
		}
	}

	/**
	 * The server's response to a resources/read request from the client.
	 *
	 * @param contents The contents of the resource
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadResourceResult( // @formatter:off
		@JsonProperty("contents") List<ResourceContents> contents,
		@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

		public ReadResourceResult(List<ResourceContents> contents) {
			this(contents, null);
		}
	}

	/**
	 * Sent from the client to request resources/updated notifications from the server
	 * whenever a particular resource changes.
	 *
	 * @param uri the URI of the resource to subscribe to. The URI can use any protocol;
	 * it is up to the server how to interpret it.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SubscribeRequest( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

		public SubscribeRequest(String uri) {
			this(uri, null);
		}
	}

	/**
	 * Sent from the client to request cancellation of resources/updated notifications
	 * from the server. This should follow a previous resources/subscribe request.
	 *
	 * @param uri The URI of the resource to unsubscribe from
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record UnsubscribeRequest( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

		public UnsubscribeRequest(String uri) {
			this(uri, null);
		}
	}

	/**
	 * The contents of a specific resource or sub-resource.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
			@JsonSubTypes.Type(value = BlobResourceContents.class) })
	public sealed interface ResourceContents extends Meta permits TextResourceContents, BlobResourceContents {

		/**
		 * The URI of this resource.
		 * @return the URI of this resource.
		 */
		String uri();

		/**
		 * The MIME type of this resource.
		 * @return the MIME type of this resource.
		 */
		String mimeType();

	}

	/**
	 * Text contents of a resource.
	 *
	 * @param uri the URI of this resource.
	 * @param mimeType the MIME type of this resource.
	 * @param text the text of the resource. This must only be set if the resource can
	 * actually be represented as text (not binary data).
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TextResourceContents( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("text") String text,
		@JsonProperty("_meta") Map<String, Object> meta) implements ResourceContents { // @formatter:on

		public TextResourceContents(String uri, String mimeType, String text) {
			this(uri, mimeType, text, null);
		}
	}

	/**
	 * Binary contents of a resource.
	 *
	 * @param uri the URI of this resource.
	 * @param mimeType the MIME type of this resource.
	 * @param blob a base64-encoded string representing the binary data of the resource.
	 * This must only be set if the resource can actually be represented as binary data
	 * (not text).
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BlobResourceContents( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("blob") String blob,
		@JsonProperty("_meta") Map<String, Object> meta) implements ResourceContents { // @formatter:on

		public BlobResourceContents(String uri, String mimeType, String blob) {
			this(uri, mimeType, blob, null);
		}
	}

	// ---------------------------
	// Prompt Interfaces
	// ---------------------------
	/**
	 * A prompt or prompt template that the server offers.
	 *
	 * @param name The name of the prompt or prompt template.
	 * @param title An optional title for the prompt.
	 * @param description An optional description of what this prompt provides.
	 * @param arguments A list of arguments to use for templating the prompt.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Prompt( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("arguments") List<PromptArgument> arguments,
		@JsonProperty("_meta") Map<String, Object> meta) implements Identifier { // @formatter:on

		public Prompt(String name, String description, List<PromptArgument> arguments) {
			this(name, null, description, arguments != null ? arguments : new ArrayList<>());
		}

		public Prompt(String name, String title, String description, List<PromptArgument> arguments) {
			this(name, title, description, arguments != null ? arguments : new ArrayList<>(), null);
		}
	}

	/**
	 * Describes an argument that a prompt can accept.
	 *
	 * @param name The name of the argument.
	 * @param title An optional title for the argument, which can be used in UI
	 * @param description A human-readable description of the argument.
	 * @param required Whether this argument must be provided.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptArgument( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("required") Boolean required) implements Identifier { // @formatter:on

		public PromptArgument(String name, String description, Boolean required) {
			this(name, null, description, required);
		}
	}

	/**
	 * Describes a message returned as part of a prompt.
	 *
	 * This is similar to `SamplingMessage`, but also supports the embedding of resources
	 * from the MCP server.
	 *
	 * @param role The sender or recipient of messages and data in a conversation.
	 * @param content The content of the message of type {@link Content}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptMessage( // @formatter:off
		@JsonProperty("role") Role role,
		@JsonProperty("content") Content content) { // @formatter:on
	}

	/**
	 * The server's response to a prompts/list request from the client.
	 *
	 * @param prompts A list of prompts that the server provides.
	 * @param nextCursor An optional cursor for pagination. If present, indicates there
	 * are more prompts available.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListPromptsResult( // @formatter:off
		@JsonProperty("prompts") List<Prompt> prompts,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("_meta") Map<String, Object> meta) implements Result  { // @formatter:on

		public ListPromptsResult(List<Prompt> prompts, String nextCursor) {
			this(prompts, nextCursor, null);
		}
	}

	/**
	 * Used by the client to get a prompt provided by the server.
	 *
	 * @param name The name of the prompt or prompt template.
	 * @param arguments Arguments to use for templating the prompt.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GetPromptRequest( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("arguments") Map<String, Object> arguments,
		@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

		public GetPromptRequest(String name, Map<String, Object> arguments) {
			this(name, arguments, null);
		}
	}

	/**
	 * The server's response to a prompts/get request from the client.
	 *
	 * @param description An optional description for the prompt.
	 * @param messages A list of messages to display as part of the prompt.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GetPromptResult( // @formatter:off
		@JsonProperty("description") String description,
		@JsonProperty("messages") List<PromptMessage> messages,
		@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

		public GetPromptResult(String description, List<PromptMessage> messages) {
			this(description, messages, null);
		}
	}

	// ---------------------------
	// Tool Interfaces
	// ---------------------------
	/**
	 * The server's response to a tools/list request from the client.
	 *
	 * @param tools A list of tools that the server provides.
	 * @param nextCursor An optional cursor for pagination. If present, indicates there
	 * are more tools available.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListToolsResult( // @formatter:off
		@JsonProperty("tools") List<Tool> tools,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

		public ListToolsResult(List<Tool> tools, String nextCursor) {
			this(tools, nextCursor, null);
		}
	}

	/**
	 * A JSON Schema object that describes the expected structure of arguments or output.
	 *
	 * @param type The type of the schema (e.g., "object")
	 * @param properties The properties of the schema object
	 * @param required List of required property names
	 * @param additionalProperties Whether additional properties are allowed
	 * @param defs Schema definitions using the newer $defs keyword
	 * @param definitions Schema definitions using the legacy definitions keyword
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JsonSchema( // @formatter:off
		@JsonProperty("type") String type,
		@JsonProperty("properties") Map<String, Object> properties,
		@JsonProperty("required") List<String> required,
		@JsonProperty("additionalProperties") Boolean additionalProperties,
		@JsonProperty("$defs") Map<String, Object> defs,
		@JsonProperty("definitions") Map<String, Object> definitions) { // @formatter:on
	}

	/**
	 * Additional properties describing a Tool to clients.
	 *
	 * NOTE: all properties in ToolAnnotations are **hints**. They are not guaranteed to
	 * provide a faithful description of tool behavior (including descriptive properties
	 * like `title`).
	 *
	 * Clients should never make tool use decisions based on ToolAnnotations received from
	 * untrusted servers.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ToolAnnotations( // @formatter:off
		@JsonProperty("title")  String title,
		@JsonProperty("readOnlyHint")   Boolean readOnlyHint,
		@JsonProperty("destructiveHint") Boolean destructiveHint,
		@JsonProperty("idempotentHint") Boolean idempotentHint,
		@JsonProperty("openWorldHint") Boolean openWorldHint,
		@JsonProperty("returnDirect") Boolean returnDirect) { // @formatter:on
	}

	/**
	 * Indicates whether a tool supports task-augmented execution.
	 */
	public enum TaskSupportMode {

		// @formatter:off
		/**
		 * Tool does not support task-augmented execution. This is the default when absent.
		 */
		@JsonProperty("forbidden") FORBIDDEN,
		/**
		 * Tool may support task-augmented execution.
		 */
		@JsonProperty("optional") OPTIONAL,
		/**
		 * Tool requires task-augmented execution.
		 */
		@JsonProperty("required") REQUIRED
		// @formatter:on

	}

	/**
	 * Execution-related properties for a tool.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ToolExecution {

		private final TaskSupportMode taskSupport;

		@JsonCreator
		private ToolExecution(@JsonProperty("taskSupport") TaskSupportMode taskSupport) {
			this.taskSupport = taskSupport;
		}

		/**
		 * Returns the task support mode for this tool.
		 * @return the task support mode
		 */
		public TaskSupportMode taskSupport() {
			return this.taskSupport;
		}

		/**
		 * Creates a new builder for ToolExecution.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link ToolExecution}.
		 */
		public static class Builder {

			private TaskSupportMode taskSupport;

			/**
			 * Sets the task support mode. Indicates whether this tool supports
			 * task-augmented execution. This allows clients to handle long-running
			 * operations through polling the task system. Default is
			 * {@link TaskSupportMode#FORBIDDEN} when absent.
			 * @param taskSupport the task support mode
			 * @return this builder
			 */
			public Builder taskSupport(TaskSupportMode taskSupport) {
				this.taskSupport = taskSupport;
				return this;
			}

			/**
			 * Builds a new {@link ToolExecution} instance.
			 * @return a new ToolExecution instance
			 */
			public ToolExecution build() {
				return new ToolExecution(taskSupport);
			}

		}

	}

	/**
	 * Represents a tool that the server provides. Tools enable servers to expose
	 * executable functionality to the system. Through these tools, you can interact with
	 * external systems, perform computations, and take actions in the real world.
	 *
	 * @param name A unique identifier for the tool. This name is used when calling the
	 * tool.
	 * @param title A human-readable title for the tool.
	 * @param description A human-readable description of what the tool does. This can be
	 * used by clients to improve the LLM's understanding of available tools.
	 * @param inputSchema A JSON Schema object that describes the expected structure of
	 * the arguments when calling this tool. This allows clients to validate tool
	 * arguments before sending them to the server.
	 * @param outputSchema An optional JSON Schema object defining the structure of the
	 * tool's output returned in the structuredContent field of a CallToolResult.
	 * @param execution Execution-related properties for the tool, including task support
	 * mode which indicates whether this tool supports task-augmented execution.
	 * @param annotations Optional additional tool information.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Tool( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("inputSchema") JsonSchema inputSchema,
		@JsonProperty("outputSchema") Map<String, Object> outputSchema,
		@JsonProperty("execution") ToolExecution execution,
		@JsonProperty("annotations") ToolAnnotations annotations,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name;

			private String title;

			private String description;

			private JsonSchema inputSchema;

			private Map<String, Object> outputSchema;

			private ToolExecution execution;

			private ToolAnnotations annotations;

			private Map<String, Object> meta;

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder inputSchema(JsonSchema inputSchema) {
				this.inputSchema = inputSchema;
				return this;
			}

			public Builder inputSchema(McpJsonMapper jsonMapper, String inputSchema) {
				this.inputSchema = parseSchema(jsonMapper, inputSchema);
				return this;
			}

			public Builder outputSchema(Map<String, Object> outputSchema) {
				this.outputSchema = outputSchema;
				return this;
			}

			public Builder outputSchema(McpJsonMapper jsonMapper, String outputSchema) {
				this.outputSchema = schemaToMap(jsonMapper, outputSchema);
				return this;
			}

			public Builder execution(ToolExecution execution) {
				this.execution = execution;
				return this;
			}

			public Builder annotations(ToolAnnotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Tool build() {
				Assert.hasText(name, "name must not be empty");
				return new Tool(name, title, description, inputSchema, outputSchema, execution, annotations, meta);
			}

		}
	}

	private static Map<String, Object> schemaToMap(McpJsonMapper jsonMapper, String schema) {
		try {
			return jsonMapper.readValue(schema, MAP_TYPE_REF);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid schema: " + schema, e);
		}
	}

	private static JsonSchema parseSchema(McpJsonMapper jsonMapper, String schema) {
		try {
			return jsonMapper.readValue(schema, JsonSchema.class);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid schema: " + schema, e);
		}
	}

	/**
	 * Used by the client to call a tool provided by the server.
	 *
	 * @param name The name of the tool to call. This must match a tool name from
	 * tools/list.
	 * @param arguments Arguments to pass to the tool. These must conform to the tool's
	 * input schema.
	 * @param task If specified, the caller is requesting task-augmented execution for
	 * this request. The request will return a CreateTaskResult immediately, and the
	 * actual result can be retrieved later via tasks/result.
	 * @param meta Optional metadata about the request. This can include additional
	 * information like `progressToken`
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CallToolRequest( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("arguments") Map<String, Object> arguments,
		@JsonProperty("task") TaskMetadata task,
		@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

		public CallToolRequest(McpJsonMapper jsonMapper, String name, String jsonArguments) {
			this(name, parseJsonArguments(jsonMapper, jsonArguments), null, null);
		}

		public CallToolRequest(String name, Map<String, Object> arguments) {
			this(name, arguments, null, null);
		}

		public CallToolRequest(String name, Map<String, Object> arguments, Map<String, Object> meta) {
			this(name, arguments, null, meta);
		}

		private static Map<String, Object> parseJsonArguments(McpJsonMapper jsonMapper, String jsonArguments) {
			try {
				return jsonMapper.readValue(jsonArguments, MAP_TYPE_REF);
			}
			catch (IOException e) {
				throw new IllegalArgumentException("Invalid arguments: " + jsonArguments, e);
			}
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name;

			private Map<String, Object> arguments;

			private TaskMetadata task;

			private Map<String, Object> meta;

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder arguments(Map<String, Object> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder arguments(McpJsonMapper jsonMapper, String jsonArguments) {
				this.arguments = parseJsonArguments(jsonMapper, jsonArguments);
				return this;
			}

			/**
			 * Sets task metadata for task-augmented execution.
			 * @param task the task metadata
			 * @return this builder
			 */
			public Builder task(TaskMetadata task) {
				this.task = task;
				return this;
			}

			/**
			 * Sets task metadata for task-augmented execution with the specified TTL.
			 * @param ttl requested duration in milliseconds to retain task from creation
			 * @return this builder
			 */
			public Builder task(Long ttl) {
				this.task = new TaskMetadata(ttl);
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Builder progressToken(Object progressToken) {
				if (this.meta == null) {
					this.meta = new HashMap<>();
				}
				this.meta.put("progressToken", progressToken);
				return this;
			}

			public CallToolRequest build() {
				Assert.hasText(name, "name must not be empty");
				return new CallToolRequest(name, arguments, task, meta);
			}

		}
	}

	/**
	 * The server's response to a tools/call request from the client.
	 *
	 * @param content A list of content items representing the tool's output. Each item
	 * can be text, an image, or an embedded resource.
	 * @param isError If true, indicates that the tool execution failed and the content
	 * contains error information. If false or absent, indicates successful execution.
	 * @param structuredContent An optional JSON object that represents the structured
	 * result of the tool call.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CallToolResult( // @formatter:off
		@JsonProperty("content") List<Content> content,
		@JsonProperty("isError") Boolean isError,
		@JsonProperty("structuredContent") Object structuredContent,
		@JsonProperty("_meta") Map<String, Object> meta) implements ServerTaskPayloadResult { // @formatter:on

		/**
		 * @deprecated use the builder instead.
		 */
		@Deprecated
		public CallToolResult(List<Content> content, Boolean isError) {
			this(content, isError, (Object) null, null);
		}

		/**
		 * @deprecated use the builder instead.
		 */
		@Deprecated
		public CallToolResult(List<Content> content, Boolean isError, Map<String, Object> structuredContent) {
			this(content, isError, structuredContent, null);
		}

		/**
		 * Creates a new instance of {@link CallToolResult} with a string containing the
		 * tool result.
		 * @param content The content of the tool result. This will be mapped to a
		 * one-sized list with a {@link TextContent} element.
		 * @param isError If true, indicates that the tool execution failed and the
		 * content contains error information. If false or absent, indicates successful
		 * execution.
		 */
		@Deprecated
		public CallToolResult(String content, Boolean isError) {
			this(List.of(new TextContent(content)), isError, null);
		}

		/**
		 * Creates a builder for {@link CallToolResult}.
		 * @return a new builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link CallToolResult}.
		 */
		public static class Builder {

			private List<Content> content = new ArrayList<>();

			private Boolean isError = false;

			private Object structuredContent;

			private Map<String, Object> meta;

			/**
			 * Sets the content list for the tool result.
			 * @param content the content list
			 * @return this builder
			 */
			public Builder content(List<Content> content) {
				Assert.notNull(content, "content must not be null");
				this.content = content;
				return this;
			}

			public Builder structuredContent(Object structuredContent) {
				Assert.notNull(structuredContent, "structuredContent must not be null");
				this.structuredContent = structuredContent;
				return this;
			}

			public Builder structuredContent(McpJsonMapper jsonMapper, String structuredContent) {
				Assert.hasText(structuredContent, "structuredContent must not be empty");
				try {
					this.structuredContent = jsonMapper.readValue(structuredContent, MAP_TYPE_REF);
				}
				catch (IOException e) {
					throw new IllegalArgumentException("Invalid structured content: " + structuredContent, e);
				}
				return this;
			}

			/**
			 * Sets the text content for the tool result.
			 * @param textContent the text content
			 * @return this builder
			 */
			public Builder textContent(List<String> textContent) {
				Assert.notNull(textContent, "textContent must not be null");
				textContent.stream().map(TextContent::new).forEach(this.content::add);
				return this;
			}

			/**
			 * Adds a content item to the tool result.
			 * @param contentItem the content item to add
			 * @return this builder
			 */
			public Builder addContent(Content contentItem) {
				Assert.notNull(contentItem, "contentItem must not be null");
				if (this.content == null) {
					this.content = new ArrayList<>();
				}
				this.content.add(contentItem);
				return this;
			}

			/**
			 * Adds a text content item to the tool result.
			 * @param text the text content
			 * @return this builder
			 */
			public Builder addTextContent(String text) {
				Assert.notNull(text, "text must not be null");
				return addContent(new TextContent(text));
			}

			/**
			 * Sets whether the tool execution resulted in an error.
			 * @param isError true if the tool execution failed, false otherwise
			 * @return this builder
			 */
			public Builder isError(Boolean isError) {
				Assert.notNull(isError, "isError must not be null");
				this.isError = isError;
				return this;
			}

			/**
			 * Sets the metadata for the tool result.
			 * @param meta metadata
			 * @return this builder
			 */
			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds a new {@link CallToolResult} instance.
			 * @return a new CallToolResult instance
			 */
			public CallToolResult build() {
				return new CallToolResult(content, isError, structuredContent, meta);
			}

		}

	}

	// ---------------------------
	// Sampling Interfaces
	// ---------------------------
	/**
	 * The server's preferences for model selection, requested of the client during
	 * sampling.
	 *
	 * @param hints Optional hints to use for model selection. If multiple hints are
	 * specified, the client MUST evaluate them in order (such that the first match is
	 * taken). The client SHOULD prioritize these hints over the numeric priorities, but
	 * MAY still use the priorities to select from ambiguous matches
	 * @param costPriority How much to prioritize cost when selecting a model. A value of
	 * 0 means cost is not important, while a value of 1 means cost is the most important
	 * factor
	 * @param speedPriority How much to prioritize sampling speed (latency) when selecting
	 * a model. A value of 0 means speed is not important, while a value of 1 means speed
	 * is the most important factor
	 * @param intelligencePriority How much to prioritize intelligence and capabilities
	 * when selecting a model. A value of 0 means intelligence is not important, while a
	 * value of 1 means intelligence is the most important factor
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelPreferences( // @formatter:off
		@JsonProperty("hints") List<ModelHint> hints,
		@JsonProperty("costPriority") Double costPriority,
		@JsonProperty("speedPriority") Double speedPriority,
		@JsonProperty("intelligencePriority") Double intelligencePriority) { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<ModelHint> hints;

			private Double costPriority;

			private Double speedPriority;

			private Double intelligencePriority;

			public Builder hints(List<ModelHint> hints) {
				this.hints = hints;
				return this;
			}

			public Builder addHint(String name) {
				if (this.hints == null) {
					this.hints = new ArrayList<>();
				}
				this.hints.add(new ModelHint(name));
				return this;
			}

			public Builder costPriority(Double costPriority) {
				this.costPriority = costPriority;
				return this;
			}

			public Builder speedPriority(Double speedPriority) {
				this.speedPriority = speedPriority;
				return this;
			}

			public Builder intelligencePriority(Double intelligencePriority) {
				this.intelligencePriority = intelligencePriority;
				return this;
			}

			public ModelPreferences build() {
				return new ModelPreferences(hints, costPriority, speedPriority, intelligencePriority);
			}

		}
	}

	/**
	 * Hints to use for model selection.
	 *
	 * @param name A hint for a model name. The client SHOULD treat this as a substring of
	 * a model name; for example: `claude-3-5-sonnet` should match
	 * `claude-3-5-sonnet-20241022`, `sonnet` should match `claude-3-5-sonnet-20241022`,
	 * `claude-3-sonnet-20240229`, etc., `claude` should match any Claude model. The
	 * client MAY also map the string to a different provider's model name or a different
	 * model family, as long as it fills a similar niche
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelHint(@JsonProperty("name") String name) {
	}

	/**
	 * Describes a message issued to or received from an LLM API.
	 *
	 * @param role The sender or recipient of messages and data in a conversation
	 * @param content The content of the message
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SamplingMessage( // @formatter:off
		@JsonProperty("role") Role role,
		@JsonProperty("content") Content content) { // @formatter:on
	}

	/**
	 * A request from the server to sample an LLM via the client. The client has full
	 * discretion over which model to select. The client should also inform the user
	 * before beginning sampling, to allow them to inspect the request (human in the loop)
	 * and decide whether to approve it.
	 *
	 * @param messages The conversation messages to send to the LLM
	 * @param modelPreferences The server's preferences for which model to select. The
	 * client MAY ignore these preferences
	 * @param systemPrompt An optional system prompt the server wants to use for sampling.
	 * The client MAY modify or omit this prompt
	 * @param includeContext A request to include context from one or more MCP servers
	 * (including the caller), to be attached to the prompt. The client MAY ignore this
	 * request
	 * @param temperature Optional temperature parameter for sampling
	 * @param maxTokens The maximum number of tokens to sample, as requested by the
	 * server. The client MAY choose to sample fewer tokens than requested
	 * @param stopSequences Optional stop sequences for sampling
	 * @param metadata Optional metadata to pass through to the LLM provider. The format
	 * of this metadata is provider-specific
	 * @param task If specified, the caller is requesting task-augmented execution for
	 * this request. The request will return a CreateTaskResult immediately, and the
	 * actual result can be retrieved later via tasks/result.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreateMessageRequest( // @formatter:off
		@JsonProperty("messages") List<SamplingMessage> messages,
		@JsonProperty("modelPreferences") ModelPreferences modelPreferences,
		@JsonProperty("systemPrompt") String systemPrompt,
		@JsonProperty("includeContext") ContextInclusionStrategy includeContext,
		@JsonProperty("temperature") Double temperature,
		@JsonProperty("maxTokens") Integer maxTokens,
		@JsonProperty("stopSequences") List<String> stopSequences,
		@JsonProperty("metadata") Map<String, Object> metadata,
		@JsonProperty("task") TaskMetadata task,
		@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

		// backwards compatibility constructor
		public CreateMessageRequest(List<SamplingMessage> messages, ModelPreferences modelPreferences,
				String systemPrompt, ContextInclusionStrategy includeContext, Double temperature, Integer maxTokens,
				List<String> stopSequences, Map<String, Object> metadata) {
			this(messages, modelPreferences, systemPrompt, includeContext, temperature, maxTokens, stopSequences,
					metadata, null, null);
		}

		// backwards compatibility constructor with _meta
		public CreateMessageRequest(List<SamplingMessage> messages, ModelPreferences modelPreferences,
				String systemPrompt, ContextInclusionStrategy includeContext, Double temperature, Integer maxTokens,
				List<String> stopSequences, Map<String, Object> metadata, Map<String, Object> meta) {
			this(messages, modelPreferences, systemPrompt, includeContext, temperature, maxTokens, stopSequences,
					metadata, null, meta);
		}

		public enum ContextInclusionStrategy {

		// @formatter:off
			@JsonProperty("none") NONE,
			@JsonProperty("thisServer") THIS_SERVER,
			@JsonProperty("allServers")ALL_SERVERS
		} // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<SamplingMessage> messages;

			private ModelPreferences modelPreferences;

			private String systemPrompt;

			private ContextInclusionStrategy includeContext;

			private Double temperature;

			private Integer maxTokens;

			private List<String> stopSequences;

			private Map<String, Object> metadata;

			private TaskMetadata task;

			private Map<String, Object> meta;

			public Builder messages(List<SamplingMessage> messages) {
				this.messages = messages;
				return this;
			}

			public Builder modelPreferences(ModelPreferences modelPreferences) {
				this.modelPreferences = modelPreferences;
				return this;
			}

			public Builder systemPrompt(String systemPrompt) {
				this.systemPrompt = systemPrompt;
				return this;
			}

			public Builder includeContext(ContextInclusionStrategy includeContext) {
				this.includeContext = includeContext;
				return this;
			}

			public Builder temperature(Double temperature) {
				this.temperature = temperature;
				return this;
			}

			public Builder maxTokens(int maxTokens) {
				this.maxTokens = maxTokens;
				return this;
			}

			public Builder stopSequences(List<String> stopSequences) {
				this.stopSequences = stopSequences;
				return this;
			}

			public Builder metadata(Map<String, Object> metadata) {
				this.metadata = metadata;
				return this;
			}

			/**
			 * Sets task metadata for task-augmented execution.
			 * @param task the task metadata
			 * @return this builder
			 */
			public Builder task(TaskMetadata task) {
				this.task = task;
				return this;
			}

			/**
			 * Sets task metadata for task-augmented execution with the specified TTL.
			 * @param ttl requested duration in milliseconds to retain task from creation
			 * @return this builder
			 */
			public Builder task(Long ttl) {
				this.task = new TaskMetadata(ttl);
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Builder progressToken(Object progressToken) {
				if (this.meta == null) {
					this.meta = new HashMap<>();
				}
				this.meta.put("progressToken", progressToken);
				return this;
			}

			public CreateMessageRequest build() {
				return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
						maxTokens, stopSequences, metadata, task, meta);
			}

		}
	}

	/**
	 * The client's response to a sampling/create_message request from the server. The
	 * client should inform the user before returning the sampled message, to allow them
	 * to inspect the response (human in the loop) and decide whether to allow the server
	 * to see it.
	 *
	 * @param role The role of the message sender (typically assistant)
	 * @param content The content of the sampled message
	 * @param model The name of the model that generated the message
	 * @param stopReason The reason why sampling stopped, if known
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreateMessageResult( // @formatter:off
		@JsonProperty("role") Role role,
		@JsonProperty("content") Content content,
		@JsonProperty("model") String model,
		@JsonProperty("stopReason") StopReason stopReason,
		@JsonProperty("_meta") Map<String, Object> meta) implements ClientTaskPayloadResult { // @formatter:on

		public enum StopReason {

		// @formatter:off
			@JsonProperty("endTurn") END_TURN("endTurn"),
			@JsonProperty("stopSequence") STOP_SEQUENCE("stopSequence"),
			@JsonProperty("maxTokens") MAX_TOKENS("maxTokens"),
			@JsonProperty("unknown") UNKNOWN("unknown");
			// @formatter:on

			private final String value;

			StopReason(String value) {
				this.value = value;
			}

			@JsonCreator
			private static StopReason of(String value) {
				return Arrays.stream(StopReason.values())
					.filter(stopReason -> stopReason.value.equals(value))
					.findFirst()
					.orElse(StopReason.UNKNOWN);
			}

		}

		public CreateMessageResult(Role role, Content content, String model, StopReason stopReason) {
			this(role, content, model, stopReason, null);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Role role = Role.ASSISTANT;

			private Content content;

			private String model;

			private StopReason stopReason = StopReason.END_TURN;

			private Map<String, Object> meta;

			public Builder role(Role role) {
				this.role = role;
				return this;
			}

			public Builder content(Content content) {
				this.content = content;
				return this;
			}

			public Builder model(String model) {
				this.model = model;
				return this;
			}

			public Builder stopReason(StopReason stopReason) {
				this.stopReason = stopReason;
				return this;
			}

			public Builder message(String message) {
				this.content = new TextContent(message);
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CreateMessageResult build() {
				return new CreateMessageResult(role, content, model, stopReason, meta);
			}

		}
	}

	// Elicitation
	/**
	 * A request from the server to elicit additional information from the user via the
	 * client.
	 *
	 * @param message The message to present to the user
	 * @param requestedSchema A restricted subset of JSON Schema. Only top-level
	 * properties are allowed, without nesting
	 * @param task If specified, the caller is requesting task-augmented execution for
	 * this request. The request will return a CreateTaskResult immediately, and the
	 * actual result can be retrieved later via tasks/result.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitRequest( // @formatter:off
		@JsonProperty("message") String message,
		@JsonProperty("requestedSchema") Map<String, Object> requestedSchema,
		@JsonProperty("task") TaskMetadata task,
		@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

		// backwards compatibility constructor
		public ElicitRequest(String message, Map<String, Object> requestedSchema) {
			this(message, requestedSchema, null, null);
		}

		// backwards compatibility constructor with _meta
		public ElicitRequest(String message, Map<String, Object> requestedSchema, Map<String, Object> meta) {
			this(message, requestedSchema, null, meta);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String message;

			private Map<String, Object> requestedSchema;

			private TaskMetadata task;

			private Map<String, Object> meta;

			public Builder message(String message) {
				this.message = message;
				return this;
			}

			public Builder requestedSchema(Map<String, Object> requestedSchema) {
				this.requestedSchema = requestedSchema;
				return this;
			}

			/**
			 * Sets task metadata for task-augmented execution.
			 * @param task the task metadata
			 * @return this builder
			 */
			public Builder task(TaskMetadata task) {
				this.task = task;
				return this;
			}

			/**
			 * Sets task metadata for task-augmented execution with the specified TTL.
			 * @param ttl requested duration in milliseconds to retain task from creation
			 * @return this builder
			 */
			public Builder task(Long ttl) {
				this.task = new TaskMetadata(ttl);
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Builder progressToken(Object progressToken) {
				if (this.meta == null) {
					this.meta = new HashMap<>();
				}
				this.meta.put("progressToken", progressToken);
				return this;
			}

			public ElicitRequest build() {
				return new ElicitRequest(message, requestedSchema, task, meta);
			}

		}
	}

	/**
	 * The client's response to an elicitation request.
	 *
	 * @param action The user action in response to the elicitation. "accept": User
	 * submitted the form/confirmed the action, "decline": User explicitly declined the
	 * action, "cancel": User dismissed without making an explicit choice
	 * @param content The submitted form data, only present when action is "accept".
	 * Contains values matching the requested schema
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitResult( // @formatter:off
		@JsonProperty("action") Action action,
		@JsonProperty("content") Map<String, Object> content,
		@JsonProperty("_meta") Map<String, Object> meta) implements ClientTaskPayloadResult { // @formatter:on

		public enum Action {

		// @formatter:off
			@JsonProperty("accept") ACCEPT,
			@JsonProperty("decline") DECLINE,
			@JsonProperty("cancel") CANCEL
		} // @formatter:on

		// backwards compatibility constructor
		public ElicitResult(Action action, Map<String, Object> content) {
			this(action, content, null);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Action action;

			private Map<String, Object> content;

			private Map<String, Object> meta;

			public Builder message(Action action) {
				this.action = action;
				return this;
			}

			public Builder content(Map<String, Object> content) {
				this.content = content;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ElicitResult build() {
				return new ElicitResult(action, content, meta);
			}

		}
	}

	// ---------------------------
	// Pagination Interfaces
	// ---------------------------
	/**
	 * A request that supports pagination using cursors.
	 *
	 * @param cursor An opaque token representing the current pagination position. If
	 * provided, the server should return results starting after this cursor
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PaginatedRequest( // @formatter:off
		@JsonProperty("cursor") String cursor,
		@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

		public PaginatedRequest(String cursor) {
			this(cursor, null);
		}

		/**
		 * Creates a new paginated request with an empty cursor.
		 */
		public PaginatedRequest() {
			this(null);
		}
	}

	/**
	 * An opaque token representing the pagination position after the last returned
	 * result. If present, there may be more results available.
	 *
	 * @param nextCursor An opaque token representing the pagination position after the
	 * last returned result. If present, there may be more results available
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PaginatedResult(@JsonProperty("nextCursor") String nextCursor) {
	}

	// ---------------------------
	// Progress and Logging
	// ---------------------------
	/**
	 * The Model Context Protocol (MCP) supports optional progress tracking for
	 * long-running operations through notification messages. Either side can send
	 * progress notifications to provide updates about operation status.
	 *
	 * @param progressToken A unique token to identify the progress notification. MUST be
	 * unique across all active requests.
	 * @param progress A value indicating the current progress.
	 * @param total An optional total amount of work to be done, if known.
	 * @param message An optional message providing additional context about the progress.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ProgressNotification( // @formatter:off
		@JsonProperty("progressToken") Object progressToken,
		@JsonProperty("progress") Double progress,
		@JsonProperty("total") Double total,
		@JsonProperty("message") String message,
		@JsonProperty("_meta") Map<String, Object> meta) implements Notification { // @formatter:on

		public ProgressNotification(Object progressToken, double progress, Double total, String message) {
			this(progressToken, progress, total, message, null);
		}
	}

	/**
	 * The Model Context Protocol (MCP) provides a standardized way for servers to send
	 * resources update message to clients.
	 *
	 * @param uri The updated resource uri.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourcesUpdatedNotification(// @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("_meta") Map<String, Object> meta) implements Notification { // @formatter:on

		public ResourcesUpdatedNotification(String uri) {
			this(uri, null);
		}
	}

	/**
	 * The Model Context Protocol (MCP) provides a standardized way for servers to send
	 * structured log messages to clients. Clients can control logging verbosity by
	 * setting minimum log levels, with servers sending notifications containing severity
	 * levels, optional logger names, and arbitrary JSON-serializable data.
	 *
	 * @param level The severity levels. The minimum log level is set by the client.
	 * @param logger The logger that generated the message.
	 * @param data JSON-serializable logging data.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LoggingMessageNotification( // @formatter:off
		@JsonProperty("level") LoggingLevel level,
		@JsonProperty("logger") String logger,
		@JsonProperty("data") String data,
		@JsonProperty("_meta") Map<String, Object> meta) implements Notification { // @formatter:on

		// backwards compatibility constructor
		public LoggingMessageNotification(LoggingLevel level, String logger, String data) {
			this(level, logger, data, null);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private LoggingLevel level = LoggingLevel.INFO;

			private String logger = "server";

			private String data;

			private Map<String, Object> meta;

			public Builder level(LoggingLevel level) {
				this.level = level;
				return this;
			}

			public Builder logger(String logger) {
				this.logger = logger;
				return this;
			}

			public Builder data(String data) {
				this.data = data;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public LoggingMessageNotification build() {
				return new LoggingMessageNotification(level, logger, data, meta);
			}

		}
	}

	public enum LoggingLevel {

	// @formatter:off
		@JsonProperty("debug") DEBUG(0),
		@JsonProperty("info") INFO(1),
		@JsonProperty("notice") NOTICE(2),
		@JsonProperty("warning") WARNING(3),
		@JsonProperty("error") ERROR(4),
		@JsonProperty("critical") CRITICAL(5),
		@JsonProperty("alert") ALERT(6),
		@JsonProperty("emergency") EMERGENCY(7);
		// @formatter:on

		private final int level;

		LoggingLevel(int level) {
			this.level = level;
		}

		public int level() {
			return level;
		}

	}

	/**
	 * A request from the client to the server, to enable or adjust logging.
	 *
	 * @param level The level of logging that the client wants to receive from the server.
	 * The server should send all logs at this level and higher (i.e., more severe) to the
	 * client as notifications/message
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SetLevelRequest(@JsonProperty("level") LoggingLevel level) {
	}

	// ---------------------------
	// Autocomplete
	// ---------------------------
	public sealed interface CompleteReference permits PromptReference, ResourceReference {

		String type();

		String identifier();

	}

	/**
	 * Identifies a prompt for completion requests.
	 *
	 * @param type The reference type identifier (typically "ref/prompt")
	 * @param name The name of the prompt
	 * @param title An optional title for the prompt
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptReference( // @formatter:off
		@JsonProperty("type") String type,
		@JsonProperty("name") String name,
		@JsonProperty("title") String title ) implements McpSchema.CompleteReference, Identifier { // @formatter:on

		public static final String TYPE = "ref/prompt";

		public PromptReference(String type, String name) {
			this(type, name, null);
		}

		public PromptReference(String name) {
			this(TYPE, name, null);
		}

		@Override
		public String identifier() {
			return name();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null || getClass() != obj.getClass())
				return false;
			PromptReference that = (PromptReference) obj;
			return java.util.Objects.equals(identifier(), that.identifier())
					&& java.util.Objects.equals(type(), that.type());
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(identifier(), type());
		}
	}

	/**
	 * A reference to a resource or resource template definition for completion requests.
	 *
	 * @param type The reference type identifier (typically "ref/resource")
	 * @param uri The URI or URI template of the resource
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceReference( // @formatter:off
		@JsonProperty("type") String type,
		@JsonProperty("uri") String uri) implements McpSchema.CompleteReference { // @formatter:on

		public static final String TYPE = "ref/resource";

		public ResourceReference(String uri) {
			this(TYPE, uri);
		}

		@Override
		public String identifier() {
			return uri();
		}
	}

	/**
	 * A request from the client to the server, to ask for completion options.
	 *
	 * @param ref A reference to a prompt or resource template definition
	 * @param argument The argument's information for completion requests
	 * @param meta See specification for notes on _meta usage
	 * @param context Additional, optional context for completions
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CompleteRequest( // @formatter:off
		@JsonProperty("ref") McpSchema.CompleteReference ref,
		@JsonProperty("argument") CompleteArgument argument,
		@JsonProperty("_meta") Map<String, Object> meta,
		@JsonProperty("context") CompleteContext context) implements Request { // @formatter:on

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument, Map<String, Object> meta) {
			this(ref, argument, meta, null);
		}

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument, CompleteContext context) {
			this(ref, argument, null, context);
		}

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument) {
			this(ref, argument, null, null);
		}

		/**
		 * The argument's information for completion requests.
		 *
		 * @param name The name of the argument
		 * @param value The value of the argument to use for completion matching
		 */
		public record CompleteArgument(@JsonProperty("name") String name, @JsonProperty("value") String value) {
		}

		/**
		 * Additional, optional context for completions.
		 *
		 * @param arguments Previously-resolved variables in a URI template or prompt
		 */
		public record CompleteContext(@JsonProperty("arguments") Map<String, String> arguments) {
		}
	}

	/**
	 * The server's response to a completion/complete request.
	 *
	 * @param completion The completion information containing values and metadata
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CompleteResult(// @formatter:off
			@JsonProperty("completion") CompleteCompletion completion,
			@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

		// backwards compatibility constructor
		public CompleteResult(CompleteCompletion completion) {
			this(completion, null);
		}

		/**
		 * The server's response to a completion/complete request
		 *
		 * @param values An array of completion values. Must not exceed 100 items
		 * @param total The total number of completion options available. This can exceed
		 * the number of values actually sent in the response
		 * @param hasMore Indicates whether there are additional completion options beyond
		 * those provided in the current response, even if the exact total is unknown
		 */
		@JsonInclude(JsonInclude.Include.ALWAYS)
		public record CompleteCompletion( // @formatter:off
				@JsonProperty("values") List<String> values,
				@JsonProperty("total") Integer total,
				@JsonProperty("hasMore") Boolean hasMore) { // @formatter:on
		}
	}

	// ---------------------------
	// Content Types
	// ---------------------------
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = "text"),
			@JsonSubTypes.Type(value = ImageContent.class, name = "image"),
			@JsonSubTypes.Type(value = AudioContent.class, name = "audio"),
			@JsonSubTypes.Type(value = EmbeddedResource.class, name = "resource"),
			@JsonSubTypes.Type(value = ResourceLink.class, name = "resource_link") })
	public sealed interface Content extends Meta
			permits TextContent, ImageContent, AudioContent, EmbeddedResource, ResourceLink {

		default String type() {
			if (this instanceof TextContent) {
				return "text";
			}
			else if (this instanceof ImageContent) {
				return "image";
			}
			else if (this instanceof AudioContent) {
				return "audio";
			}
			else if (this instanceof EmbeddedResource) {
				return "resource";
			}
			else if (this instanceof ResourceLink) {
				return "resource_link";
			}
			throw new IllegalArgumentException("Unknown content type: " + this);
		}

	}

	/**
	 * Text provided to or from an LLM.
	 *
	 * @param annotations Optional annotations for the client
	 * @param text The text content of the message
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TextContent( // @formatter:off
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("text") String text,
		@JsonProperty("_meta") Map<String, Object> meta) implements Annotated, Content { // @formatter:on

		public TextContent(Annotations annotations, String text) {
			this(annotations, text, null);
		}

		public TextContent(String content) {
			this(null, content, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link TextContent#TextContent(Annotations, String)} instead.
		 */
		@Deprecated
		public TextContent(List<Role> audience, Double priority, String content) {
			this(audience != null || priority != null ? new Annotations(audience, priority) : null, content, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link TextContent#annotations()} instead.
		 */
		@Deprecated
		public List<Role> audience() {
			return annotations == null ? null : annotations.audience();
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link TextContent#annotations()} instead.
		 */
		@Deprecated
		public Double priority() {
			return annotations == null ? null : annotations.priority();
		}
	}

	/**
	 * An image provided to or from an LLM.
	 *
	 * @param annotations Optional annotations for the client
	 * @param data The base64-encoded image data
	 * @param mimeType The MIME type of the image. Different providers may support
	 * different image types
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ImageContent( // @formatter:off
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("data") String data,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("_meta") Map<String, Object> meta) implements Annotated, Content { // @formatter:on

		public ImageContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ImageContent#ImageContent(Annotations, String, String)} instead.
		 */
		@Deprecated
		public ImageContent(List<Role> audience, Double priority, String data, String mimeType) {
			this(audience != null || priority != null ? new Annotations(audience, priority) : null, data, mimeType,
					null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ImageContent#annotations()} instead.
		 */
		@Deprecated
		public List<Role> audience() {
			return annotations == null ? null : annotations.audience();
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ImageContent#annotations()} instead.
		 */
		@Deprecated
		public Double priority() {
			return annotations == null ? null : annotations.priority();
		}
	}

	/**
	 * Audio provided to or from an LLM.
	 *
	 * @param annotations Optional annotations for the client
	 * @param data The base64-encoded audio data
	 * @param mimeType The MIME type of the audio. Different providers may support
	 * different audio types
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AudioContent( // @formatter:off
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("data") String data,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("_meta") Map<String, Object> meta) implements Annotated, Content { // @formatter:on

		// backwards compatibility constructor
		public AudioContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
		}
	}

	/**
	 * The contents of a resource, embedded into a prompt or tool call result.
	 *
	 * It is up to the client how best to render embedded resources for the benefit of the
	 * LLM and/or the user.
	 *
	 * @param annotations Optional annotations for the client
	 * @param resource The resource contents that are embedded
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddedResource( // @formatter:off
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("resource") ResourceContents resource,
		@JsonProperty("_meta") Map<String, Object> meta) implements Annotated, Content { // @formatter:on

		// backwards compatibility constructor
		public EmbeddedResource(Annotations annotations, ResourceContents resource) {
			this(annotations, resource, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link EmbeddedResource#EmbeddedResource(Annotations, ResourceContents)}
		 * instead.
		 */
		@Deprecated
		public EmbeddedResource(List<Role> audience, Double priority, ResourceContents resource) {
			this(audience != null || priority != null ? new Annotations(audience, priority) : null, resource, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link EmbeddedResource#annotations()} instead.
		 */
		@Deprecated
		public List<Role> audience() {
			return annotations == null ? null : annotations.audience();
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link EmbeddedResource#annotations()} instead.
		 */
		@Deprecated
		public Double priority() {
			return annotations == null ? null : annotations.priority();
		}
	}

	/**
	 * A known resource that the server is capable of reading.
	 *
	 * @param uri the URI of the resource.
	 * @param name A human-readable name for this resource. This can be used by clients to
	 * populate UI elements.
	 * @param title A human-readable title for this resource.
	 * @param description A description of what this resource represents. This can be used
	 * by clients to improve the LLM's understanding of available resources. It can be
	 * thought of like a "hint" to the model.
	 * @param mimeType The MIME type of this resource, if known.
	 * @param size The size of the raw resource content, in bytes (i.e., before base64
	 * encoding or any tokenization), if known. This can be used by Hosts to display file
	 * sizes and estimate context window usage.
	 * @param annotations Optional annotations for the client. The client can use
	 * annotations to inform how objects are used or displayed.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceLink( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("uri") String uri,
		@JsonProperty("description") String description,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("size") Long size,
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("_meta") Map<String, Object> meta) implements Content, ResourceContent { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name;

			private String title;

			private String uri;

			private String description;

			private String mimeType;

			private Annotations annotations;

			private Long size;

			private Map<String, Object> meta;

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder uri(String uri) {
				this.uri = uri;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder size(Long size) {
				this.size = size;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceLink build() {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");

				return new ResourceLink(name, title, uri, description, mimeType, size, annotations, meta);
			}

		}
	}

	// ---------------------------
	// Roots
	// ---------------------------
	/**
	 * Represents a root directory or file that the server can operate on.
	 *
	 * @param uri The URI identifying the root. This *must* start with file:// for now.
	 * This restriction may be relaxed in future versions of the protocol to allow other
	 * URI schemes.
	 * @param name An optional name for the root. This can be used to provide a
	 * human-readable identifier for the root, which may be useful for display purposes or
	 * for referencing the root in other parts of the application.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Root( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("name") String name,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public Root(String uri, String name) {
			this(uri, name, null);
		}
	}

	/**
	 * The client's response to a roots/list request from the server. This result contains
	 * an array of Root objects, each representing a root directory or file that the
	 * server can operate on.
	 *
	 * @param roots An array of Root objects, each representing a root directory or file
	 * that the server can operate on.
	 * @param nextCursor An optional cursor for pagination. If present, indicates there
	 * are more roots available. The client can use this cursor to request the next page
	 * of results by sending a roots/list request with the cursor parameter set to this
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListRootsResult( // @formatter:off
		@JsonProperty("roots") List<Root> roots,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

		public ListRootsResult(List<Root> roots) {
			this(roots, null);
		}

		public ListRootsResult(List<Root> roots, String nextCursor) {
			this(roots, nextCursor, null);
		}
	}

	// ---------------------------
	// Tasks
	// ---------------------------

	/*
	 * Note on meta fields in task types:
	 *
	 * All task-related types (GetTaskRequest, GetTaskResult, CancelTaskRequest,
	 * CancelTaskResult, CreateTaskResult, etc.) include optional "_meta" fields that may
	 * be null. This is intentional - the MCP specification defines these as optional
	 * extension points for protocol-level metadata. Callers should always check for null
	 * before accessing meta fields. When not using metadata extensions, simply pass null.
	 */

	/**
	 * A request to retrieve the state of a task.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class GetTaskRequest implements Request {

		private final String taskId;

		private final Map<String, Object> meta;

		@JsonCreator
		private GetTaskRequest( // @formatter:off
				@JsonProperty("taskId") String taskId,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.hasText(taskId, "taskId must not be empty");
			this.taskId = taskId;
			this.meta = meta;
		}

		/**
		 * Returns the task identifier.
		 * @return the task identifier
		 */
		@JsonProperty("taskId")
		public String taskId() {
			return this.taskId;
		}

		/**
		 * Returns the metadata.
		 * @return the metadata map, or null
		 */
		@JsonProperty("_meta")
		public Map<String, Object> meta() {
			return this.meta;
		}

		/**
		 * Creates a new builder for GetTaskRequest.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link GetTaskRequest}.
		 */
		public static class Builder {

			private String taskId;

			private Map<String, Object> meta;

			/**
			 * Sets the task identifier.
			 * @param taskId the task identifier
			 * @return this builder
			 */
			public Builder taskId(String taskId) {
				this.taskId = taskId;
				return this;
			}

			/**
			 * Sets the metadata.
			 * @param meta the metadata map
			 * @return this builder
			 */
			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds a new {@link GetTaskRequest} instance.
			 * @return a new GetTaskRequest instance
			 */
			public GetTaskRequest build() {
				return new GetTaskRequest(taskId, meta);
			}

		}

	}

	/**
	 * The response to a tasks/get request. Contains all Task fields plus Result metadata.
	 *
	 * <p>
	 * <strong>Design Note:</strong> This type is structurally identical to
	 * {@link CancelTaskResult} but kept as a separate type for compile-time type safety.
	 * This ensures that code expecting a {@code GetTaskResult} cannot accidentally
	 * receive a {@code CancelTaskResult} and vice versa, making API boundaries explicit
	 * in method signatures.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class GetTaskResult implements Result {

		private final String taskId;

		private final TaskStatus status;

		private final String statusMessage;

		private final String createdAt;

		private final String lastUpdatedAt;

		private final Long ttl;

		private final Long pollInterval;

		private final Map<String, Object> meta;

		@JsonCreator
		private GetTaskResult( // @formatter:off
				@JsonProperty("taskId") String taskId,
				@JsonProperty("status") TaskStatus status,
				@JsonProperty("statusMessage") @Nullable String statusMessage,
				@JsonProperty("createdAt") String createdAt,
				@JsonProperty("lastUpdatedAt") String lastUpdatedAt,
				@JsonProperty("ttl") @Nullable Long ttl,
				@JsonProperty("pollInterval") @Nullable Long pollInterval,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.hasText(taskId, "taskId must not be empty");
			Assert.notNull(status, "status must not be null");
			Assert.hasText(createdAt, "createdAt must not be empty");
			Assert.hasText(lastUpdatedAt, "lastUpdatedAt must not be empty");
			this.taskId = taskId;
			this.status = status;
			this.statusMessage = statusMessage;
			this.createdAt = createdAt;
			this.lastUpdatedAt = lastUpdatedAt;
			this.ttl = ttl;
			this.pollInterval = pollInterval;
			this.meta = meta;
		}

		/**
		 * Returns the task identifier.
		 * @return the task identifier
		 */
		@JsonProperty("taskId")
		public String taskId() {
			return this.taskId;
		}

		/**
		 * Returns the task status.
		 * @return the task status
		 */
		@JsonProperty("status")
		public TaskStatus status() {
			return this.status;
		}

		/**
		 * Returns the optional status message.
		 * @return the status message, or null
		 */
		@JsonProperty("statusMessage")
		@Nullable
		public String statusMessage() {
			return this.statusMessage;
		}

		/**
		 * Returns the creation timestamp.
		 * @return the ISO 8601 creation timestamp
		 */
		@JsonProperty("createdAt")
		public String createdAt() {
			return this.createdAt;
		}

		/**
		 * Returns the last updated timestamp.
		 * @return the ISO 8601 last updated timestamp
		 */
		@JsonProperty("lastUpdatedAt")
		public String lastUpdatedAt() {
			return this.lastUpdatedAt;
		}

		/**
		 * Returns the TTL (time-to-live) in milliseconds.
		 * @return the TTL, or null for unlimited
		 */
		@JsonProperty("ttl")
		@Nullable
		public Long ttl() {
			return this.ttl;
		}

		/**
		 * Returns the suggested polling interval in milliseconds.
		 * @return the polling interval, or null
		 */
		@JsonProperty("pollInterval")
		@Nullable
		public Long pollInterval() {
			return this.pollInterval;
		}

		/**
		 * Returns the metadata.
		 * @return the metadata map, or null
		 */
		@JsonProperty("_meta")
		public Map<String, Object> meta() {
			return this.meta;
		}

		/**
		 * Creates a GetTaskResult from a Task.
		 * @param task the task to convert
		 * @return a new GetTaskResult
		 */
		public static GetTaskResult fromTask(Task task) {
			return new GetTaskResult(task.taskId(), task.status(), task.statusMessage(), task.createdAt(),
					task.lastUpdatedAt(), task.ttl(), task.pollInterval(), null);
		}

		/**
		 * Converts this result to a Task.
		 * @return a Task with the same field values
		 */
		public Task toTask() {
			return Task.builder()
				.taskId(taskId)
				.status(status)
				.statusMessage(statusMessage)
				.createdAt(createdAt)
				.lastUpdatedAt(lastUpdatedAt)
				.ttl(ttl)
				.pollInterval(pollInterval)
				.build();
		}

		/**
		 * Checks if the task is in a terminal state (completed, failed, or cancelled).
		 * @return true if the task is in a terminal state
		 */
		public boolean isTerminal() {
			return status.isTerminal();
		}

		/**
		 * Creates a new builder for GetTaskResult.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link GetTaskResult}.
		 */
		public static class Builder {

			private String taskId;

			private TaskStatus status;

			private String statusMessage;

			private String createdAt;

			private String lastUpdatedAt;

			private Long ttl;

			private Long pollInterval;

			private Map<String, Object> meta;

			public Builder taskId(String taskId) {
				this.taskId = taskId;
				return this;
			}

			public Builder status(TaskStatus status) {
				this.status = status;
				return this;
			}

			public Builder statusMessage(String statusMessage) {
				this.statusMessage = statusMessage;
				return this;
			}

			public Builder createdAt(String createdAt) {
				this.createdAt = createdAt;
				return this;
			}

			public Builder lastUpdatedAt(String lastUpdatedAt) {
				this.lastUpdatedAt = lastUpdatedAt;
				return this;
			}

			public Builder ttl(Long ttl) {
				this.ttl = ttl;
				return this;
			}

			public Builder pollInterval(Long pollInterval) {
				this.pollInterval = pollInterval;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public GetTaskResult build() {
				return new GetTaskResult(taskId, status, statusMessage, createdAt, lastUpdatedAt, ttl, pollInterval,
						meta);
			}

		}

	}

	/**
	 * A request to retrieve the result payload of a completed task.
	 *
	 * <p>
	 * This corresponds to the {@code tasks/result} method in the MCP protocol. The name
	 * "Payload" distinguishes the actual result data (e.g., {@link CallToolResult},
	 * {@link CreateMessageResult}) from the task status information returned by
	 * {@link GetTaskResult}.
	 *
	 * <p>
	 * The response type depends on what created the task:
	 * <ul>
	 * <li>Tool calls: {@link CallToolResult}</li>
	 * <li>Sampling requests: {@link CreateMessageResult}</li>
	 * <li>Elicitation requests: {@link ElicitResult}</li>
	 * </ul>
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class GetTaskPayloadRequest implements Request {

		private final String taskId;

		private final Map<String, Object> meta;

		@JsonCreator
		private GetTaskPayloadRequest( // @formatter:off
				@JsonProperty("taskId") String taskId,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.hasText(taskId, "taskId must not be empty");
			this.taskId = taskId;
			this.meta = meta;
		}

		/**
		 * Returns the task identifier.
		 * @return the task identifier
		 */
		@JsonProperty("taskId")
		public String taskId() {
			return this.taskId;
		}

		/**
		 * Returns the metadata.
		 * @return the metadata map, or null
		 */
		@JsonProperty("_meta")
		public Map<String, Object> meta() {
			return this.meta;
		}

		/**
		 * Creates a new builder for GetTaskPayloadRequest.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link GetTaskPayloadRequest}.
		 */
		public static class Builder {

			private String taskId;

			private Map<String, Object> meta;

			/**
			 * Sets the task identifier.
			 * @param taskId the task identifier
			 * @return this builder
			 */
			public Builder taskId(String taskId) {
				this.taskId = taskId;
				return this;
			}

			/**
			 * Sets the metadata.
			 * @param meta the metadata map
			 * @return this builder
			 */
			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds a new {@link GetTaskPayloadRequest} instance.
			 * @return a new GetTaskPayloadRequest instance
			 */
			public GetTaskPayloadRequest build() {
				return new GetTaskPayloadRequest(taskId, meta);
			}

		}

	}

	/**
	 * A request to cancel a task.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CancelTaskRequest implements Request {

		private final String taskId;

		private final Map<String, Object> meta;

		@JsonCreator
		private CancelTaskRequest( // @formatter:off
				@JsonProperty("taskId") String taskId,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.hasText(taskId, "taskId must not be empty");
			this.taskId = taskId;
			this.meta = meta;
		}

		/**
		 * Returns the task identifier.
		 * @return the task identifier
		 */
		@JsonProperty("taskId")
		public String taskId() {
			return this.taskId;
		}

		/**
		 * Returns the metadata.
		 * @return the metadata map, or null
		 */
		@JsonProperty("_meta")
		public Map<String, Object> meta() {
			return this.meta;
		}

		/**
		 * Creates a new builder for CancelTaskRequest.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link CancelTaskRequest}.
		 */
		public static class Builder {

			private String taskId;

			private Map<String, Object> meta;

			/**
			 * Sets the task identifier.
			 * @param taskId the task identifier
			 * @return this builder
			 */
			public Builder taskId(String taskId) {
				this.taskId = taskId;
				return this;
			}

			/**
			 * Sets the metadata.
			 * @param meta the metadata map
			 * @return this builder
			 */
			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds a new {@link CancelTaskRequest} instance.
			 * @return a new CancelTaskRequest instance
			 */
			public CancelTaskRequest build() {
				return new CancelTaskRequest(taskId, meta);
			}

		}

	}

	/**
	 * The response to a tasks/cancel request. Contains all Task fields plus Result
	 * metadata.
	 *
	 * <p>
	 * <strong>Design Note:</strong> This type is structurally identical to
	 * {@link GetTaskResult} but kept as a separate type for compile-time type safety.
	 * This ensures that code expecting a {@code CancelTaskResult} cannot accidentally
	 * receive a {@code GetTaskResult} and vice versa, making API boundaries explicit in
	 * method signatures.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CancelTaskResult implements Result {

		private final String taskId;

		private final TaskStatus status;

		private final String statusMessage;

		private final String createdAt;

		private final String lastUpdatedAt;

		private final Long ttl;

		private final Long pollInterval;

		private final Map<String, Object> meta;

		@JsonCreator
		private CancelTaskResult( // @formatter:off
				@JsonProperty("taskId") String taskId,
				@JsonProperty("status") TaskStatus status,
				@JsonProperty("statusMessage") @Nullable String statusMessage,
				@JsonProperty("createdAt") String createdAt,
				@JsonProperty("lastUpdatedAt") String lastUpdatedAt,
				@JsonProperty("ttl") @Nullable Long ttl,
				@JsonProperty("pollInterval") @Nullable Long pollInterval,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.hasText(taskId, "taskId must not be empty");
			Assert.notNull(status, "status must not be null");
			Assert.hasText(createdAt, "createdAt must not be empty");
			Assert.hasText(lastUpdatedAt, "lastUpdatedAt must not be empty");
			this.taskId = taskId;
			this.status = status;
			this.statusMessage = statusMessage;
			this.createdAt = createdAt;
			this.lastUpdatedAt = lastUpdatedAt;
			this.ttl = ttl;
			this.pollInterval = pollInterval;
			this.meta = meta;
		}

		/**
		 * Returns the task identifier.
		 * @return the task identifier
		 */
		@JsonProperty("taskId")
		public String taskId() {
			return this.taskId;
		}

		/**
		 * Returns the task status.
		 * @return the task status
		 */
		@JsonProperty("status")
		public TaskStatus status() {
			return this.status;
		}

		/**
		 * Returns the optional status message.
		 * @return the status message, or null
		 */
		@JsonProperty("statusMessage")
		@Nullable
		public String statusMessage() {
			return this.statusMessage;
		}

		/**
		 * Returns the creation timestamp.
		 * @return the ISO 8601 creation timestamp
		 */
		@JsonProperty("createdAt")
		public String createdAt() {
			return this.createdAt;
		}

		/**
		 * Returns the last updated timestamp.
		 * @return the ISO 8601 last updated timestamp
		 */
		@JsonProperty("lastUpdatedAt")
		public String lastUpdatedAt() {
			return this.lastUpdatedAt;
		}

		/**
		 * Returns the TTL (time-to-live) in milliseconds.
		 * @return the TTL, or null for unlimited
		 */
		@JsonProperty("ttl")
		@Nullable
		public Long ttl() {
			return this.ttl;
		}

		/**
		 * Returns the suggested polling interval in milliseconds.
		 * @return the polling interval, or null
		 */
		@JsonProperty("pollInterval")
		@Nullable
		public Long pollInterval() {
			return this.pollInterval;
		}

		/**
		 * Returns the metadata.
		 * @return the metadata map, or null
		 */
		@JsonProperty("_meta")
		public Map<String, Object> meta() {
			return this.meta;
		}

		/**
		 * Creates a CancelTaskResult from a Task.
		 * @param task the task to convert
		 * @return a new CancelTaskResult
		 */
		public static CancelTaskResult fromTask(Task task) {
			return new CancelTaskResult(task.taskId(), task.status(), task.statusMessage(), task.createdAt(),
					task.lastUpdatedAt(), task.ttl(), task.pollInterval(), null);
		}

		/**
		 * Checks if the task is in a terminal state (completed, failed, or cancelled).
		 * @return true if the task is in a terminal state
		 */
		public boolean isTerminal() {
			return status.isTerminal();
		}

		/**
		 * Creates a new builder for CancelTaskResult.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link CancelTaskResult}.
		 */
		public static class Builder {

			private String taskId;

			private TaskStatus status;

			private String statusMessage;

			private String createdAt;

			private String lastUpdatedAt;

			private Long ttl;

			private Long pollInterval;

			private Map<String, Object> meta;

			public Builder taskId(String taskId) {
				this.taskId = taskId;
				return this;
			}

			public Builder status(TaskStatus status) {
				this.status = status;
				return this;
			}

			public Builder statusMessage(String statusMessage) {
				this.statusMessage = statusMessage;
				return this;
			}

			public Builder createdAt(String createdAt) {
				this.createdAt = createdAt;
				return this;
			}

			public Builder lastUpdatedAt(String lastUpdatedAt) {
				this.lastUpdatedAt = lastUpdatedAt;
				return this;
			}

			public Builder ttl(Long ttl) {
				this.ttl = ttl;
				return this;
			}

			public Builder pollInterval(Long pollInterval) {
				this.pollInterval = pollInterval;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CancelTaskResult build() {
				return new CancelTaskResult(taskId, status, statusMessage, createdAt, lastUpdatedAt, ttl, pollInterval,
						meta);
			}

		}

	}

	/**
	 * The response to a tasks/list request.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListTasksResult implements Result {

		private final List<Task> tasks;

		private final String nextCursor;

		private final Map<String, Object> meta;

		@JsonCreator
		private ListTasksResult( // @formatter:off
				@JsonProperty("tasks") List<Task> tasks,
				@JsonProperty("nextCursor") @Nullable String nextCursor,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			this.tasks = tasks != null ? tasks : List.of();
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		/**
		 * Returns the list of tasks.
		 * @return the tasks list, never null
		 */
		@JsonProperty("tasks")
		public List<Task> tasks() {
			return this.tasks;
		}

		/**
		 * Returns the next cursor for pagination.
		 * @return the next cursor, or null if no more results
		 */
		@JsonProperty("nextCursor")
		@Nullable
		public String nextCursor() {
			return this.nextCursor;
		}

		/**
		 * Returns the metadata.
		 * @return the metadata map, or null
		 */
		@JsonProperty("_meta")
		public Map<String, Object> meta() {
			return this.meta;
		}

		/**
		 * Creates a new builder for ListTasksResult.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link ListTasksResult}.
		 */
		public static class Builder {

			private List<Task> tasks;

			private String nextCursor;

			private Map<String, Object> meta;

			/**
			 * Sets the list of tasks.
			 * @param tasks the tasks list
			 * @return this builder
			 */
			public Builder tasks(List<Task> tasks) {
				this.tasks = tasks;
				return this;
			}

			/**
			 * Sets the next cursor for pagination.
			 * @param nextCursor the next cursor
			 * @return this builder
			 */
			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			/**
			 * Sets the metadata.
			 * @param meta the metadata map
			 * @return this builder
			 */
			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds a new {@link ListTasksResult} instance.
			 * @return a new ListTasksResult instance
			 */
			public ListTasksResult build() {
				return new ListTasksResult(tasks, nextCursor, meta);
			}

		}

	}

	/**
	 * A response to a task-augmented request, indicating that a task has been created.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CreateTaskResult implements Result {

		private final Task task;

		private final Map<String, Object> meta;

		@JsonCreator
		private CreateTaskResult( // @formatter:off
				@JsonProperty("task") Task task,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			this.task = task;
			this.meta = meta;
		}

		/**
		 * Returns the created task.
		 * @return the task
		 */
		@JsonProperty("task")
		public Task task() {
			return this.task;
		}

		/**
		 * Returns the metadata.
		 * @return the metadata map, or null
		 */
		@JsonProperty("_meta")
		public Map<String, Object> meta() {
			return this.meta;
		}

		/**
		 * Creates a new builder for CreateTaskResult.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link CreateTaskResult}.
		 */
		public static class Builder {

			private Task task;

			private Map<String, Object> meta;

			/**
			 * Sets the task.
			 * @param task the task
			 * @return this builder
			 */
			public Builder task(Task task) {
				this.task = task;
				return this;
			}

			/**
			 * Sets the metadata.
			 * @param meta the metadata map
			 * @return this builder
			 */
			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds a new {@link CreateTaskResult} instance.
			 * @return a new CreateTaskResult instance
			 */
			public CreateTaskResult build() {
				return new CreateTaskResult(task, meta);
			}

		}

	}

	/**
	 * An optional notification from the receiver to the requestor, informing them that a
	 * task's status has changed. Receivers are not required to send these notifications.
	 *
	 * <p>
	 * Use {@link #builder()} to create instances.
	 *
	 * @see Builder
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TaskStatusNotification implements Notification {

		private final String taskId;

		private final TaskStatus status;

		private final String statusMessage;

		private final String createdAt;

		private final String lastUpdatedAt;

		private final Long ttl;

		private final Long pollInterval;

		private final Map<String, Object> meta;

		@JsonCreator
		private TaskStatusNotification( // @formatter:off
				@JsonProperty("taskId") String taskId,
				@JsonProperty("status") TaskStatus status,
				@JsonProperty("statusMessage") @Nullable String statusMessage,
				@JsonProperty("createdAt") String createdAt,
				@JsonProperty("lastUpdatedAt") String lastUpdatedAt,
				@JsonProperty("ttl") @Nullable Long ttl,
				@JsonProperty("pollInterval") @Nullable Long pollInterval,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.hasText(taskId, "taskId must not be empty");
			Assert.notNull(status, "status must not be null");
			Assert.hasText(createdAt, "createdAt must not be empty");
			Assert.hasText(lastUpdatedAt, "lastUpdatedAt must not be empty");
			this.taskId = taskId;
			this.status = status;
			this.statusMessage = statusMessage;
			this.createdAt = createdAt;
			this.lastUpdatedAt = lastUpdatedAt;
			this.ttl = ttl;
			this.pollInterval = pollInterval;
			this.meta = meta;
		}

		/**
		 * Returns the task identifier.
		 * @return the task identifier
		 */
		@JsonProperty("taskId")
		public String taskId() {
			return this.taskId;
		}

		/**
		 * Returns the task status.
		 * @return the task status
		 */
		@JsonProperty("status")
		public TaskStatus status() {
			return this.status;
		}

		/**
		 * Returns the optional status message.
		 * @return the status message, or null
		 */
		@JsonProperty("statusMessage")
		@Nullable
		public String statusMessage() {
			return this.statusMessage;
		}

		/**
		 * Returns the creation timestamp.
		 * @return the ISO 8601 creation timestamp
		 */
		@JsonProperty("createdAt")
		public String createdAt() {
			return this.createdAt;
		}

		/**
		 * Returns the last updated timestamp.
		 * @return the ISO 8601 last updated timestamp
		 */
		@JsonProperty("lastUpdatedAt")
		public String lastUpdatedAt() {
			return this.lastUpdatedAt;
		}

		/**
		 * Returns the TTL (time-to-live) in milliseconds.
		 * @return the TTL, or null for unlimited
		 */
		@JsonProperty("ttl")
		@Nullable
		public Long ttl() {
			return this.ttl;
		}

		/**
		 * Returns the suggested polling interval in milliseconds.
		 * @return the polling interval, or null
		 */
		@JsonProperty("pollInterval")
		@Nullable
		public Long pollInterval() {
			return this.pollInterval;
		}

		/**
		 * Returns the metadata.
		 * @return the metadata map, or null
		 */
		@JsonProperty("_meta")
		public Map<String, Object> meta() {
			return this.meta;
		}

		/**
		 * Creates a TaskStatusNotification from a Task.
		 * @param task the task to convert
		 * @return a new TaskStatusNotification
		 */
		public static TaskStatusNotification fromTask(Task task) {
			return new TaskStatusNotification(task.taskId(), task.status(), task.statusMessage(), task.createdAt(),
					task.lastUpdatedAt(), task.ttl(), task.pollInterval(), null);
		}

		/**
		 * Checks if the task is in a terminal state (completed, failed, or cancelled).
		 * @return true if the task is in a terminal state
		 */
		public boolean isTerminal() {
			return status.isTerminal();
		}

		/**
		 * Creates a new builder for TaskStatusNotification.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link TaskStatusNotification}.
		 */
		public static class Builder {

			private String taskId;

			private TaskStatus status;

			private String statusMessage;

			private String createdAt;

			private String lastUpdatedAt;

			private Long ttl;

			private Long pollInterval;

			private Map<String, Object> meta;

			public Builder taskId(String taskId) {
				this.taskId = taskId;
				return this;
			}

			public Builder status(TaskStatus status) {
				this.status = status;
				return this;
			}

			public Builder statusMessage(String statusMessage) {
				this.statusMessage = statusMessage;
				return this;
			}

			public Builder createdAt(String createdAt) {
				this.createdAt = createdAt;
				return this;
			}

			public Builder lastUpdatedAt(String lastUpdatedAt) {
				this.lastUpdatedAt = lastUpdatedAt;
				return this;
			}

			public Builder ttl(Long ttl) {
				this.ttl = ttl;
				return this;
			}

			public Builder pollInterval(Long pollInterval) {
				this.pollInterval = pollInterval;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public TaskStatusNotification build() {
				return new TaskStatusNotification(taskId, status, statusMessage, createdAt, lastUpdatedAt, ttl,
						pollInterval, meta);
			}

		}

	}

	// --------------------------
	// Streaming Response Messages
	// --------------------------

	/**
	 * Sealed interface representing messages yielded during streaming request processing.
	 * Used by {@code callToolStream()} and other streaming APIs to provide real-time
	 * updates about task execution progress.
	 *
	 * <p>
	 * The message types are:
	 * <ul>
	 * <li>{@link TaskCreatedMessage} - First message for task-augmented requests,
	 * contains the created task
	 * <li>{@link TaskStatusMessage} - Status update during task polling
	 * <li>{@link ResultMessage} - Final successful result (terminal)
	 * <li>{@link ErrorMessage} - Error occurred (terminal)
	 * </ul>
	 *
	 * <h2>Streaming Order for Task-Augmented Requests</h2>
	 * <p>
	 * For task-augmented requests (those with {@code TaskMetadata}), messages are yielded
	 * in this order:
	 * <ol>
	 * <li>One {@link TaskCreatedMessage} - immediately after task creation</li>
	 * <li>Zero or more {@link TaskStatusMessage} - during polling while task is
	 * running</li>
	 * <li>One terminal message: either {@link ResultMessage} (success) or
	 * {@link ErrorMessage} (failure)</li>
	 * </ol>
	 *
	 * <p>
	 * For non-task requests, the stream yields only a single {@link ResultMessage} or
	 * {@link ErrorMessage}.
	 *
	 * @param <T> The type of result expected from the request
	 */
	public sealed interface ResponseMessage<T extends Result>
			permits TaskCreatedMessage, TaskStatusMessage, ResultMessage, ErrorMessage {

		/**
		 * Returns the message type identifier.
		 * @return the type string ("taskCreated", "taskStatus", "result", or "error")
		 */
		String type();

	}

	/**
	 * Message indicating a task has been created for a task-augmented request. This is
	 * the first message yielded for task-augmented requests.
	 *
	 * @param <T> The type of result expected from the request
	 */
	public static final class TaskCreatedMessage<T extends Result> implements ResponseMessage<T> {

		private final Task task;

		private TaskCreatedMessage(Task task) {
			this.task = task;
		}

		/**
		 * Returns the created task.
		 * @return the task
		 */
		public Task task() {
			return this.task;
		}

		@Override
		public String type() {
			return "taskCreated";
		}

		/**
		 * Creates a new TaskCreatedMessage with the given task.
		 * @param <T> the result type
		 * @param task the task
		 * @return a new TaskCreatedMessage
		 */
		public static <T extends Result> TaskCreatedMessage<T> of(Task task) {
			return new TaskCreatedMessage<>(task);
		}

	}

	/**
	 * Message indicating a task status update during polling. Yielded periodically while
	 * waiting for a task to reach a terminal state.
	 *
	 * @param <T> The type of result expected from the request
	 */
	public static final class TaskStatusMessage<T extends Result> implements ResponseMessage<T> {

		private final Task task;

		private TaskStatusMessage(Task task) {
			this.task = task;
		}

		/**
		 * Returns the task with updated status.
		 * @return the task
		 */
		public Task task() {
			return this.task;
		}

		@Override
		public String type() {
			return "taskStatus";
		}

		/**
		 * Creates a new TaskStatusMessage with the given task.
		 * @param <T> the result type
		 * @param task the task
		 * @return a new TaskStatusMessage
		 */
		public static <T extends Result> TaskStatusMessage<T> of(Task task) {
			return new TaskStatusMessage<>(task);
		}

	}

	/**
	 * Message containing the final successful result. This is a terminal message - no
	 * more messages will be yielded after this.
	 *
	 * @param <T> The type of result
	 * @param result The final result
	 */
	public static final class ResultMessage<T extends Result> implements ResponseMessage<T> {

		private final T result;

		private ResultMessage(T result) {
			this.result = result;
		}

		/**
		 * Returns the final result.
		 * @return the result
		 */
		public T result() {
			return this.result;
		}

		@Override
		public String type() {
			return "result";
		}

		/**
		 * Creates a new ResultMessage with the given result.
		 * @param <T> the result type
		 * @param result the result
		 * @return a new ResultMessage
		 */
		public static <T extends Result> ResultMessage<T> of(T result) {
			return new ResultMessage<>(result);
		}

	}

	/**
	 * Message indicating an error occurred. This is a terminal message - no more messages
	 * will be yielded after this.
	 *
	 * @param <T> The type of result expected from the request
	 * @param error The error that occurred
	 */
	public static final class ErrorMessage<T extends Result> implements ResponseMessage<T> {

		private final McpError error;

		private ErrorMessage(McpError error) {
			this.error = error;
		}

		/**
		 * Returns the error that occurred.
		 * @return the error
		 */
		public McpError error() {
			return this.error;
		}

		@Override
		public String type() {
			return "error";
		}

		/**
		 * Creates a new ErrorMessage with the given error.
		 * @param <T> the result type
		 * @param error the error
		 * @return a new ErrorMessage
		 */
		public static <T extends Result> ErrorMessage<T> of(McpError error) {
			return new ErrorMessage<>(error);
		}

	}

}
