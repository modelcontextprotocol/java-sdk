/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
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
 * @author Zachary German
 */
public final class McpSchema {

	private static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

	private McpSchema() {
	}

	public static final String LATEST_PROTOCOL_VERSION = "2024-11-05";

	public static final String JSONRPC_VERSION = "2.0";

	public static final String FIRST_PAGE = null;

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

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

	}

	/**
	 * MCP JSON-RPC Message ID wrapper: MUST be non-null String or Int.
	 * <p>
	 * <b>Note</b>: This does <b>not</b> follow the JSON-RPC 'id' specification, which is
	 * nullable and could be a floating-point.
	 * </p>
	 */
	@JsonSerialize(using = MessageId.Serializer.class)
	@JsonDeserialize(using = MessageId.Deserializer.class)
	public static final class MessageId {

		private final Object value;

		public MessageId(String value) {
			this.value = Objects.requireNonNull(value, "'id' must not be null");
		}

		public MessageId(Integer value) {
			this.value = Objects.requireNonNull(value, "'id' must not be null");
		}

		public static MessageId of(Object raw) {
			if (raw instanceof String s)
				return new MessageId(s);
			if (raw instanceof Integer i)
				return new MessageId(i);
			throw new IllegalArgumentException("MCP 'id' must be String or Integer");
		}

		public boolean isString() {
			return value instanceof String;
		}

		public boolean isInteger() {
			return value instanceof Integer;
		}

		public String asString() {
			return (String) value;
		}

		public Integer asInteger() {
			return (Integer) value;
		}

		public Object raw() {
			return value;
		}

		@Override
		public String toString() {
			return String.valueOf(value);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;
			if (o == null || getClass() != o.getClass())
				return false;
			MessageId messageId = (MessageId) o;
			return value.equals(messageId.value);
		}

		@Override
		public int hashCode() {
			return value.hashCode();
		}

		public static class Deserializer extends JsonDeserializer<MessageId> {

			@Override
			public MessageId deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
				JsonToken t = p.getCurrentToken();
				if (t == JsonToken.VALUE_STRING) {
					return new MessageId(p.getText());
				}
				else if (t == JsonToken.VALUE_NUMBER_INT) {
					return new MessageId(p.getIntValue());
				}
				throw JsonMappingException.from(p, "MCP 'id' must be a non-null String or Integer");
			}

		}

		public static class Serializer extends JsonSerializer<MessageId> {

			@Override
			public void serialize(MessageId id, JsonGenerator gen, SerializerProvider serializers) throws IOException {
				if (id.isString()) {
					gen.writeString(id.asString());
				}
				else {
					gen.writeNumber(id.asInteger());
				}
			}

		}

	}

	public sealed interface Request permits InitializeRequest, CallToolRequest, CreateMessageRequest, ElicitRequest,
			CompleteRequest, GetPromptRequest, PaginatedRequest, ReadResourceRequest {

		Map<String, Object> meta();

		default String progressToken() {
			if (meta() != null && meta().containsKey("progressToken")) {
				return meta().get("progressToken").toString();
			}
			return null;
		}

	}

	private static final TypeReference<HashMap<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * Deserializes a JSON string into a JSONRPCMessage object.
	 * @param objectMapper The ObjectMapper instance to use for deserialization
	 * @param jsonText The JSON string to deserialize
	 * @return A JSONRPCMessage instance using either the {@link JSONRPCRequest},
	 * {@link JSONRPCNotification}, or {@link JSONRPCResponse} classes.
	 * @throws IOException If there's an error during deserialization
	 * @throws IllegalArgumentException If the JSON structure doesn't match any known
	 * message type
	 */
	public static JSONRPCMessage deserializeJsonRpcMessage(ObjectMapper objectMapper, String jsonText)
			throws IOException {

		logger.debug("Received JSON message: {}", jsonText);

		var map = objectMapper.readValue(jsonText, MAP_TYPE_REF);

		// Determine message type based on specific JSON structure
		if (map.containsKey("method") && map.containsKey("id")) {
			return objectMapper.convertValue(map, JSONRPCRequest.class);
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			return objectMapper.convertValue(map, JSONRPCNotification.class);
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			return objectMapper.convertValue(map, JSONRPCResponse.class);
		}

		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

	// ---------------------------
	// JSON-RPC Message Types
	// ---------------------------
	public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCNotification, JSONRPCResponse {

		String jsonrpc();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	// TODO: batching support
	// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	public record JSONRPCRequest( // @formatter:off
						@JsonProperty("jsonrpc") String jsonrpc,
						@JsonProperty("method") String method,
						@JsonProperty("id") MessageId id,
						@JsonProperty("params") Object params) implements JSONRPCMessage {
		} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	// TODO: batching support
	// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	public record JSONRPCNotification( // @formatter:off
						@JsonProperty("jsonrpc") String jsonrpc,
						@JsonProperty("method") String method,
						@JsonProperty("params") Object params) implements JSONRPCMessage {
		} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	// TODO: batching support
	// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	public record JSONRPCResponse( // @formatter:off
						@JsonProperty("jsonrpc") String jsonrpc,
						@JsonProperty("id") MessageId id,
						@JsonProperty("result") Object result,
						@JsonProperty("error") JSONRPCError error) implements JSONRPCMessage {

				@JsonInclude(JsonInclude.Include.NON_ABSENT)
				@JsonIgnoreProperties(ignoreUnknown = true)
				public record JSONRPCError(
						@JsonProperty("code") int code,
						@JsonProperty("message") String message,
						@JsonProperty("data") Object data) {
				}
		}// @formatter:on

	// ---------------------------
	// Initialization
	// ---------------------------
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record InitializeRequest( // @formatter:off
				@JsonProperty("protocolVersion") String protocolVersion,
				@JsonProperty("capabilities") ClientCapabilities capabilities,
				@JsonProperty("clientInfo") Implementation clientInfo,
				@JsonProperty("_meta") Map<String, Object> meta) implements Request {
			
			public InitializeRequest(String protocolVersion, ClientCapabilities capabilities, Implementation clientInfo) {
				this(protocolVersion, capabilities, clientInfo, null);
			}
		} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record InitializeResult( // @formatter:off
				@JsonProperty("protocolVersion") String protocolVersion,
				@JsonProperty("capabilities") ServerCapabilities capabilities,
				@JsonProperty("serverInfo") Implementation serverInfo,
				@JsonProperty("instructions") String instructions) {
		} // @formatter:on

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
	 * @param elicitation Provides a standardized way for servers to request additional
	 * information from users through the client during interactions.
	 *
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ClientCapabilities( // @formatter:off
				@JsonProperty("experimental") Map<String, Object> experimental,
				@JsonProperty("roots") RootCapabilities roots,
				@JsonProperty("sampling") Sampling sampling,
				@JsonProperty("elicitation") Elicitation elicitation) {

				/**
				 * Roots define the boundaries of where servers can operate within the filesystem,
				 * allowing them to understand which directories and files they have access to.
				 * Servers can request the list of roots from supporting clients and
				 * receive notifications when that list changes.
				 *
				 * @param listChanged Whether the client would send notification about roots
				 *                has changed since the last time the server checked.
				 */
				@JsonInclude(JsonInclude.Include.NON_ABSENT)
				@JsonIgnoreProperties(ignoreUnknown = true)
				public record RootCapabilities(
						@JsonProperty("listChanged") Boolean listChanged) {
				}

				/**
				 * Provides a standardized way for servers to request LLM
				 * sampling ("completions" or "generations") from language
				 * models via clients. This flow allows clients to maintain
				 * control over model access, selection, and permissions
				 * while enabling servers to leverage AI capabilities—with
				 * no server API keys necessary. Servers can request text or
				 * image-based interactions and optionally include context
				 * from MCP servers in their prompts.
				 */
				@JsonInclude(JsonInclude.Include.NON_ABSENT)
				public record Sampling() {
				}

				/**
				 * Provides a standardized way for servers to request additional
				 * information from users through the client during interactions.
				 * This flow allows clients to maintain control over user
				 * interactions and data sharing while enabling servers to gather
				 * necessary information dynamically. Servers can request structured
				 * data from users with optional JSON schemas to validate responses.
				 */
				@JsonInclude(JsonInclude.Include.NON_ABSENT)
				public record Elicitation() {
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

						public Builder elicitation() {
								this.elicitation = new Elicitation();
								return this;
						}

						public ClientCapabilities build() {
								return new ClientCapabilities(experimental, roots, sampling, elicitation);
						}
				}
		}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ServerCapabilities( // @formatter:off
			@JsonProperty("completions") CompletionCapabilities completions,
				@JsonProperty("experimental") Map<String, Object> experimental,
				@JsonProperty("logging") LoggingCapabilities logging,
				@JsonProperty("prompts") PromptCapabilities prompts,
				@JsonProperty("resources") ResourceCapabilities resources,
				@JsonProperty("tools") ToolCapabilities tools) {

				@JsonInclude(JsonInclude.Include.NON_ABSENT)
				public record CompletionCapabilities() {
				}

				@JsonInclude(JsonInclude.Include.NON_ABSENT)
				public record LoggingCapabilities() {
				}

				@JsonInclude(JsonInclude.Include.NON_ABSENT)
				public record PromptCapabilities(
						@JsonProperty("listChanged") Boolean listChanged) {
				}

				@JsonInclude(JsonInclude.Include.NON_ABSENT)
				public record ResourceCapabilities(
						@JsonProperty("subscribe") Boolean subscribe,
						@JsonProperty("listChanged") Boolean listChanged) {
				}

				@JsonInclude(JsonInclude.Include.NON_ABSENT)
				public record ToolCapabilities(
						@JsonProperty("listChanged") Boolean listChanged) {
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
		} // @formatter:on

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
	public record Implementation(// @formatter:off
				@JsonProperty("name") String name,
				@JsonProperty("title") String title,
				@JsonProperty("version") String version) implements BaseMetadata {// @formatter:on			

		public Implementation(String name, String version) {
			this(name, null, version);
		}
	}

	// Existing Enums and Base Types (from previous implementation)
	public enum Role {// @formatter:off

				@JsonProperty("user") USER,
				@JsonProperty("assistant") ASSISTANT
		}// @formatter:on

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
				@JsonProperty("priority") Double priority) {
		} // @formatter:on

	/**
	 * A common interface for resource content, which includes metadata about the resource
	 * such as its URI, name, description, MIME type, size, and annotations. This
	 * interface is implemented by both {@link Resource} and {@link ResourceLink} to
	 * provide a consistent way to access resource metadata.
	 */
	public interface ResourceContent extends BaseMetadata {

		String uri();

		String description();

		String mimeType();

		Long size();

		Annotations annotations();

	}

	/**
	 * Base interface for metadata with name (identifier) and title (display name)
	 * properties.
	 */
	public interface BaseMetadata {

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
				@JsonProperty("annotations") Annotations annotations) implements Annotated, ResourceContent {// @formatter:on

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link Resource#builder()} instead.
		 */
		@Deprecated
		public Resource(String uri, String name, String description, String mimeType, Long size,
				Annotations annotations) {
			this(uri, name, null, description, mimeType, null, annotations);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link Resource#builder()} instead.
		 */
		@Deprecated
		public Resource(String uri, String name, String description, String mimeType, Annotations annotations) {
			this(uri, name, null, description, mimeType, null, annotations);
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

			public Resource build() {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");

				return new Resource(uri, name, title, description, mimeType, size, annotations);
			}

		}
	}

	/**
	 * Resource templates allow servers to expose parameterized resources using URI
	 * templates.
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
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceTemplate( // @formatter:off
				@JsonProperty("uriTemplate") String uriTemplate,
				@JsonProperty("name") String name,
				@JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType,
				@JsonProperty("annotations") Annotations annotations) implements Annotated, BaseMetadata {// @formatter:on

		public ResourceTemplate(String uriTemplate, String name, String description, String mimeType,
				Annotations annotations) {
			this(uriTemplate, name, null, description, mimeType, annotations);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListResourcesResult( // @formatter:off
				@JsonProperty("resources") List<Resource> resources,
				@JsonProperty("nextCursor") String nextCursor) {
		} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListResourceTemplatesResult( // @formatter:off
				@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
				@JsonProperty("nextCursor") String nextCursor) {
		} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadResourceRequest( // @formatter:off
				@JsonProperty("uri") String uri,
				@JsonProperty("_meta") Map<String, Object> meta) implements Request {// @formatter:on

		public ReadResourceRequest(String uri) {
			this(uri, null);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadResourceResult( // @formatter:off
				@JsonProperty("contents") List<ResourceContents> contents){
		} // @formatter:on

	/**
	 * Sent from the client to request resources/updated notifications from the server
	 * whenever a particular resource changes.
	 *
	 * @param uri the URI of the resource to subscribe to. The URI can use any protocol;
	 * it is up to the server how to interpret it.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SubscribeRequest( // @formatter:off
				@JsonProperty("uri") String uri){
		} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record UnsubscribeRequest( // @formatter:off
				@JsonProperty("uri") String uri){
		} // @formatter:on

	/**
	 * The contents of a specific resource or sub-resource.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, include = As.PROPERTY)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class, name = "text"),
			@JsonSubTypes.Type(value = BlobResourceContents.class, name = "blob") })
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
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TextResourceContents( // @formatter:off
				@JsonProperty("uri") String uri,
				@JsonProperty("mimeType") String mimeType,
				@JsonProperty("text") String text) implements ResourceContents {
		} // @formatter:on

	/**
	 * Binary contents of a resource.
	 *
	 * @param uri the URI of this resource.
	 * @param mimeType the MIME type of this resource.
	 * @param blob a base64-encoded string representing the binary data of the resource.
	 * This must only be set if the resource can actually be represented as binary data
	 * (not text).
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BlobResourceContents( // @formatter:off
				@JsonProperty("uri") String uri,
				@JsonProperty("mimeType") String mimeType,
				@JsonProperty("blob") String blob) implements ResourceContents {
		} // @formatter:on

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
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Prompt( // @formatter:off
				@JsonProperty("name") String name,
				@JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("arguments") List<PromptArgument> arguments) implements BaseMetadata { // @formatter:on

		public Prompt(String name, String description, List<PromptArgument> arguments) {
			this(name, null, description, arguments != null ? arguments : new ArrayList<>());
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
				@JsonProperty("required") Boolean required) implements BaseMetadata {// @formatter:on

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
				@JsonProperty("content") Content content) {
		} // @formatter:on

	/**
	 * The server's response to a prompts/list request from the client.
	 *
	 * @param prompts A list of prompts that the server provides.
	 * @param nextCursor An optional cursor for pagination. If present, indicates there
	 * are more prompts available.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListPromptsResult( // @formatter:off
				@JsonProperty("prompts") List<Prompt> prompts,
				@JsonProperty("nextCursor") String nextCursor) {
		}// @formatter:on

	/**
	 * Used by the client to get a prompt provided by the server.
	 *
	 * @param name The name of the prompt or prompt template.
	 * @param arguments Arguments to use for templating the prompt.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GetPromptRequest(// @formatter:off
				@JsonProperty("name") String name,
				@JsonProperty("arguments") Map<String, Object> arguments,
				@JsonProperty("_meta") Map<String, Object> meta) implements Request {

				public GetPromptRequest(String name, Map<String, Object> arguments) {
						this(name, arguments, null);
				}
		}// @formatter:off

		/**
		 * The server's response to a prompts/get request from the client.
		 *
		 * @param description An optional description for the prompt.
		 * @param messages A list of messages to display as part of the prompt.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record GetPromptResult( // @formatter:off
				@JsonProperty("description") String description,
				@JsonProperty("messages") List<PromptMessage> messages) {
		} // @formatter:on

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
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListToolsResult( // @formatter:off
				@JsonProperty("tools") List<Tool> tools,
				@JsonProperty("nextCursor") String nextCursor) {
		}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JsonSchema( // @formatter:off
				@JsonProperty("type") String type,
				@JsonProperty("properties") Map<String, Object> properties,
				@JsonProperty("required") List<String> required,
				@JsonProperty("additionalProperties") Boolean additionalProperties,
				@JsonProperty("$defs") Map<String, Object> defs,
				@JsonProperty("definitions") Map<String, Object> definitions) {
		} // @formatter:on

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
		@JsonProperty("returnDirect") Boolean returnDirect) {
	} // @formatter:on

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
	 * @param outputSchema An optional JSON Schema object defining the structure of the
	 * tool's output returned in the structuredContent field of a CallToolResult.
	 * @param annotations Optional additional tool information.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Tool( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("inputSchema") JsonSchema inputSchema,
		@JsonProperty("outputSchema") Map<String, Object> outputSchema,
		@JsonProperty("annotations") ToolAnnotations annotations) {// @formatter:on

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link Tool#builder()} instead.
		 */
		@Deprecated
		public Tool(String name, String description, JsonSchema inputSchema, ToolAnnotations annotations) {
			this(name, null, description, inputSchema, null, annotations);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link Tool#builder()} instead.
		 */
		@Deprecated
		public Tool(String name, String description, String inputSchema) {
			this(name, null, description, parseSchema(inputSchema), null, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link Tool#builder()} instead.
		 */
		@Deprecated
		public Tool(String name, String description, String schema, ToolAnnotations annotations) {
			this(name, null, description, parseSchema(schema), null, annotations);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link Tool#builder()} instead.
		 */
		@Deprecated
		public Tool(String name, String description, String inputSchema, String outputSchema,
				ToolAnnotations annotations) {
			this(name, null, description, parseSchema(inputSchema), schemaToMap(outputSchema), annotations);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name;

			private String title;

			private String description;

			private JsonSchema inputSchema;

			private Map<String, Object> outputSchema;

			private ToolAnnotations annotations;

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

			public Builder inputSchema(String inputSchema) {
				this.inputSchema = parseSchema(inputSchema);
				return this;
			}

			public Builder outputSchema(Map<String, Object> outputSchema) {
				this.outputSchema = outputSchema;
				return this;
			}

			public Builder outputSchema(String outputSchema) {
				this.outputSchema = schemaToMap(outputSchema);
				return this;
			}

			public Builder annotations(ToolAnnotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Tool build() {
				Assert.hasText(name, "name must not be empty");
				return new Tool(name, title, description, inputSchema, outputSchema, annotations);
			}

		}
	}

	private static Map<String, Object> schemaToMap(String schema) {
		try {
			return OBJECT_MAPPER.readValue(schema, MAP_TYPE_REF);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid schema: " + schema, e);
		}
	}

	private static JsonSchema parseSchema(String schema) {
		try {
			return OBJECT_MAPPER.readValue(schema, JsonSchema.class);
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
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CallToolRequest(// @formatter:off
				@JsonProperty("name") String name,
				@JsonProperty("arguments") Map<String, Object> arguments,
				@JsonProperty("_meta") Map<String, Object> meta) implements Request {// @formatter:off

				public CallToolRequest(String name, String jsonArguments) {
						this(name, parseJsonArguments(jsonArguments), null);
				}
				public CallToolRequest(String name, Map<String, Object> arguments) {
						this(name, arguments, null);
				}

				private static Map<String, Object> parseJsonArguments(String jsonArguments) {
						try {
								return OBJECT_MAPPER.readValue(jsonArguments, MAP_TYPE_REF);
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
						private Map<String, Object> meta;

						public Builder name(String name) {
								this.name = name;
								return this;
						}

						public Builder arguments(Map<String, Object> arguments) {
								this.arguments = arguments;
								return this;
						}

						public Builder arguments(String jsonArguments) {
								this.arguments = parseJsonArguments(jsonArguments);
								return this;
						}

						public Builder meta(Map<String, Object> meta) {
								this.meta = meta;
								return this;
						}

						public Builder progressToken(String progressToken) {
								if (this.meta == null) {
										this.meta = new HashMap<>();
								}
								this.meta.put("progressToken", progressToken);
								return this;
						}

						public CallToolRequest build() {
								Assert.hasText(name, "name must not be empty");
								return new CallToolRequest(name, arguments, meta);
						}
				}
		}

		/**
		 * The server's response to a tools/call request from the client.
		 *
		 * @param content A list of content items representing the tool's output. Each item can be text, an image,
		 *                or an embedded resource.
		 * @param isError If true, indicates that the tool execution failed and the content contains error information.
		 *                If false or absent, indicates successful execution.
		 * @param structuredContent An optional JSON object that represents the structured result of the tool call.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record CallToolResult( // @formatter:off
				@JsonProperty("content") List<Content> content,
				@JsonProperty("isError") Boolean isError,
				@JsonProperty("structuredContent") Map<String, Object> structuredContent) {// @formatter:on

		// backwards compatibility constructor
		public CallToolResult(List<Content> content, Boolean isError) {
			this(content, isError, null);
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

			private Boolean isError = false;

			private Map<String, Object> structuredContent;

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

			public Builder structuredContent(Map<String, Object> structuredContent) {
				Assert.notNull(structuredContent, "structuredContent must not be null");
				this.structuredContent = structuredContent;
				return this;
			}

			public Builder structuredContent(String structuredContent) {
				Assert.hasText(structuredContent, "structuredContent must not be empty");
				try {
					this.structuredContent = OBJECT_MAPPER.readValue(structuredContent, MAP_TYPE_REF);
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
			 * Builds a new {@link CallToolResult} instance.
			 * @return a new CallToolResult instance
			 */
			public CallToolResult build() {
				return new CallToolResult(content, isError, structuredContent);
			}

		}

	}

	// ---------------------------
	// Sampling Interfaces
	// ---------------------------
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelPreferences(// @formatter:off
		@JsonProperty("hints") List<ModelHint> hints,
		@JsonProperty("costPriority") Double costPriority,
		@JsonProperty("speedPriority") Double speedPriority,
		@JsonProperty("intelligencePriority") Double intelligencePriority) {

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
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelHint(@JsonProperty("name") String name) {
		public static ModelHint of(String name) {
			return new ModelHint(name);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SamplingMessage(// @formatter:off
				@JsonProperty("role") Role role,
				@JsonProperty("content") Content content) {
		} // @formatter:on

	// Sampling and Message Creation
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreateMessageRequest(// @formatter:off
				@JsonProperty("messages") List<SamplingMessage> messages,
				@JsonProperty("modelPreferences") ModelPreferences modelPreferences,
				@JsonProperty("systemPrompt") String systemPrompt,
				@JsonProperty("includeContext") ContextInclusionStrategy includeContext,
				@JsonProperty("temperature") Double temperature,
				@JsonProperty("maxTokens") int maxTokens,
				@JsonProperty("stopSequences") List<String> stopSequences,
				@JsonProperty("metadata") Map<String, Object> metadata,
				@JsonProperty("_meta") Map<String, Object> meta) implements Request {

				
				// backwards compatibility constructor
				public CreateMessageRequest(List<SamplingMessage> messages, ModelPreferences modelPreferences,
											String systemPrompt, ContextInclusionStrategy includeContext,
											Double temperature, int maxTokens, List<String> stopSequences,
											Map<String, Object> metadata) {
						this(messages, modelPreferences, systemPrompt, includeContext, temperature, maxTokens,
								stopSequences, metadata, null);
				}

				public enum ContextInclusionStrategy {
						@JsonProperty("none") NONE,
						@JsonProperty("thisServer") THIS_SERVER,
						@JsonProperty("allServers") ALL_SERVERS
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

						public Builder meta(Map<String, Object> meta) {
								this.meta = meta;
								return this;
						}

						public Builder progressToken(String progressToken) {
								if (this.meta == null) {
										this.meta = new HashMap<>();
								}
								this.meta.put("progressToken", progressToken);
								return this;
						}

						public CreateMessageRequest build() {
								return new CreateMessageRequest(messages, modelPreferences, systemPrompt,
										includeContext, temperature, maxTokens, stopSequences, metadata, meta);
						}
				}
		}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreateMessageResult(// @formatter:off
				@JsonProperty("role") Role role,
				@JsonProperty("content") Content content,
				@JsonProperty("model") String model,
				@JsonProperty("stopReason") StopReason stopReason) {

				public enum StopReason {
						@JsonProperty("endTurn") END_TURN("endTurn"),
						@JsonProperty("stopSequence") STOP_SEQUENCE("stopSequence"),
						@JsonProperty("maxTokens") MAX_TOKENS("maxTokens"),
						@JsonProperty("unknown") UNKNOWN("unknown");

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
		}// @formatter:on

	// Elicitation
	/**
	 * Used by the server to send an elicitation to the client.
	 *
	 * @param errorMessage The body of the elicitation message.
	 * @param requestedSchema The elicitation response schema that must be satisfied.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitRequest(// @formatter:off
				@JsonProperty("message") String message,
				@JsonProperty("requestedSchema") Map<String, Object> requestedSchema,
				@JsonProperty("_meta") Map<String, Object> meta) implements Request {

				// backwards compatibility constructor
				public ElicitRequest(String message, Map<String, Object> requestedSchema) {
						this(message, requestedSchema, null);
				}

				public static Builder builder() {
						return new Builder();
				}

				public static class Builder {
						private String message;
						private Map<String, Object> requestedSchema;
						private Map<String, Object> meta;

						public Builder message(String message) {
								this.message = message;
								return this;
						}

						public Builder requestedSchema(Map<String, Object> requestedSchema) {
								this.requestedSchema = requestedSchema;
								return this;
						}

						public Builder meta(Map<String, Object> meta) {
								this.meta = meta;
								return this;
						}

						public Builder progressToken(String progressToken) {
								if (this.meta == null) {
										this.meta = new HashMap<>();
								}
								this.meta.put("progressToken", progressToken);
								return this;
						}

						public ElicitRequest build() {
								return new ElicitRequest(message, requestedSchema, meta);
						}
				}
		}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitResult(// @formatter:off
				@JsonProperty("action") Action action,
				@JsonProperty("content") Map<String, Object> content) {

				public enum Action {
						@JsonProperty("accept") ACCEPT,
						@JsonProperty("decline") DECLINE,
						@JsonProperty("cancel") CANCEL
				}

				public static Builder builder() {
						return new Builder();
				}

				public static class Builder {
						private Action action;
						private Map<String, Object> content;

						public Builder message(Action action) {
								this.action = action;
								return this;
						}

						public Builder content(Map<String, Object> content) {
								this.content = content;
								return this;
						}

						public ElicitResult build() {
								return new ElicitResult(action, content);
						}
				}
		}// @formatter:on

	// ---------------------------
	// Pagination Interfaces
	// ---------------------------
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PaginatedRequest(// @formatter:off
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
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ProgressNotification(// @formatter:off
				@JsonProperty("progressToken") String progressToken,
				@JsonProperty("progress") Double progress,
				@JsonProperty("total") Double total,
				@JsonProperty("message") String message) {
		}// @formatter:on

	/**
	 * The Model Context Protocol (MCP) provides a standardized way for servers to send
	 * resources update message to clients.
	 *
	 * @param uri The updated resource uri.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourcesUpdatedNotification(// @formatter:off
		   @JsonProperty("uri") String uri) {
		}// @formatter:on

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
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LoggingMessageNotification(// @formatter:off
				@JsonProperty("level") LoggingLevel level,
				@JsonProperty("logger") String logger,
				@JsonProperty("data") String data) {

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
		}// @formatter:on

	public enum LoggingLevel {// @formatter:off
				@JsonProperty("debug") DEBUG(0),
				@JsonProperty("info") INFO(1),
				@JsonProperty("notice") NOTICE(2),
				@JsonProperty("warning") WARNING(3),
				@JsonProperty("error") ERROR(4),
				@JsonProperty("critical") CRITICAL(5),
				@JsonProperty("alert") ALERT(6),
				@JsonProperty("emergency") EMERGENCY(7);

				private final int level;

				LoggingLevel(int level) {
						this.level = level;
				}

				public int level() {
						return level;
				}

		} // @formatter:on

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

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptReference(// @formatter:off
				@JsonProperty("type") String type,
				@JsonProperty("name") String name,
				@JsonProperty("title") String title ) implements McpSchema.CompleteReference, BaseMetadata { // @formatter:on

		public PromptReference(String type, String name) {
			this(type, name, null);
		}

		public PromptReference(String name) {
			this("ref/prompt", name, null);
		}

		@Override
		public String identifier() {
			return name();
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceReference(// @formatter:off
				@JsonProperty("type") String type,
				@JsonProperty("uri") String uri) implements McpSchema.CompleteReference {

				public ResourceReference(String uri) {
						this("ref/resource", uri);
				}

				@Override
				public String identifier() {
						return uri();
				}
		}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CompleteRequest(// @formatter:off
		@JsonProperty("ref") McpSchema.CompleteReference ref,
		@JsonProperty("argument") CompleteArgument argument,
		@JsonProperty("_meta") Map<String, Object> meta,
		@JsonProperty("context") CompleteContext context) implements Request {

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument, Map<String, Object> meta) {
			this(ref, argument, meta, null);
		}

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument, CompleteContext context) {
			this(ref, argument, null, context);
		}

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument) {
			this(ref, argument, null, null);
		}
		
		public record CompleteArgument(
			@JsonProperty("name") String name,
			@JsonProperty("value") String value) {
		}

		public record CompleteContext(
			@JsonProperty("arguments") Map<String, String> arguments) {
		}// @formatter:on
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CompleteResult(@JsonProperty("completion") CompleteCompletion completion) { // @formatter:off

				public record CompleteCompletion(
						@JsonProperty("values") List<String> values,
						@JsonProperty("total") Integer total,
						@JsonProperty("hasMore") Boolean hasMore) {
				}// @formatter:on
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
	public sealed interface Content permits TextContent, ImageContent, AudioContent, EmbeddedResource, ResourceLink {

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

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TextContent( // @formatter:off
				@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("text") String text) implements Annotated, Content { // @formatter:on

		public TextContent(String content) {
			this(null, content);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link TextContent#TextContent(Annotations, String)} instead.
		 */
		public TextContent(List<Role> audience, Double priority, String content) {
			this(audience != null || priority != null ? new Annotations(audience, priority) : null, content);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link TextContent#annotations()} instead.
		 */
		public List<Role> audience() {
			return annotations == null ? null : annotations.audience();
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link TextContent#annotations()} instead.
		 */
		public Double priority() {
			return annotations == null ? null : annotations.priority();
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ImageContent( // @formatter:off
				@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("data") String data,
				@JsonProperty("mimeType") String mimeType) implements Annotated, Content { // @formatter:on

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ImageContent#ImageContent(Annotations, String, String)} instead.
		 */
		public ImageContent(List<Role> audience, Double priority, String data, String mimeType) {
			this(audience != null || priority != null ? new Annotations(audience, priority) : null, data, mimeType);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ImageContent#annotations()} instead.
		 */
		public List<Role> audience() {
			return annotations == null ? null : annotations.audience();
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ImageContent#annotations()} instead.
		 */
		public Double priority() {
			return annotations == null ? null : annotations.priority();
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AudioContent( // @formatter:off
				@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("data") String data,
				@JsonProperty("mimeType") String mimeType) implements Annotated, Content { // @formatter:on
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddedResource( // @formatter:off
				@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("resource") ResourceContents resource) implements Annotated, Content { // @formatter:on

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link EmbeddedResource#EmbeddedResource(Annotations, ResourceContents)}
		 * instead.
		 */
		public EmbeddedResource(List<Role> audience, Double priority, ResourceContents resource) {
			this(audience != null || priority != null ? new Annotations(audience, priority) : null, resource);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link EmbeddedResource#annotations()} instead.
		 */
		public List<Role> audience() {
			return annotations == null ? null : annotations.audience();
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link EmbeddedResource#annotations()} instead.
		 */
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
				@JsonProperty("annotations") Annotations annotations) implements Annotated, Content, ResourceContent { // @formatter:on

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ResourceLink#ResourceLink(String, String, String, String, String, Long, Annotations)}
		 * instead.
		 */
		@Deprecated
		public ResourceLink(String name, String uri, String description, String mimeType, Long size,
				Annotations annotations) {
			this(name, null, uri, description, mimeType, size, annotations);
		}

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

			public ResourceLink build() {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");

				return new ResourceLink(name, title, uri, description, mimeType, size, annotations);
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
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Root( // @formatter:off
				@JsonProperty("uri") String uri,
				@JsonProperty("name") String name) {
		} // @formatter:on

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
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListRootsResult( // @formatter:off
				@JsonProperty("roots") List<Root> roots,
				@JsonProperty("nextCursor") String nextCursor) {

				public ListRootsResult(List<Root> roots) {
						this(roots, null);
				}
		} // @formatter:on

}
