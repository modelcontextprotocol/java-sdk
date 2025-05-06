/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.util.Assert;

/**
 * Based on the <a href="http://www.jsonrpc.org/specification">JSON-RPC 2.0
 * specification</a> and the <a href=
 * "https://github.com/modelcontextprotocol/specification/blob/main/schema/2024-11-05/schema.ts">Model
 * Context Protocol Schema</a>.
 *
 * @author Christian Tzolov
 * @author Aliaksei Darafeyeu
 */
public final class McpSchema {

	private McpSchema() {
	}

	public static final String LATEST_PROTOCOL_VERSION = "2024-11-05";

	public static final String JSONRPC_VERSION = "2.0";

	// ---------------------------
	// Method Names
	// ---------------------------

	// Lifecycle Methods
	public static final String METHOD_INITIALIZE = "initialize";

	public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";

	public static final String METHOD_PING = "ping";

	// Tool Methods
	public static final String METHOD_TOOLS_LIST = "tools/list";

	public static final String METHOD_TOOLS_CALL = "tools/call";

	public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

	// Resources Methods
	public static final String METHOD_RESOURCES_LIST = "resources/list";

	public static final String METHOD_RESOURCES_READ = "resources/read";

	public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";

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

	// ---------------------------
	// JSON-RPC Error Codes
	// ---------------------------
	/**
	 * Standard error codes used in MCP JSON-RPC responses.
	 */
	public static final class ErrorCodes {

		ErrorCodes() {
			// Prevent instantiation
		}

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

	}

	public sealed interface Request
			permits InitializeRequest, CallToolRequest, CreateMessageRequest, CompleteRequest, GetPromptRequest {

	}

	// ---------------------------
	// JSON-RPC Message Types
	// ---------------------------
	public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCNotification, JSONRPCResponse {

		String jsonrpc();

	}

	public record JSONRPCRequest(String jsonrpc, String method, Object id, Object params) implements JSONRPCMessage {
	}

	public record JSONRPCNotification(String jsonrpc, String method, Object params) implements JSONRPCMessage {
	}

	public record JSONRPCResponse(String jsonrpc, Object id, Object result,
			JSONRPCError error) implements JSONRPCMessage {

		public record JSONRPCError(int code, String message, Object data) {
		}
	}

	// ---------------------------
	// Initialization
	// ---------------------------
	public record InitializeRequest(String protocolVersion, ClientCapabilities capabilities,
			Implementation clientInfo) implements Request {
	}

	public record InitializeResult(String protocolVersion, ServerCapabilities capabilities, Implementation serverInfo,
			String instructions) {
	}

	/**
	 * Clients can implement additional features to enrich connected MCP servers with
	 * additional capabilities. These capabilities can be used to extend the functionality
	 * of the server, or to provide additional information to the server about the
	 * client's capabilities.
	 *
	 * @param experimental WIP
	 * @param roots define the boundaries of where servers can operate within the
	 * filesystem, allowing them to understand which directories and files they have
	 * access to.
	 * @param sampling Provides a standardized way for servers to request LLM sampling
	 * (“completions” or “generations”) from language models via clients.
	 *
	 */

	public record ClientCapabilities(Map<String, Object> experimental, RootCapabilities roots, Sampling sampling) {

		/**
		 * Roots define the boundaries of where servers can operate within the filesystem,
		 * allowing them to understand which directories and files they have access to.
		 * Servers can request the list of roots from supporting clients and receive
		 * notifications when that list changes.
		 *
		 * @param listChanged Whether the client would send notification about roots has
		 * changed since the last time the server checked.
		 */

		public record RootCapabilities(Boolean listChanged) {
		}

		/**
		 * Provides a standardized way for servers to request LLM sampling ("completions"
		 * or "generations") from language models via clients. This flow allows clients to
		 * maintain control over model access, selection, and permissions while enabling
		 * servers to leverage AI capabilities—with no server API keys necessary. Servers
		 * can request text or image-based interactions and optionally include context
		 * from MCP servers in their prompts.
		 */

		public record Sampling() {
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Map<String, Object> experimental;

			private RootCapabilities roots;

			private Sampling sampling;

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

			public ClientCapabilities build() {
				return new ClientCapabilities(experimental, roots, sampling);
			}

		}
	}

	public record ServerCapabilities(CompletionCapabilities completions, Map<String, Object> experimental,
			LoggingCapabilities logging, PromptCapabilities prompts, ResourceCapabilities resources,
			ToolCapabilities tools) {

		public record CompletionCapabilities() {
		}

		public record LoggingCapabilities() {
		}

		public record PromptCapabilities(Boolean listChanged) {
		}

		public record ResourceCapabilities(Boolean subscribe, Boolean listChanged) {
		}

		public record ToolCapabilities(Boolean listChanged) {
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private CompletionCapabilities completions;

			private Map<String, Object> experimental;

			private LoggingCapabilities logging = new LoggingCapabilities();

			private PromptCapabilities prompts;

			private ResourceCapabilities resources;

			private ToolCapabilities tools;

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

			public ServerCapabilities build() {
				return new ServerCapabilities(completions, experimental, logging, prompts, resources, tools);
			}

		}
	}

	public record Implementation(String name, String version) {
	}

	// Existing Enums and Base Types (from previous implementation)
	public enum Role {

		USER, ASSISTANT

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

	public record Annotations(List<Role> audience, Double priority) {
	}

	/**
	 * A known resource that the server is capable of reading.
	 *
	 * @param uri the URI of the resource.
	 * @param name A human-readable name for this resource. This can be used by clients to
	 * populate UI elements.
	 * @param description A description of what this resource represents. This can be used
	 * by clients to improve the LLM's understanding of available resources. It can be
	 * thought of like a "hint" to the model.
	 * @param mimeType The MIME type of this resource, if known.
	 * @param annotations Optional annotations for the client. The client can use
	 * annotations to inform how objects are used or displayed.
	 */

	public record Resource(String uri, String name, String description, String mimeType,
			Annotations annotations) implements Annotated {
	}

	/**
	 * Resource templates allow servers to expose parameterized resources using URI
	 * templates.
	 *
	 * @param uriTemplate A URI template that can be used to generate URIs for this
	 * resource.
	 * @param name A human-readable name for this resource. This can be used by clients to
	 * populate UI elements.
	 * @param description A description of what this resource represents. This can be used
	 * by clients to improve the LLM's understanding of available resources. It can be
	 * thought of like a "hint" to the model.
	 * @param mimeType The MIME type of this resource, if known.
	 * @param annotations Optional annotations for the client. The client can use
	 * annotations to inform how objects are used or displayed.
	 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6570">RFC 6570</a>
	 */

	public record ResourceTemplate(String uriTemplate, String name, String description, String mimeType,
			Annotations annotations) implements Annotated {
	}

	public record ListResourcesResult(List<Resource> resources, String nextCursor) {
	}

	public record ListResourceTemplatesResult(List<ResourceTemplate> resourceTemplates, String nextCursor) {
	}

	public record ReadResourceRequest(String uri) {
	}

	public record ReadResourceResult(List<ResourceContents> contents) {
	}

	/**
	 * Sent from the client to request resources/updated notifications from the server
	 * whenever a particular resource changes.
	 *
	 * @param uri the URI of the resource to subscribe to. The URI can use any protocol;
	 * it is up to the server how to interpret it.
	 */

	public record SubscribeRequest(String uri) {
	}

	public record UnsubscribeRequest(String uri) {
	}

	/**
	 * The contents of a specific resource or sub-resource.
	 */
	public sealed interface ResourceContents permits TextResourceContents, BlobResourceContents {

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
	 */

	public record TextResourceContents(String uri, String mimeType, String text) implements ResourceContents {
	}

	/**
	 * Binary contents of a resource.
	 *
	 * @param uri the URI of this resource.
	 * @param mimeType the MIME type of this resource.
	 * @param blob a base64-encoded string representing the binary data of the resource.
	 * This must only be set if the resource can actually be represented as binary data
	 * (not text).
	 */

	public record BlobResourceContents(String uri, String mimeType, String blob) implements ResourceContents {
	}

	// ---------------------------
	// Prompt Interfaces
	// ---------------------------
	/**
	 * A prompt or prompt template that the server offers.
	 *
	 * @param name The name of the prompt or prompt template.
	 * @param description An optional description of what this prompt provides.
	 * @param arguments A list of arguments to use for templating the prompt.
	 */

	public record Prompt(String name, String description, List<PromptArgument> arguments) {
	}

	/**
	 * Describes an argument that a prompt can accept.
	 *
	 * @param name The name of the argument.
	 * @param description A human-readable description of the argument.
	 * @param required Whether this argument must be provided.
	 */

	public record PromptArgument(String name, String description, Boolean required) {
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

	public record PromptMessage(Role role, Content content) {
	}

	/**
	 * The server's response to a prompts/list request from the client.
	 *
	 * @param prompts A list of prompts that the server provides.
	 * @param nextCursor An optional cursor for pagination. If present, indicates there
	 * are more prompts available.
	 */

	public record ListPromptsResult(List<Prompt> prompts, String nextCursor) {
	}

	/**
	 * Used by the client to get a prompt provided by the server.
	 *
	 * @param name The name of the prompt or prompt template.
	 * @param arguments Arguments to use for templating the prompt.
	 */

	public record GetPromptRequest(String name, Map<String, Object> arguments) implements Request {
	}

	/**
	 * The server's response to a prompts/get request from the client.
	 *
	 * @param description An optional description for the prompt.
	 * @param messages A list of messages to display as part of the prompt.
	 */

	public record GetPromptResult(String description, List<PromptMessage> messages) {
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
	 */

	public record ListToolsResult(List<Tool> tools, String nextCursor) {
	}

	public record JsonSchema(String type, Map<String, Object> properties, List<String> required,
			Boolean additionalProperties, Map<String, Object> defs, Map<String, Object> definitions) {
	}

	/**
	 * Represents a tool that the server provides. Tools enable servers to expose
	 * executable functionality to the system. Through these tools, you can interact with
	 * external systems, perform computations, and take actions in the real world.
	 *
	 * @param name A unique identifier for the tool. This name is used when calling the
	 * tool.
	 * @param description A human-readable description of what the tool does. This can be
	 * used by clients to improve the LLM's understanding of available tools.
	 * @param inputSchema A JSON Schema object that describes the expected structure of
	 * the arguments when calling this tool. This allows clients to validate tool
	 * arguments before sending them to the server.
	 */

	public record Tool(String name, String description, JsonSchema inputSchema) {

	}

	/**
	 * Used by the client to call a tool provided by the server.
	 *
	 * @param name The name of the tool to call. This must match a tool name from
	 * tools/list.
	 * @param arguments Arguments to pass to the tool. These must conform to the tool's
	 * input schema.
	 */

	public record CallToolRequest(String name, Map<String, Object> arguments) implements Request {
	}

	/**
	 * The server's response to a tools/call request from the client.
	 *
	 * @param content A list of content items representing the tool's output. Each item
	 * can be text, an image, or an embedded resource.
	 * @param isError If true, indicates that the tool execution failed and the content
	 * contains error information. If false or absent, indicates successful execution.
	 */

	public record CallToolResult(List<Content> content, Boolean isError) {

		/**
		 * Creates a new instance of {@link CallToolResult} with a string containing the
		 * tool result.
		 * @param content The content of the tool result. This will be mapped to a
		 * one-sized list with a {@link TextContent} element.
		 * @param isError If true, indicates that the tool execution failed and the
		 * content contains error information. If false or absent, indicates successful
		 * execution.
		 */
		public CallToolResult(String content, Boolean isError) {
			this(List.of(new TextContent(content)), isError);
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

			private Boolean isError;

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
			 * Builds a new {@link CallToolResult} instance.
			 * @return a new CallToolResult instance
			 */
			public CallToolResult build() {
				return new CallToolResult(content, isError);
			}

		}

	}

	// ---------------------------
	// Sampling Interfaces
	// ---------------------------

	public record ModelPreferences(List<ModelHint> hints, Double costPriority, Double speedPriority,
			Double intelligencePriority) {

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

	public record ModelHint(String name) {
		public static ModelHint of(String name) {
			return new ModelHint(name);
		}
	}

	public record SamplingMessage(Role role, Content content) {
	}

	// Sampling and Message Creation

	public record CreateMessageRequest(List<SamplingMessage> messages, ModelPreferences modelPreferences,
			String systemPrompt, ContextInclusionStrategy includeContext, Double temperature, int maxTokens,
			List<String> stopSequences, Map<String, Object> metadata) implements Request {

		public enum ContextInclusionStrategy {

			NONE, THIS_SERVER, ALL_SERVERS

		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<SamplingMessage> messages;

			private ModelPreferences modelPreferences;

			private String systemPrompt;

			private ContextInclusionStrategy includeContext;

			private Double temperature;

			private int maxTokens;

			private List<String> stopSequences;

			private Map<String, Object> metadata;

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

			public CreateMessageRequest build() {
				return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
						maxTokens, stopSequences, metadata);
			}

		}
	}

	public record CreateMessageResult(Role role, Content content, String model, StopReason stopReason) {

		public enum StopReason {

			END_TURN, STOP_SEQUENCE, MAX_TOKENS

		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Role role = Role.ASSISTANT;

			private Content content;

			private String model;

			private StopReason stopReason = StopReason.END_TURN;

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

			public CreateMessageResult build() {
				return new CreateMessageResult(role, content, model, stopReason);
			}

		}
	}

	// ---------------------------
	// Pagination Interfaces
	// ---------------------------

	public record PaginatedRequest(String cursor) {
	}

	public record PaginatedResult(String nextCursor) {
	}

	// ---------------------------
	// Progress and Logging
	// ---------------------------

	public record ProgressNotification(String progressToken, double progress, Double total) {
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
	 */

	public record LoggingMessageNotification(LoggingLevel level, String logger, String data) {

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private LoggingLevel level = LoggingLevel.INFO;

			private String logger = "server";

			private String data;

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

			public LoggingMessageNotification build() {
				return new LoggingMessageNotification(level, logger, data);
			}

		}
	}

	public enum LoggingLevel {

		DEBUG(0), INFO(1), NOTICE(2), WARNING(3), ERROR(4), CRITICAL(5), ALERT(6), EMERGENCY(7);

		private final int level;

		LoggingLevel(int level) {
			this.level = level;
		}

		public int level() {
			return level;
		}

	}

	public record SetLevelRequest(LoggingLevel level) {
	}

	// ---------------------------
	// Autocomplete
	// ---------------------------
	public sealed interface CompleteReference permits PromptReference, ResourceReference {

		String type();

		String identifier();

	}

	public record PromptReference(String type, String name) implements CompleteReference {

		public PromptReference(String name) {
			this("ref/prompt", name);
		}

		@Override
		public String identifier() {
			return name();
		}
	}

	public record ResourceReference(String type, String uri) implements CompleteReference {

		public ResourceReference(String uri) {
			this("ref/resource", uri);
		}

		@Override
		public String identifier() {
			return uri();
		}
	}

	public record CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument) implements Request {

		public record CompleteArgument(String name, String value) {
		}
	}

	public record CompleteResult(CompleteCompletion completion) {

		public record CompleteCompletion(List<String> values, Integer total, Boolean hasMore) {
		}
	}

	// ---------------------------
	// Content Types
	// ---------------------------
	public sealed interface Content permits TextContent, ImageContent, EmbeddedResource {

		default String type() {
			if (this instanceof TextContent) {
				return "text";
			}
			else if (this instanceof ImageContent) {
				return "image";
			}
			else if (this instanceof EmbeddedResource) {
				return "resource";
			}
			throw new IllegalArgumentException("Unknown content type: " + this);
		}

	}

	public record TextContent(List<Role> audience, Double priority, String text) implements Content {

		public TextContent(String content) {
			this(null, null, content);
		}
	}

	public record ImageContent(List<Role> audience, Double priority, String data, String mimeType) implements Content {
	}

	public record EmbeddedResource(List<Role> audience, Double priority, ResourceContents resource) implements Content {
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
	 */
	public record Root(String uri, String name) {
	}

	/**
	 * The client's response to a roots/list request from the server. This result contains
	 * an array of Root objects, each representing a root directory or file that the
	 * server can operate on.
	 *
	 * @param roots An array of Root objects, each representing a root directory or file
	 * that the server can operate on.
	 */
	public record ListRootsResult(List<Root> roots) {
	}

}
