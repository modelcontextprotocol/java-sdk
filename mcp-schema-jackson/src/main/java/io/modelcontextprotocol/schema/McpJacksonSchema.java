/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.schema;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;

/**
 * @author Aliaksei Darafeyeu
 */
public final class McpJacksonSchema {

	public static final Map<Class<?>, Class<?>> MIXINS = Map.ofEntries(
			Map.entry(McpSchema.JSONRPCRequest.class, JSONRPCRequestMixin.class),
			Map.entry(McpSchema.JSONRPCNotification.class, JSONRPCNotificationMixin.class),
			Map.entry(McpSchema.JSONRPCResponse.class, JSONRPCResponseMixin.class),
			Map.entry(McpSchema.JSONRPCResponse.JSONRPCError.class, JSONRPCErrorMixin.class),
			Map.entry(McpSchema.InitializeRequest.class, InitializeRequestMixin.class),
			Map.entry(McpSchema.InitializeResult.class, InitializeResultMixin.class),
			Map.entry(McpSchema.ClientCapabilities.class, ClientCapabilitiesMixin.class),
			Map.entry(McpSchema.ClientCapabilities.RootCapabilities.class, RootCapabilitiesMixin.class),
			Map.entry(McpSchema.ClientCapabilities.Sampling.class, SamplingMixin.class),
			Map.entry(McpSchema.ServerCapabilities.class, ServerCapabilitiesMixin.class),
			Map.entry(McpSchema.ServerCapabilities.CompletionCapabilities.class, CompletionCapabilitiesMixin.class),
			Map.entry(McpSchema.ServerCapabilities.LoggingCapabilities.class, LoggingCapabilitiesMixin.class),
			Map.entry(McpSchema.ServerCapabilities.PromptCapabilities.class, PromptCapabilitiesMixin.class),
			Map.entry(McpSchema.ServerCapabilities.ToolCapabilities.class, ToolCapabilitiesMixin.class),
			Map.entry(McpSchema.Implementation.class, ImplementationMixin.class),
			Map.entry(McpSchema.Annotations.class, AnnotationsMixin.class),
			Map.entry(McpSchema.Resource.class, ResourceMixin.class),
			Map.entry(McpSchema.ResourceTemplate.class, ResourceTemplateMixin.class),
			Map.entry(McpSchema.ListResourcesResult.class, ListResourcesResultMixin.class),
			Map.entry(McpSchema.ListResourceTemplatesResult.class, ListResourceTemplatesResultMixin.class),
			Map.entry(McpSchema.ReadResourceRequest.class, ReadResourceRequestMixin.class),
			Map.entry(McpSchema.ReadResourceResult.class, ReadResourceResultMixin.class),
			Map.entry(McpSchema.SubscribeRequest.class, SubscribeRequestMixin.class),
			Map.entry(McpSchema.UnsubscribeRequest.class, UnsubscribeRequestMixin.class),
			Map.entry(McpSchema.ResourceContents.class, ResourceContentsMixin.class),
			Map.entry(McpSchema.TextResourceContents.class, TextResourceContentsMixin.class),
			Map.entry(McpSchema.BlobResourceContents.class, BlobResourceContentsMixin.class),
			Map.entry(McpSchema.Prompt.class, PromptMixin.class),
			Map.entry(McpSchema.PromptArgument.class, PromptArgumentMixin.class),
			Map.entry(McpSchema.PromptMessage.class, PromptMessageMixin.class),
			Map.entry(McpSchema.GetPromptRequest.class, GetPromptRequestMixin.class),
			Map.entry(McpSchema.ListPromptsResult.class, ListPromptsResultMixin.class),
			Map.entry(McpSchema.GetPromptResult.class, GetPromptResultMixin.class),
			Map.entry(McpSchema.ListToolsResult.class, ListToolsResultMixin.class),
			Map.entry(McpSchema.JsonSchema.class, JsonSchemaMixin.class),
			Map.entry(McpSchema.Tool.class, ToolMixin.class),
			Map.entry(McpSchema.CallToolRequest.class, CallToolRequestMixin.class),
			Map.entry(McpSchema.CallToolResult.class, CallToolResultMixin.class),
			Map.entry(McpSchema.ModelPreferences.class, ModelPreferencesMixin.class),
			Map.entry(McpSchema.ModelHint.class, ModelHintMixin.class),
			Map.entry(McpSchema.SamplingMessage.class, SamplingMessageMixin.class),
			Map.entry(McpSchema.CreateMessageRequest.class, CreateMessageRequestMixin.class),
			Map.entry(McpSchema.CreateMessageResult.class, CreateMessageResultMixin.class),
			Map.entry(McpSchema.PaginatedRequest.class, PaginatedRequestMixin.class),
			Map.entry(McpSchema.PaginatedResult.class, PaginatedResultMixin.class),
			Map.entry(McpSchema.ProgressNotification.class, ProgressNotificationMixin.class),
			Map.entry(McpSchema.LoggingMessageNotification.class, LoggingMessageNotificationMixin.class),
			Map.entry(McpSchema.SetLevelRequest.class, SetLevelRequestMixin.class),
			Map.entry(McpSchema.PromptReference.class, PromptReferenceMixin.class),
			Map.entry(McpSchema.ResourceReference.class, ResourceReferenceMixin.class),
			Map.entry(McpSchema.CompleteRequest.class, CompleteRequestMixin.class),
			Map.entry(McpSchema.CompleteRequest.CompleteArgument.class, CompleteArgumentMixin.class),
			Map.entry(McpSchema.CompleteResult.class, CompleteResultMixin.class),
			Map.entry(McpSchema.CompleteResult.CompleteCompletion.class, CompleteCompletionMixin.class),
			Map.entry(McpSchema.Content.class, ContentMixin.class),
			Map.entry(McpSchema.TextContent.class, TextContentMixin.class),
			Map.entry(McpSchema.ImageContent.class, ImageContentMixin.class),
			Map.entry(McpSchema.EmbeddedResource.class, EmbeddedResourceMixin.class),
			Map.entry(McpSchema.Root.class, RootMixin.class),
			Map.entry(McpSchema.ListRootsResult.class, ListRootsResultMixin.class));

	private McpJacksonSchema() {
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface JSONRPCRequestMixin {

	// @formatter:off
			@JsonProperty("jsonrpc") String jsonrpc();
			@JsonProperty("method") String method();
			@JsonProperty("id") Object id();
			@JsonProperty("params") Object params();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface JSONRPCNotificationMixin {

	// @formatter:off
			@JsonProperty("jsonrpc") String jsonrpc();
			@JsonProperty("method") String method();
			@JsonProperty("params") Object params();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface JSONRPCResponseMixin {

	// @formatter:off
			@JsonProperty("jsonrpc") String jsonrpc();
			@JsonProperty("id") Object id();
			@JsonProperty("result") Object result();
			@JsonProperty("error") McpSchema.JSONRPCResponse.JSONRPCError error();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface JSONRPCErrorMixin {// @formatter:off
			@JsonProperty("code") int code();
			@JsonProperty("message") String message();
			@JsonProperty("data") Object data();
	}// @formatter:on

	// ---------------------------
	// Initialization
	// ---------------------------
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface InitializeRequestMixin {

	// @formatter:off
		@JsonProperty("protocolVersion") String protocolVersion();
		@JsonProperty("capabilities") McpSchema.ClientCapabilities capabilities();
		@JsonProperty("clientInfo") McpSchema.Implementation clientInfo();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface InitializeResultMixin {

	// @formatter:off
		@JsonProperty("protocolVersion") String protocolVersion();
		@JsonProperty("capabilities") McpSchema.ServerCapabilities capabilities();
		@JsonProperty("serverInfo") McpSchema.Implementation serverInfo();
		@JsonProperty("instructions") String instructions();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ClientCapabilitiesMixin {

	// @formatter:off
		@JsonProperty("experimental") Map<String, Object> experimental();
		@JsonProperty("roots") McpSchema.ClientCapabilities.RootCapabilities roots();
		@JsonProperty("sampling") McpSchema.ClientCapabilities.Sampling sampling();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface RootCapabilitiesMixin {

	// @formatter:off
		@JsonProperty("listChanged") Boolean listChanged();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	public interface SamplingMixin {

	// @formatter:off
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ServerCapabilitiesMixin {

	// @formatter:off
	    @JsonProperty("completions") McpSchema.ServerCapabilities.CompletionCapabilities completions();
		@JsonProperty("experimental") Map<String, Object> experimental();
		@JsonProperty("logging") McpSchema.ServerCapabilities.LoggingCapabilities logging();
		@JsonProperty("prompts") McpSchema.ServerCapabilities.PromptCapabilities prompts();
		@JsonProperty("resources") McpSchema.ServerCapabilities.ResourceCapabilities resources();
		@JsonProperty("tools") McpSchema.ServerCapabilities.ToolCapabilities tools();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	public interface CompletionCapabilitiesMixin {

	// @formatter:off
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	public interface LoggingCapabilitiesMixin {

	// @formatter:off
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	public interface PromptCapabilitiesMixin {

	// @formatter:off
		@JsonProperty("listChanged") Boolean listChanged();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	public interface ResourceCapabilitiesMixin {

	// @formatter:off
		@JsonProperty("subscribe") Boolean subscribe();
		@JsonProperty("listChanged") Boolean listChanged();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	public interface ToolCapabilitiesMixin {

	// @formatter:off
		@JsonProperty("listChanged") Boolean listChanged();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ImplementationMixin {

	// @formatter:off
		@JsonProperty("name") String name();
		@JsonProperty("version") String version();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface AnnotationsMixin {

	// @formatter:off
		@JsonProperty("audience") List<McpSchema.Role> audience();
		@JsonProperty("priority") Double priority();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ResourceMixin {

	// @formatter:off
		@JsonProperty("uri") String uri();
		@JsonProperty("name") String name();
		@JsonProperty("description") String description();
		@JsonProperty("mimeType") String mimeType();
		@JsonProperty("annotations") McpSchema.Annotations annotations();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ResourceTemplateMixin {

	// @formatter:off
		@JsonProperty("uriTemplate") String uriTemplate();
		@JsonProperty("name") String name();
		@JsonProperty("description") String description();
		@JsonProperty("mimeType") String mimeType();
		@JsonProperty("annotations") McpSchema.Annotations annotations();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ListResourcesResultMixin {

	// @formatter:off
		@JsonProperty("resources") List<McpSchema.Resource> resources();
		@JsonProperty("nextCursor") String nextCursor();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ListResourceTemplatesResultMixin {

	// @formatter:off
		@JsonProperty("resourceTemplates") List<McpSchema.ResourceTemplate> resourceTemplates();
		@JsonProperty("nextCursor") String nextCursor();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ReadResourceRequestMixin {

	// @formatter:off
		@JsonProperty("uri") String uri();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ReadResourceResultMixin {

	// @formatter:off
		@JsonProperty("contents") List<McpSchema.ResourceContents> contents();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface SubscribeRequestMixin {

	// @formatter:off
		@JsonProperty("uri") String uri();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface UnsubscribeRequestMixin {

	// @formatter:off
		@JsonProperty("uri") String uri();
	} // @formatter:on

	/**
	 * The contents of a specific resource or sub-resource.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, include = As.PROPERTY)
	@JsonSubTypes({ @JsonSubTypes.Type(value = McpSchema.TextResourceContents.class, name = "text"),
			@JsonSubTypes.Type(value = McpSchema.BlobResourceContents.class, name = "blob") })
	public interface ResourceContentsMixin {

	// @formatter:off
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface TextResourceContentsMixin{ // @formatter:off
		@JsonProperty("uri") String uri();
		@JsonProperty("mimeType") String mimeType();
		@JsonProperty("text") String text();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface BlobResourceContentsMixin {

	// @formatter:off
		@JsonProperty("uri") String uri();
		@JsonProperty("mimeType") String mimeType();
		@JsonProperty("blob") String blob();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface PromptMixin {

	// @formatter:off
		@JsonProperty("name") String name();
		@JsonProperty("description") String description();
		@JsonProperty("arguments") List<McpSchema.PromptArgument> arguments();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface PromptArgumentMixin {

	// @formatter:off
		@JsonProperty("name") String name();
		@JsonProperty("description") String description();
		@JsonProperty("required") Boolean required();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface PromptMessageMixin {

	// @formatter:off
		@JsonProperty("role") McpSchema.Role role();
		@JsonProperty("content") McpSchema.Content content();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ListPromptsResultMixin {

	// @formatter:off
		@JsonProperty("prompts") List<McpSchema.Prompt> prompts();
		@JsonProperty("nextCursor") String nextCursor();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface GetPromptRequestMixin {// @formatter:off
		@JsonProperty("name") String name();
		@JsonProperty("arguments") Map<String, Object> arguments();
	}// @formatter:off

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface GetPromptResultMixin{ // @formatter:off
		@JsonProperty("description") String description();
		@JsonProperty("messages") List<McpSchema.PromptMessage> messages();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ListToolsResultMixin {

	// @formatter:off
		@JsonProperty("tools") List<McpSchema.Tool> tools();
		@JsonProperty("nextCursor") String nextCursor();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface JsonSchemaMixin {

	// @formatter:off
		@JsonProperty("type") String type();
		@JsonProperty("properties") Map<String, Object> properties();
		@JsonProperty("required") List<String> required();
		@JsonProperty("additionalProperties") Boolean additionalProperties();
		@JsonProperty("$defs")  Map<String, Object> defs();
		@JsonProperty("definitions") Map<String, Object> definitions();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ToolMixin {

	// @formatter:off
		@JsonProperty("name") String name();
		@JsonProperty("description") String description();
		@JsonProperty("inputSchema") McpSchema.JsonSchema inputSchema();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface CallToolRequestMixin {// @formatter:off
		@JsonProperty("name") String name();
		@JsonProperty("arguments") Map<String, Object> arguments();
	}// @formatter:off

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface CallToolResultMixin{ // @formatter:off
		@JsonProperty("content") List<McpSchema.Content> content();
		@JsonProperty("isError") Boolean isError();
	} // @formatter:on

	// ---------------------------
	// Sampling Interfaces
	// ---------------------------
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ModelPreferencesMixin {// @formatter:off
	@JsonProperty("hints") List<McpSchema.ModelHint> hints();
	@JsonProperty("costPriority") Double costPriority();
	@JsonProperty("speedPriority") Double speedPriority();
	@JsonProperty("intelligencePriority") Double intelligencePriority();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ModelHintMixin {// @formatter:off
		@JsonProperty("name") String name();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface SamplingMessageMixin {// @formatter:off
		@JsonProperty("role") McpSchema.Role role();
		@JsonProperty("content") McpSchema.Content content();
	} // @formatter:on

	// Sampling and Message Creation
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface CreateMessageRequestMixin {// @formatter:off
		@JsonProperty("messages") List<McpSchema.SamplingMessage> messages();
		@JsonProperty("modelPreferences") McpSchema.ModelPreferences modelPreferences();
		@JsonProperty("systemPrompt") String systemPrompt();
		@JsonProperty("includeContext") McpSchema.CreateMessageRequest.ContextInclusionStrategy includeContext();
		@JsonProperty("temperature") Double temperature();
		@JsonProperty("maxTokens") int maxTokens();
		@JsonProperty("stopSequences") List<String> stopSequences(); 			
		@JsonProperty("metadata") Map<String, Object> metadata();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface CreateMessageResultMixin {// @formatter:off
		@JsonProperty("role") McpSchema.Role role();
		@JsonProperty("content") McpSchema.Content content();
		@JsonProperty("model") String model();
		@JsonProperty("stopReason") McpSchema.CreateMessageResult.StopReason stopReason();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface PaginatedRequestMixin {// @formatter:off
		 @JsonProperty("cursor") String cursor();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface PaginatedResultMixin {// @formatter:off
			@JsonProperty("nextCursor") String nextCursor();
	}// @formatter:on

	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ProgressNotificationMixin {// @formatter:off
		@JsonProperty("progressToken") String progressToken();
		@JsonProperty("progress") double progress();
		@JsonProperty("total") Double total();
	}// @formatter:on

	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface LoggingMessageNotificationMixin {// @formatter:off
		@JsonProperty("level") McpSchema.LoggingLevel level();
		@JsonProperty("logger") String logger();
		@JsonProperty("data") String data();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface SetLevelRequestMixin {

	// @formatter:off
		@JsonProperty("level") McpSchema.LoggingLevel level();
	}


	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface PromptReferenceMixin{// @formatter:off
		@JsonProperty("type") String type();
		@JsonProperty("name") String name();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ResourceReferenceMixin {// @formatter:off
		@JsonProperty("type") String type();
		@JsonProperty("uri") String uri();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface CompleteRequestMixin {// @formatter:off
		@JsonProperty("ref") McpSchema.CompleteReference ref();
		@JsonProperty("argument") McpSchema.CompleteRequest.CompleteArgument argument();
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface CompleteArgumentMixin{
		@JsonProperty("name") String name();
		@JsonProperty("value") String value();
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface CompleteResultMixin {

	// @formatter:off
		@JsonProperty("completion") McpSchema.CompleteResult.CompleteCompletion completion();
	} // @formatter:on

	public interface CompleteCompletionMixin {// @formatter:off
		@JsonProperty("values") List<String> values();
		@JsonProperty("total") Integer total();
		@JsonProperty("hasMore") Boolean hasMore();
	}// @formatter:on

	// ---------------------------
	// Content Types
	// ---------------------------
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = McpSchema.TextContent.class, name = "text"),
			@JsonSubTypes.Type(value = McpSchema.ImageContent.class, name = "image"),
			@JsonSubTypes.Type(value = McpSchema.EmbeddedResource.class, name = "resource") })
	public interface ContentMixin {

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface TextContentMixin {

	// @formatter:off
		@JsonProperty("audience") List<McpSchema.Role> audience();
		@JsonProperty("priority") Double priority();
		@JsonProperty("text") String text();
	}  // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ImageContentMixin {

	// @formatter:off
		@JsonProperty("audience") List<McpSchema.Role> audience();
		@JsonProperty("priority") Double priority();
		@JsonProperty("data") String data();
		@JsonProperty("mimeType") String mimeType();
	}  // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface EmbeddedResourceMixin {

	// @formatter:off
		@JsonProperty("audience") List<McpSchema.Role> audience();
		@JsonProperty("priority") Double priority();
		@JsonProperty("resource") McpSchema.ResourceContents resource();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface RootMixin {

	// @formatter:off
		@JsonProperty("uri") String uri();
		@JsonProperty("name") String name();
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public interface ListRootsResultMixin {

	// @formatter:off
		@JsonProperty("roots") List<McpSchema.Root> roots();
	} // @formatter:on

}
