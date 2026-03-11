/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.schema.resource.Annotated;
import io.modelcontextprotocol.spec.schema.resource.Identifier;
import io.modelcontextprotocol.spec.schema.resource.ResourceContent;
import io.modelcontextprotocol.spec.schema.resource.ResourceContents;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
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

	public static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

	private McpSchema() {
	}

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

	public interface Request extends Meta {

		default Object progressToken() {
			if (meta() != null && meta().containsKey("progressToken")) {
				return meta().get("progressToken");
			}
			return null;
		}

	}

	public interface Result extends Meta {

	}

	public interface Notification extends Meta {

	}

	public static final TypeRef<HashMap<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
	};

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
		@JsonProperty("elicitation") Elicitation elicitation) { // @formatter:on

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
		@JsonIgnoreProperties(ignoreUnknown = true)
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
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Elicitation(@JsonProperty("form") Form form, @JsonProperty("url") Url url) {

			/**
			 * Marker record indicating support for form-based elicitation mode.
			 */
			@JsonInclude(JsonInclude.Include.NON_ABSENT)
			@JsonIgnoreProperties(ignoreUnknown = true)
			public record Form() {
			}

			/**
			 * Marker record indicating support for URL-based elicitation mode.
			 */
			@JsonInclude(JsonInclude.Include.NON_ABSENT)
			@JsonIgnoreProperties(ignoreUnknown = true)
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

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Map<String, Object> experimental;

			private RootCapabilities roots;

			private Sampling sampling;

			private Elicitation elicitation;

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

			public ClientCapabilities build() {
				return new ClientCapabilities(experimental, roots, sampling, elicitation);
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
		@JsonProperty("tools") ToolCapabilities tools) { // @formatter:on

		/**
		 * Present if the server supports argument autocompletion suggestions.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record CompletionCapabilities() {
		}

		/**
		 * Present if the server supports sending log messages to the client.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record LoggingCapabilities() {
		}

		/**
		 * Present if the server offers any prompt templates.
		 *
		 * @param listChanged Whether this server supports notifications for changes to
		 * the prompt list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
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
		@JsonIgnoreProperties(ignoreUnknown = true)
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
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
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
	public interface CompleteReference {

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
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record CompleteArgument(@JsonProperty("name") String name, @JsonProperty("value") String value) {
		}

		/**
		 * Additional, optional context for completions.
		 *
		 * @param arguments Previously-resolved variables in a URI template or prompt
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
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
		@JsonIgnoreProperties(ignoreUnknown = true)
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
	public interface Content extends Meta {

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

}
