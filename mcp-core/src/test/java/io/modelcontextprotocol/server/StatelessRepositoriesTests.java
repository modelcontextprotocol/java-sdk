/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for stateless primitive repositories.
 *
 * @author Taewoong Kim
 */
class StatelessRepositoriesTests {

	private static final String USER_KEY = "X-User";

	private static final JsonSchemaValidator VALID_SCHEMA_VALIDATOR = (schema, structuredContent) -> ValidationResponse
		.asValid(null);

	private static final McpJsonMapper JSON_MAPPER = new PassThroughMcpJsonMapper();

	@Test
	void statelessRepositoriesResolvePrimitivesFromTransportContext() {
		var transport = new MockStatelessServerTransport();
		var repository = new ContextAwareRepository();

		McpServer.sync(transport)
			.jsonMapper(JSON_MAPPER)
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(repository)
			.resourcesRepository(repository)
			.promptsRepository(repository)
			.completionsRepository(repository)
			.build();

		McpSchema.ListToolsResult tools = result(transport, "alice", McpSchema.METHOD_TOOLS_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(tools.tools()).extracting(McpSchema.Tool::name).containsExactly("alice-tool");

		McpSchema.CallToolResult toolResult = result(transport, "alice", McpSchema.METHOD_TOOLS_CALL,
				McpSchema.CallToolRequest.builder("alice-tool").build());
		assertThat(text(toolResult.content().get(0))).isEqualTo("tool:alice");

		McpSchema.ListResourcesResult resources = result(transport, "alice", McpSchema.METHOD_RESOURCES_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(resources.resources()).extracting(McpSchema.Resource::uri).containsExactly("test://resources/alice");

		McpSchema.ReadResourceResult resourceResult = result(transport, "alice", McpSchema.METHOD_RESOURCES_READ,
				McpSchema.ReadResourceRequest.builder("test://resources/alice").build());
		assertThat(((McpSchema.TextResourceContents) resourceResult.contents().get(0)).text())
			.isEqualTo("resource:alice");

		McpSchema.ListResourceTemplatesResult templates = result(transport, "alice",
				McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, new McpSchema.PaginatedRequest());
		assertThat(templates.resourceTemplates()).extracting(McpSchema.ResourceTemplate::uriTemplate)
			.containsExactly("test://resource-templates/alice/{name}");

		McpSchema.ReadResourceResult templateResult = result(transport, "alice", McpSchema.METHOD_RESOURCES_READ,
				McpSchema.ReadResourceRequest.builder("test://resource-templates/alice/guide").build());
		assertThat(((McpSchema.TextResourceContents) templateResult.contents().get(0)).text())
			.isEqualTo("resource-template:alice");

		McpSchema.ListPromptsResult prompts = result(transport, "alice", McpSchema.METHOD_PROMPT_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(prompts.prompts()).extracting(McpSchema.Prompt::name).containsExactly("alice-prompt");

		McpSchema.GetPromptResult promptResult = result(transport, "alice", McpSchema.METHOD_PROMPT_GET,
				McpSchema.GetPromptRequest.builder("alice-prompt").build());
		assertThat(text(promptResult.messages().get(0).content())).isEqualTo("prompt:alice");

		McpSchema.CompleteResult completeResult = result(transport, "alice", McpSchema.METHOD_COMPLETION_COMPLETE,
				McpSchema.CompleteRequest
					.builder(new McpSchema.PromptReference("alice-prompt"),
							new McpSchema.CompleteRequest.CompleteArgument("name", "a"))
					.build());
		assertThat(completeResult.completion().values()).containsExactly("completion:alice");

		McpSchema.CompleteResult resourceCompleteResult = result(transport, "alice",
				McpSchema.METHOD_COMPLETION_COMPLETE,
				McpSchema.CompleteRequest
					.builder(new McpSchema.ResourceReference("test://resource-templates/alice/{name}"),
							new McpSchema.CompleteRequest.CompleteArgument("name", "g"))
					.build());
		assertThat(resourceCompleteResult.completion().values()).containsExactly("completion:alice");

		McpSchema.ListToolsResult bobTools = result(transport, "bob", McpSchema.METHOD_TOOLS_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(bobTools.tools()).extracting(McpSchema.Tool::name).containsExactly("bob-tool");

		McpSchema.CallToolResult bobToolResult = result(transport, "bob", McpSchema.METHOD_TOOLS_CALL,
				McpSchema.CallToolRequest.builder("bob-tool").build());
		assertThat(text(bobToolResult.content().get(0))).isEqualTo("tool:bob");

		McpSchema.JSONRPCResponse hiddenResource = response(transport, "bob", McpSchema.METHOD_RESOURCES_READ,
				McpSchema.ReadResourceRequest.builder("test://resources/alice").build());
		assertThat(hiddenResource.error()).isNotNull();
		assertThat(hiddenResource.error().code()).isEqualTo(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND);
	}

	@Test
	void statelessRepositoriesReceivePaginatedListRequests() {
		var transport = new MockStatelessServerTransport();
		var repository = new ContextAwareRepository();

		McpServer.sync(transport)
			.jsonMapper(JSON_MAPPER)
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(repository)
			.resourcesRepository(repository)
			.promptsRepository(repository)
			.build();

		McpSchema.ListToolsResult tools = result(transport, "alice", McpSchema.METHOD_TOOLS_LIST,
				new McpSchema.PaginatedRequest("tools-cursor"));
		assertThat(tools.nextCursor()).isEqualTo("tools-cursor");

		McpSchema.ListResourcesResult resources = result(transport, "alice", McpSchema.METHOD_RESOURCES_LIST,
				new McpSchema.PaginatedRequest("resources-cursor"));
		assertThat(resources.nextCursor()).isEqualTo("resources-cursor");

		McpSchema.ListResourceTemplatesResult templates = result(transport, "alice",
				McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, new McpSchema.PaginatedRequest("templates-cursor"));
		assertThat(templates.nextCursor()).isEqualTo("templates-cursor");

		McpSchema.ListPromptsResult prompts = result(transport, "alice", McpSchema.METHOD_PROMPT_LIST,
				new McpSchema.PaginatedRequest("prompts-cursor"));
		assertThat(prompts.nextCursor()).isEqualTo("prompts-cursor");
	}

	@Test
	void statelessRepositorySupportsRuntimeToolMutation() {
		var transport = new MockStatelessServerTransport();
		var repository = new MutableRepository();

		McpStatelessSyncServer server = McpServer.sync(transport)
			.jsonMapper(JSON_MAPPER)
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(repository)
			.build();

		server.addTool(toolSpec("runtime-tool", "runtime"));

		McpSchema.ListToolsResult tools = result(transport, "alice", McpSchema.METHOD_TOOLS_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(tools.tools()).extracting(McpSchema.Tool::name).containsExactly("runtime-tool");

		server.removeTool("runtime-tool");

		McpSchema.ListToolsResult emptyTools = result(transport, "alice", McpSchema.METHOD_TOOLS_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(emptyTools.tools()).isEmpty();
	}

	@Test
	void statelessRepositoriesSupportRuntimeResourceAndPromptMutation() {
		var transport = new MockStatelessServerTransport();
		var repository = new MutableRepository();

		McpStatelessSyncServer server = McpServer.sync(transport)
			.jsonMapper(JSON_MAPPER)
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.resourcesRepository(repository)
			.promptsRepository(repository)
			.build();

		server.addResource(resourceSpec("test://resources/runtime", "runtime-resource", "resource-runtime"));
		server.addResourceTemplate(resourceTemplateSpec("test://resource-templates/runtime/{name}",
				"runtime-resource-template", "template-runtime"));
		server.addPrompt(promptSpec("runtime-prompt", "prompt-runtime"));

		McpSchema.ListResourcesResult resources = result(transport, "alice", McpSchema.METHOD_RESOURCES_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(resources.resources()).extracting(McpSchema.Resource::uri)
			.containsExactly("test://resources/runtime");
		McpSchema.ListResourceTemplatesResult templates = result(transport, "alice",
				McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, new McpSchema.PaginatedRequest());
		assertThat(templates.resourceTemplates()).extracting(McpSchema.ResourceTemplate::uriTemplate)
			.containsExactly("test://resource-templates/runtime/{name}");
		McpSchema.ListPromptsResult prompts = result(transport, "alice", McpSchema.METHOD_PROMPT_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(prompts.prompts()).extracting(McpSchema.Prompt::name).containsExactly("runtime-prompt");

		McpSchema.ReadResourceResult resourceResult = result(transport, "alice", McpSchema.METHOD_RESOURCES_READ,
				McpSchema.ReadResourceRequest.builder("test://resources/runtime").build());
		assertThat(((McpSchema.TextResourceContents) resourceResult.contents().get(0)).text())
			.isEqualTo("resource-runtime");
		McpSchema.ReadResourceResult templateResult = result(transport, "alice", McpSchema.METHOD_RESOURCES_READ,
				McpSchema.ReadResourceRequest.builder("test://resource-templates/runtime/guide").build());
		assertThat(((McpSchema.TextResourceContents) templateResult.contents().get(0)).text())
			.isEqualTo("template-runtime");
		McpSchema.GetPromptResult promptResult = result(transport, "alice", McpSchema.METHOD_PROMPT_GET,
				McpSchema.GetPromptRequest.builder("runtime-prompt").build());
		assertThat(text(promptResult.messages().get(0).content())).isEqualTo("prompt-runtime");

		server.removeResource("test://resources/runtime");
		server.removeResourceTemplate("test://resource-templates/runtime/{name}");
		server.removePrompt("runtime-prompt");

		McpSchema.ListResourcesResult emptyResources = result(transport, "alice", McpSchema.METHOD_RESOURCES_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(emptyResources.resources()).isEmpty();
		McpSchema.ListResourceTemplatesResult emptyTemplates = result(transport, "alice",
				McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, new McpSchema.PaginatedRequest());
		assertThat(emptyTemplates.resourceTemplates()).isEmpty();
		McpSchema.ListPromptsResult emptyPrompts = result(transport, "alice", McpSchema.METHOD_PROMPT_LIST,
				new McpSchema.PaginatedRequest());
		assertThat(emptyPrompts.prompts()).isEmpty();
	}

	@Test
	void statelessRuntimeMutationRequiresRepositoryMutationSupport() {
		var transport = new MockStatelessServerTransport();
		var repository = new ContextAwareRepository();

		McpStatelessSyncServer server = McpServer.sync(transport)
			.jsonMapper(JSON_MAPPER)
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(repository)
			.resourcesRepository(repository)
			.promptsRepository(repository)
			.build();

		assertThatThrownBy(() -> server.addTool(toolSpec("runtime-tool", "runtime")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support adding tools");
		assertThatThrownBy(() -> server.removeTool("runtime-tool")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support removing tools");

		assertThatThrownBy(() -> server
			.addResource(resourceSpec("test://resources/runtime", "runtime-resource", "resource-runtime")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support adding resources");
		assertThatThrownBy(() -> server.removeResource("test://resources/runtime"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support removing resources");

		assertThatThrownBy(
				() -> server.addResourceTemplate(resourceTemplateSpec("test://resource-templates/runtime/{name}",
						"runtime-resource-template", "template-runtime")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support adding resource templates");
		assertThatThrownBy(() -> server.removeResourceTemplate("test://resource-templates/runtime/{name}"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support removing resource templates");

		assertThatThrownBy(() -> server.addPrompt(promptSpec("runtime-prompt", "prompt-runtime")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support adding prompts");
		assertThatThrownBy(() -> server.removePrompt("runtime-prompt"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support removing prompts");
	}

	@Test
	void statelessBuilderRejectsRepositoriesMixedWithStaticRegistrations() {
		var tool = tool("static-tool");
		var prompt = McpSchema.Prompt.builder("static-prompt").build();
		var resource = McpSchema.Resource.builder("test://resources/static", "static-resource").build();
		var reference = new McpSchema.PromptReference("static-prompt");

		assertThatThrownBy(() -> McpServer.sync(new MockStatelessServerTransport())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(new MutableRepository())
			.toolCall(tool, (context, request) -> McpSchema.CallToolResult.builder().build()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("toolsRepository");
		assertThatThrownBy(() -> McpServer.sync(new MockStatelessServerTransport())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolCall(tool, (context, request) -> McpSchema.CallToolResult.builder().build())
			.toolsRepository(new MutableRepository())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("toolsRepository");

		assertThatThrownBy(() -> McpServer.sync(new MockStatelessServerTransport())
			.resourcesRepository(new MutableRepository())
			.resources(new McpStatelessServerFeatures.SyncResourceSpecification(resource,
					(context, request) -> McpSchema.ReadResourceResult.builder(List.of()).build())))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("resourcesRepository");
		assertThatThrownBy(() -> McpServer.sync(new MockStatelessServerTransport())
			.resources(new McpStatelessServerFeatures.SyncResourceSpecification(resource,
					(context, request) -> McpSchema.ReadResourceResult.builder(List.of()).build()))
			.resourcesRepository(new MutableRepository())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("resourcesRepository");

		assertThatThrownBy(() -> McpServer.sync(new MockStatelessServerTransport())
			.promptsRepository(new MutableRepository())
			.prompts(new McpStatelessServerFeatures.SyncPromptSpecification(prompt,
					(context, request) -> McpSchema.GetPromptResult.builder(List.of()).build())))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("promptsRepository");
		assertThatThrownBy(() -> McpServer.sync(new MockStatelessServerTransport())
			.prompts(new McpStatelessServerFeatures.SyncPromptSpecification(prompt,
					(context, request) -> McpSchema.GetPromptResult.builder(List.of()).build()))
			.promptsRepository(new MutableRepository())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("promptsRepository");

		assertThatThrownBy(() -> McpServer.sync(new MockStatelessServerTransport())
			.completionsRepository(new MutableRepository())
			.completions(new McpStatelessServerFeatures.SyncCompletionSpecification(reference,
					(context, request) -> new McpSchema.CompleteResult(
							new McpSchema.CompleteResult.CompleteCompletion(List.of())))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("completionsRepository");
		assertThatThrownBy(() -> McpServer.sync(new MockStatelessServerTransport())
			.completions(new McpStatelessServerFeatures.SyncCompletionSpecification(reference,
					(context, request) -> new McpSchema.CompleteResult(
							new McpSchema.CompleteResult.CompleteCompletion(List.of()))))
			.completionsRepository(new MutableRepository())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("completionsRepository");
	}

	@SuppressWarnings("unchecked")
	private static <T> T result(MockStatelessServerTransport transport, String user, String method, Object params) {
		McpSchema.JSONRPCResponse response = response(transport, user, method, params);
		assertThat(response).isNotNull();
		assertThat(response.error()).isNull();
		return (T) response.result();
	}

	private static McpSchema.JSONRPCResponse response(MockStatelessServerTransport transport, String user,
			String method, Object params) {
		return transport.handler
			.handleRequest(McpTransportContext.create(Map.of(USER_KEY, user)),
					new McpSchema.JSONRPCRequest(method, method + "-id", params))
			.block();
	}

	private static McpStatelessServerFeatures.SyncToolSpecification toolSpec(String name, String responseText) {
		return new McpStatelessServerFeatures.SyncToolSpecification(tool(name),
				(context, request) -> McpSchema.CallToolResult.builder().addTextContent(responseText).build());
	}

	private static McpStatelessServerFeatures.SyncResourceSpecification resourceSpec(String uri, String name,
			String responseText) {
		var resource = McpSchema.Resource.builder(uri, name).build();
		return new McpStatelessServerFeatures.SyncResourceSpecification(resource,
				(context, request) -> McpSchema.ReadResourceResult
					.builder(List.of(McpSchema.TextResourceContents.builder(request.uri(), responseText).build()))
					.build());
	}

	private static McpStatelessServerFeatures.SyncResourceTemplateSpecification resourceTemplateSpec(String uriTemplate,
			String name, String responseText) {
		var resourceTemplate = McpSchema.ResourceTemplate.builder(uriTemplate, name).build();
		return new McpStatelessServerFeatures.SyncResourceTemplateSpecification(resourceTemplate,
				(context, request) -> McpSchema.ReadResourceResult
					.builder(List.of(McpSchema.TextResourceContents.builder(request.uri(), responseText).build()))
					.build());
	}

	private static McpStatelessServerFeatures.SyncPromptSpecification promptSpec(String name, String responseText) {
		var prompt = McpSchema.Prompt.builder(name).build();
		return new McpStatelessServerFeatures.SyncPromptSpecification(prompt,
				(context,
						request) -> McpSchema.GetPromptResult.builder(List.of(McpSchema.PromptMessage
							.builder(McpSchema.Role.USER, McpSchema.TextContent.builder(responseText).build())
							.build())).build());
	}

	private static McpSchema.Tool tool(String name) {
		return McpSchema.Tool.builder(name, Map.of("type", "object")).build();
	}

	private static String text(McpSchema.Content content) {
		return ((McpSchema.TextContent) content).text();
	}

	private static String user(McpTransportContext transportContext) {
		Object user = transportContext.get(USER_KEY);
		return (user != null) ? user.toString() : "anonymous";
	}

	private static final class PassThroughMcpJsonMapper implements McpJsonMapper {

		private final GsonMcpJsonMapper delegate = new GsonMcpJsonMapper();

		@Override
		public <T> T readValue(String content, Class<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> T readValue(byte[] content, Class<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> T readValue(String content, TypeRef<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> T convertValue(Object fromValue, Class<T> type) {
			if (type.isInstance(fromValue)) {
				return type.cast(fromValue);
			}
			return this.delegate.convertValue(fromValue, type);
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T convertValue(Object fromValue, TypeRef<T> type) {
			Type targetType = type.getType();
			if (targetType instanceof Class<?> targetClass && targetClass.isInstance(fromValue)) {
				return (T) fromValue;
			}
			return this.delegate.convertValue(fromValue, type);
		}

		@Override
		public String writeValueAsString(Object value) throws IOException {
			return this.delegate.writeValueAsString(value);
		}

		@Override
		public byte[] writeValueAsBytes(Object value) throws IOException {
			return this.delegate.writeValueAsBytes(value);
		}

	}

	private static final class MockStatelessServerTransport implements McpStatelessServerTransport {

		private McpStatelessServerHandler handler;

		@Override
		public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
			this.handler = mcpHandler;
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

	}

	private static final class ContextAwareRepository
			implements ToolsRepository, ResourcesRepository, PromptsRepository, CompletionsRepository {

		@Override
		public McpSchema.ListToolsResult listTools(McpSchema.PaginatedRequest request,
				McpTransportContext transportContext) {
			return McpSchema.ListToolsResult.builder(List.of(tool(user(transportContext) + "-tool")))
				.nextCursor(request.cursor())
				.build();
		}

		@Override
		public Optional<McpSchema.Tool> resolveTool(String name, McpTransportContext transportContext) {
			String user = user(transportContext);
			if (!name.equals(user + "-tool")) {
				return Optional.empty();
			}
			return Optional.of(tool(name));
		}

		@Override
		public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request,
				McpTransportContext transportContext) {
			return McpSchema.CallToolResult.builder().addTextContent("tool:" + user(transportContext)).build();
		}

		@Override
		public McpSchema.ListResourcesResult listResources(McpSchema.PaginatedRequest request,
				McpTransportContext transportContext) {
			String user = user(transportContext);
			return McpSchema.ListResourcesResult
				.builder(List.of(McpSchema.Resource.builder("test://resources/" + user, user + "-resource").build()))
				.nextCursor(request.cursor())
				.build();
		}

		@Override
		public McpSchema.ListResourceTemplatesResult listResourceTemplates(McpSchema.PaginatedRequest request,
				McpTransportContext transportContext) {
			String user = user(transportContext);
			return McpSchema.ListResourceTemplatesResult.builder(List.of(McpSchema.ResourceTemplate
				.builder("test://resource-templates/" + user + "/{name}", user + "-resource-template")
				.build())).nextCursor(request.cursor()).build();
		}

		@Override
		public Optional<McpSchema.Resource> resolveResource(String uri, McpTransportContext transportContext) {
			String user = user(transportContext);
			if (!uri.equals("test://resources/" + user)) {
				return Optional.empty();
			}
			return Optional.of(McpSchema.Resource.builder(uri, user + "-resource").build());
		}

		@Override
		public Optional<McpSchema.ResourceTemplate> resolveResourceTemplate(String uri,
				McpTransportContext transportContext) {
			String user = user(transportContext);
			if (!uri.equals("test://resource-templates/" + user + "/{name}")
					&& !uri.equals("test://resource-templates/" + user + "/guide")) {
				return Optional.empty();
			}
			return Optional.of(McpSchema.ResourceTemplate
				.builder("test://resource-templates/" + user + "/{name}", user + "-resource-template")
				.build());
		}

		@Override
		public McpSchema.ReadResourceResult readResource(McpSchema.ReadResourceRequest request,
				McpTransportContext transportContext) {
			String user = user(transportContext);
			String content = request.uri().equals("test://resources/" + user) ? "resource:" + user
					: "resource-template:" + user;
			return McpSchema.ReadResourceResult
				.builder(List.of(McpSchema.TextResourceContents.builder(request.uri(), content).build()))
				.build();
		}

		@Override
		public McpSchema.ListPromptsResult listPrompts(McpSchema.PaginatedRequest request,
				McpTransportContext transportContext) {
			String user = user(transportContext);
			return McpSchema.ListPromptsResult.builder(List.of(McpSchema.Prompt.builder(user + "-prompt")
				.arguments(List.of(McpSchema.PromptArgument.builder("name").build()))
				.build())).nextCursor(request.cursor()).build();
		}

		@Override
		public Optional<McpSchema.Prompt> resolvePrompt(String name, McpTransportContext transportContext) {
			String user = user(transportContext);
			if (!name.equals(user + "-prompt")) {
				return Optional.empty();
			}
			return Optional.of(McpSchema.Prompt.builder(name)
				.arguments(List.of(McpSchema.PromptArgument.builder("name").build()))
				.build());
		}

		@Override
		public McpSchema.GetPromptResult getPrompt(McpSchema.GetPromptRequest request,
				McpTransportContext transportContext) {
			return McpSchema.GetPromptResult
				.builder(
						List.of(McpSchema.PromptMessage
							.builder(McpSchema.Role.USER,
									McpSchema.TextContent.builder("prompt:" + user(transportContext)).build())
							.build()))
				.build();
		}

		@Override
		public McpSchema.CompleteResult complete(McpSchema.CompleteRequest request,
				McpTransportContext transportContext) {
			return new McpSchema.CompleteResult(
					new McpSchema.CompleteResult.CompleteCompletion(List.of("completion:" + user(transportContext))));
		}

	}

	private static final class MutableRepository
			implements ToolsRepository, ResourcesRepository, PromptsRepository, CompletionsRepository {

		private final Map<String, McpStatelessServerFeatures.SyncToolSpecification> tools = new LinkedHashMap<>();

		private final Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resources = new LinkedHashMap<>();

		private final Map<String, McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates = new LinkedHashMap<>();

		private final Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts = new LinkedHashMap<>();

		@Override
		public McpSchema.ListToolsResult listTools(McpSchema.PaginatedRequest request,
				McpTransportContext transportContext) {
			return McpSchema.ListToolsResult.builder(
					this.tools.values().stream().map(McpStatelessServerFeatures.SyncToolSpecification::tool).toList())
				.build();
		}

		@Override
		public Optional<McpSchema.Tool> resolveTool(String name, McpTransportContext transportContext) {
			return Optional.ofNullable(this.tools.get(name))
				.map(McpStatelessServerFeatures.SyncToolSpecification::tool);
		}

		@Override
		public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request,
				McpTransportContext transportContext) {
			return this.tools.get(request.name()).callHandler().apply(transportContext, request);
		}

		@Override
		public void addTool(McpStatelessServerFeatures.SyncToolSpecification toolSpecification) {
			this.tools.put(toolSpecification.tool().name(), toolSpecification);
		}

		@Override
		public boolean removeTool(String toolName) {
			return this.tools.remove(toolName) != null;
		}

		@Override
		public McpSchema.ListResourcesResult listResources(McpSchema.PaginatedRequest request,
				McpTransportContext transportContext) {
			return McpSchema.ListResourcesResult
				.builder(this.resources.values()
					.stream()
					.map(McpStatelessServerFeatures.SyncResourceSpecification::resource)
					.toList())
				.build();
		}

		@Override
		public McpSchema.ListResourceTemplatesResult listResourceTemplates(McpSchema.PaginatedRequest request,
				McpTransportContext transportContext) {
			return McpSchema.ListResourceTemplatesResult
				.builder(this.resourceTemplates.values()
					.stream()
					.map(McpStatelessServerFeatures.SyncResourceTemplateSpecification::resourceTemplate)
					.toList())
				.build();
		}

		@Override
		public Optional<McpSchema.Resource> resolveResource(String uri, McpTransportContext transportContext) {
			return Optional.ofNullable(this.resources.get(uri))
				.map(McpStatelessServerFeatures.SyncResourceSpecification::resource);
		}

		@Override
		public Optional<McpSchema.ResourceTemplate> resolveResourceTemplate(String uri,
				McpTransportContext transportContext) {
			return this.resourceTemplates.values()
				.stream()
				.filter(resourceTemplate -> matches(resourceTemplate.resourceTemplate().uriTemplate(), uri))
				.map(McpStatelessServerFeatures.SyncResourceTemplateSpecification::resourceTemplate)
				.findFirst();
		}

		@Override
		public McpSchema.ReadResourceResult readResource(McpSchema.ReadResourceRequest request,
				McpTransportContext transportContext) {
			McpStatelessServerFeatures.SyncResourceSpecification resourceSpecification = this.resources
				.get(request.uri());
			if (resourceSpecification != null) {
				return resourceSpecification.readHandler().apply(transportContext, request);
			}
			return this.resourceTemplates.values()
				.stream()
				.filter(resourceTemplate -> matches(resourceTemplate.resourceTemplate().uriTemplate(), request.uri()))
				.findFirst()
				.orElseThrow()
				.readHandler()
				.apply(transportContext, request);
		}

		@Override
		public void addResource(McpStatelessServerFeatures.SyncResourceSpecification resourceSpecification) {
			this.resources.put(resourceSpecification.resource().uri(), resourceSpecification);
		}

		@Override
		public boolean removeResource(String resourceUri) {
			return this.resources.remove(resourceUri) != null;
		}

		@Override
		public void addResourceTemplate(
				McpStatelessServerFeatures.SyncResourceTemplateSpecification resourceTemplateSpecification) {
			this.resourceTemplates.put(resourceTemplateSpecification.resourceTemplate().uriTemplate(),
					resourceTemplateSpecification);
		}

		@Override
		public boolean removeResourceTemplate(String uriTemplate) {
			return this.resourceTemplates.remove(uriTemplate) != null;
		}

		@Override
		public McpSchema.ListPromptsResult listPrompts(McpSchema.PaginatedRequest request,
				McpTransportContext transportContext) {
			return McpSchema.ListPromptsResult
				.builder(this.prompts.values()
					.stream()
					.map(McpStatelessServerFeatures.SyncPromptSpecification::prompt)
					.toList())
				.build();
		}

		@Override
		public Optional<McpSchema.Prompt> resolvePrompt(String name, McpTransportContext transportContext) {
			return Optional.ofNullable(this.prompts.get(name))
				.map(McpStatelessServerFeatures.SyncPromptSpecification::prompt);
		}

		@Override
		public McpSchema.GetPromptResult getPrompt(McpSchema.GetPromptRequest request,
				McpTransportContext transportContext) {
			return this.prompts.get(request.name()).promptHandler().apply(transportContext, request);
		}

		@Override
		public void addPrompt(McpStatelessServerFeatures.SyncPromptSpecification promptSpecification) {
			this.prompts.put(promptSpecification.prompt().name(), promptSpecification);
		}

		@Override
		public boolean removePrompt(String promptName) {
			return this.prompts.remove(promptName) != null;
		}

		@Override
		public McpSchema.CompleteResult complete(McpSchema.CompleteRequest request,
				McpTransportContext transportContext) {
			return new McpSchema.CompleteResult(new McpSchema.CompleteResult.CompleteCompletion(List.of()));
		}

		private static boolean matches(String uriTemplate, String uri) {
			String prefix = uriTemplate.replace("{name}", "");
			return uriTemplate.equals(uri) || uri.startsWith(prefix);
		}

	}

}
