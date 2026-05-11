/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for dynamic MCP primitive repositories.
 *
 * @author Taewoong Kim
 */
class PrimitiveRepositoryTests {

	private static final String USER_KEY = "user";

	@Test
	void toolsRepositoryControlsListAndCallVisibilityPerRequestContext() {
		CapturingServerTransportProvider provider = new CapturingServerTransportProvider();
		McpServer.async(provider)
			.jsonMapper(new GsonMcpJsonMapper())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(new UserAwareToolsRepository())
			.build();
		MockServerTransport transport = new MockServerTransport();
		McpServerSession session = initializedSession(provider, transport);

		JSONRPCResponse aliceListResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, "alice-tools", new McpSchema.PaginatedRequest()),
				"alice");
		assertThat(aliceListResponse.error()).isNull();
		assertThat((McpSchema.ListToolsResult) aliceListResponse.result()).satisfies(result -> {
			assertThat(result.tools()).extracting(McpSchema.Tool::name).containsExactly("alice-tool");
		});

		JSONRPCResponse bobListResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, "bob-tools", new McpSchema.PaginatedRequest()), "bob");
		assertThat(bobListResponse.error()).isNull();
		assertThat((McpSchema.ListToolsResult) bobListResponse.result()).satisfies(result -> {
			assertThat(result.tools()).extracting(McpSchema.Tool::name).containsExactly("bob-tool");
		});

		JSONRPCResponse forbiddenCallResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, "alice-calls-bob",
						McpSchema.CallToolRequest.builder("bob-tool").build()),
				"alice");
		assertThat(forbiddenCallResponse.error()).isNotNull();

		JSONRPCResponse bobCallResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, "bob-calls-bob",
						McpSchema.CallToolRequest.builder("bob-tool").build()),
				"bob");
		assertThat(bobCallResponse.error()).isNull();
		assertThat((McpSchema.CallToolResult) bobCallResponse.result()).satisfies(result -> {
			assertThat(text(result.content().get(0))).isEqualTo("bob-tool");
		});
	}

	@Test
	void resourcesRepositoryControlsListAndReadVisibilityPerRequestContext() {
		CapturingServerTransportProvider provider = new CapturingServerTransportProvider();
		McpServer.async(provider)
			.jsonMapper(new GsonMcpJsonMapper())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.resourcesRepository(new UserAwareResourcesRepository())
			.build();
		MockServerTransport transport = new MockServerTransport();
		McpServerSession session = initializedSession(provider, transport);

		JSONRPCResponse aliceListResponse = handleWithUser(session, transport, new JSONRPCRequest(
				McpSchema.METHOD_RESOURCES_LIST, "alice-resources", new McpSchema.PaginatedRequest()), "alice");
		assertThat(aliceListResponse.error()).isNull();
		assertThat((McpSchema.ListResourcesResult) aliceListResponse.result()).satisfies(result -> {
			assertThat(result.resources()).extracting(McpSchema.Resource::uri).containsExactly(resourceUri("alice"));
		});

		JSONRPCResponse aliceTemplateListResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, "alice-resource-templates",
						new McpSchema.PaginatedRequest()),
				"alice");
		assertThat(aliceTemplateListResponse.error()).isNull();
		assertThat((McpSchema.ListResourceTemplatesResult) aliceTemplateListResponse.result()).satisfies(result -> {
			assertThat(result.resourceTemplates()).extracting(McpSchema.ResourceTemplate::uriTemplate)
				.containsExactly(resourceTemplateUri("alice"));
		});

		JSONRPCResponse forbiddenReadResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_RESOURCES_READ, "alice-reads-bob",
						McpSchema.ReadResourceRequest.builder(resourceUri("bob")).build()),
				"alice");
		assertThat(forbiddenReadResponse.error()).isNotNull();

		JSONRPCResponse forbiddenTemplateReadResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_RESOURCES_READ, "alice-reads-bob-template",
						McpSchema.ReadResourceRequest.builder(resourceTemplateReadUri("bob")).build()),
				"alice");
		assertThat(forbiddenTemplateReadResponse.error()).isNotNull();

		JSONRPCResponse bobReadResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_RESOURCES_READ, "bob-reads-bob",
						McpSchema.ReadResourceRequest.builder(resourceUri("bob")).build()),
				"bob");
		assertThat(bobReadResponse.error()).isNull();
		assertThat((McpSchema.ReadResourceResult) bobReadResponse.result()).satisfies(result -> {
			assertThat(((McpSchema.TextResourceContents) result.contents().get(0)).text()).isEqualTo("bob-resource");
		});

		JSONRPCResponse bobTemplateReadResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_RESOURCES_READ, "bob-reads-bob-template",
						McpSchema.ReadResourceRequest.builder(resourceTemplateReadUri("bob")).build()),
				"bob");
		assertThat(bobTemplateReadResponse.error()).isNull();
		assertThat((McpSchema.ReadResourceResult) bobTemplateReadResponse.result()).satisfies(result -> {
			assertThat(((McpSchema.TextResourceContents) result.contents().get(0)).text())
				.isEqualTo("bob-resource-template");
		});
	}

	@Test
	void promptsRepositoryControlsListAndGetVisibilityPerRequestContext() {
		CapturingServerTransportProvider provider = new CapturingServerTransportProvider();
		McpServer.async(provider)
			.jsonMapper(new GsonMcpJsonMapper())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.promptsRepository(new UserAwarePromptsRepository())
			.build();
		MockServerTransport transport = new MockServerTransport();
		McpServerSession session = initializedSession(provider, transport);

		JSONRPCResponse aliceListResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_PROMPT_LIST, "alice-prompts", new McpSchema.PaginatedRequest()),
				"alice");
		assertThat(aliceListResponse.error()).isNull();
		assertThat((McpSchema.ListPromptsResult) aliceListResponse.result()).satisfies(result -> {
			assertThat(result.prompts()).extracting(McpSchema.Prompt::name).containsExactly(promptName("alice"));
		});

		JSONRPCResponse forbiddenGetResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_PROMPT_GET, "alice-gets-bob",
						McpSchema.GetPromptRequest.builder(promptName("bob")).build()),
				"alice");
		assertThat(forbiddenGetResponse.error()).isNotNull();

		JSONRPCResponse bobGetResponse = handleWithUser(session, transport,
				new JSONRPCRequest(McpSchema.METHOD_PROMPT_GET, "bob-gets-bob",
						McpSchema.GetPromptRequest.builder(promptName("bob")).build()),
				"bob");
		assertThat(bobGetResponse.error()).isNull();
		assertThat((McpSchema.GetPromptResult) bobGetResponse.result()).satisfies(result -> {
			assertThat(text(result.messages().get(0).content())).isEqualTo("bob-prompt");
		});
	}

	@Test
	void inMemoryToolsRepositoryReplacesDuplicateTools() {
		InMemoryToolsRepository repository = new InMemoryToolsRepository();

		repository.addTool(toolSpec("duplicate-tool", "first"));
		repository.addTool(toolSpec("duplicate-tool", "second"));

		McpSchema.ListToolsResult result = repository.listTools(null, new McpSchema.PaginatedRequest()).block();

		assertThat(result).isNotNull();
		assertThat(result.tools()).hasSize(1);
		assertThat(result.tools().get(0).description()).isEqualTo("second");
		assertThat(repository.removeTool("duplicate-tool")).isTrue();
		assertThat(repository.removeTool("duplicate-tool")).isFalse();
	}

	@Test
	void inMemoryResourcesRepositoryResolvesResourcesAndTemplates() {
		InMemoryResourcesRepository repository = new InMemoryResourcesRepository();
		var resource = McpSchema.Resource.builder("file:///docs/static.txt", "Static doc").build();
		var templatedDirectResource = McpSchema.Resource
			.builder("file:///direct/{name}.txt", "Direct template-like doc")
			.build();
		var template = McpSchema.ResourceTemplate.builder("file:///docs/{name}.txt", "Doc template").build();

		repository.addResource(new McpServerFeatures.AsyncResourceSpecification(resource,
				(exchange, request) -> Mono.just(McpSchema.ReadResourceResult.builder(List.of()).build())));
		repository.addResource(new McpServerFeatures.AsyncResourceSpecification(templatedDirectResource,
				(exchange, request) -> Mono.just(McpSchema.ReadResourceResult.builder(List.of()).build())));
		repository.addResourceTemplate(new McpServerFeatures.AsyncResourceTemplateSpecification(template,
				(exchange, request) -> Mono.just(McpSchema.ReadResourceResult.builder(List.of()).build())));

		assertThat(repository.resolveResource("file:///docs/static.txt", null).block()).isNotNull();
		assertThat(repository.resolveResource("file:///docs/static.txt/extra", null).block()).isNull();
		assertThat(repository.resolveResource("file:///direct/readme.txt", null).block()).isNotNull();
		assertThat(repository.resolveResourceTemplate("file:///docs/guide.txt", null).block()).isNotNull();
		assertThat(repository.listResourceTemplates(null, new McpSchema.PaginatedRequest()).block().resourceTemplates())
			.extracting(McpSchema.ResourceTemplate::uriTemplate)
			.containsExactly("file:///docs/{name}.txt");
	}

	@Test
	void serverPassesCursorToCustomToolsRepository() {
		CapturingServerTransportProvider provider = new CapturingServerTransportProvider();
		CursorAwareToolsRepository repository = new CursorAwareToolsRepository();

		McpServer.async(provider)
			.jsonMapper(new GsonMcpJsonMapper())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(repository)
			.build();

		MockServerTransport transport = new MockServerTransport();
		McpServerSession session = initializedSession(provider, transport);
		session
			.handle(new JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, "tools",
					new McpSchema.PaginatedRequest("cursor-1")))
			.block();

		JSONRPCResponse response = (JSONRPCResponse) transport.sent.get(transport.sent.size() - 1);

		assertThat(repository.receivedCursor.get()).isEqualTo("cursor-1");
		assertThat(response.error()).isNull();
		assertThat((McpSchema.ListToolsResult) response.result()).satisfies(result -> {
			assertThat(result.tools()).extracting(McpSchema.Tool::name).containsExactly("visible-tool");
			assertThat(result.nextCursor()).isEqualTo("cursor-2");
		});
	}

	@Test
	void runtimeToolMutationRequiresRepositoryMutationSupport() {
		McpAsyncServer server = McpServer.async(new CapturingServerTransportProvider())
			.jsonMapper(new GsonMcpJsonMapper())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(new CursorAwareToolsRepository())
			.build();

		assertThatThrownBy(() -> server.addTool(toolSpec("runtime-tool", "Runtime tool")).block())
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support adding tools");
		assertThatThrownBy(() -> server.removeTool("runtime-tool").block())
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("does not support removing tools");
	}

	@Test
	void statefulRuntimeRepositoryMutationsSendListChangedNotificationsForSupportedOperations() {
		CapturingServerTransportProvider provider = new CapturingServerTransportProvider();
		McpAsyncServer server = McpServer.async(provider)
			.jsonMapper(new GsonMcpJsonMapper())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.capabilities(
					McpSchema.ServerCapabilities.builder().tools(true).resources(false, true).prompts(true).build())
			.toolsRepository(new InMemoryToolsRepository())
			.resourcesRepository(new InMemoryResourcesRepository())
			.promptsRepository(new InMemoryPromptsRepository())
			.build();

		server.addTool(toolSpec("runtime-tool", "Runtime tool")).block();
		server.removeTool("runtime-tool").block();
		server.addResource(UserAwareResourcesRepository.resourceSpecification("runtime")).block();
		server.removeResource(resourceUri("runtime")).block();
		server.addResourceTemplate(UserAwareResourcesRepository.resourceTemplateSpecification("runtime")).block();
		server.removeResourceTemplate(resourceTemplateUri("runtime")).block();
		server.addPrompt(UserAwarePromptsRepository.promptSpecification("runtime")).block();
		server.removePrompt(promptName("runtime")).block();

		assertThat(provider.notifiedMethods).containsExactly(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,
				McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED,
				McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED,
				McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED,
				McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED);
	}

	@Test
	void statefulCompletionRepositoryEnablesCompletionCapability() {
		var serverInfo = McpSchema.Implementation.builder("test-server", "1.0.0").build();
		var features = new McpServerFeatures.Async(serverInfo, null, List.of(), null, Map.of(), Map.of(), null,
				Map.of(), null, Map.of(), new InMemoryCompletionsRepository(), List.of(), null);

		assertThat(features.serverCapabilities().completions()).isNotNull();
	}

	@Test
	void statelessCompletionRepositoryEnablesCompletionCapability() {
		var serverInfo = McpSchema.Implementation.builder("test-server", "1.0.0").build();
		var features = new McpStatelessServerFeatures.Async(serverInfo, null, List.of(), null, Map.of(), Map.of(), null,
				Map.of(), null, Map.of(), new InMemoryStatelessCompletionsRepository(), null);

		assertThat(features.serverCapabilities().completions()).isNotNull();
	}

	@Test
	void statefulSyncBuilderAcceptsCustomRepositories() {
		CapturingServerTransportProvider provider = new CapturingServerTransportProvider();

		McpSyncServer server = McpServer.sync(provider)
			.jsonMapper(new GsonMcpJsonMapper())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(new InMemoryToolsRepository())
			.build();

		assertThat(server.getServerCapabilities().tools()).isNotNull();
	}

	@Test
	void statelessSyncBuilderAcceptsCustomRepositories() {
		MockStatelessServerTransport transport = new MockStatelessServerTransport();

		McpStatelessSyncServer server = McpServer.sync(transport)
			.jsonMapper(new GsonMcpJsonMapper())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(new InMemoryStatelessToolsRepository())
			.build();

		assertThat(server.getServerCapabilities().tools()).isNotNull();
	}

	@Test
	void statefulBuilderRejectsToolsRepositoryMixedWithStaticTools() {
		var tool = toolSpec("static-tool", "Static tool").tool();

		assertThatThrownBy(() -> McpServer.async(new CapturingServerTransportProvider())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(new InMemoryToolsRepository())
			.toolCall(tool, (exchange, request) -> Mono.just(emptyCallToolResult())))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("toolsRepository");

		assertThatThrownBy(() -> McpServer.async(new CapturingServerTransportProvider())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolCall(tool, (exchange, request) -> Mono.just(emptyCallToolResult()))
			.toolsRepository(new InMemoryToolsRepository())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("toolsRepository");
	}

	@Test
	void statefulBuilderRejectsResourcesRepositoryMixedWithStaticResources() {
		var resource = McpSchema.Resource.builder("file:///docs/static.txt", "Static doc").build();
		var resourceSpec = new McpServerFeatures.AsyncResourceSpecification(resource,
				(exchange, request) -> Mono.just(McpSchema.ReadResourceResult.builder(List.of()).build()));
		var template = McpSchema.ResourceTemplate.builder("file:///docs/{name}.txt", "Doc template").build();
		var templateSpec = new McpServerFeatures.AsyncResourceTemplateSpecification(template,
				(exchange, request) -> Mono.just(McpSchema.ReadResourceResult.builder(List.of()).build()));

		assertThatThrownBy(() -> McpServer.async(new CapturingServerTransportProvider())
			.resourcesRepository(new InMemoryResourcesRepository())
			.resources(resourceSpec)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("resourcesRepository");

		assertThatThrownBy(() -> McpServer.async(new CapturingServerTransportProvider())
			.resourceTemplates(templateSpec)
			.resourcesRepository(new InMemoryResourcesRepository())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("resourcesRepository");
	}

	@Test
	void statelessBuilderRejectsToolsRepositoryMixedWithStaticTools() {
		var tool = McpSchema.Tool.builder().name("stateless-static-tool").inputSchema(EMPTY_JSON_SCHEMA).build();

		assertThatThrownBy(() -> McpServer.async(new MockStatelessServerTransport())
			.jsonSchemaValidator(VALID_SCHEMA_VALIDATOR)
			.toolsRepository(new InMemoryStatelessToolsRepository())
			.toolCall(tool, (context, request) -> Mono.just(emptyCallToolResult())))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("toolsRepository");
	}

	private McpServerFeatures.AsyncToolSpecification toolSpec(String name, String description) {
		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(name)
			.description(description)
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		return McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> Mono.just(emptyCallToolResult()))
			.build();
	}

	private static CallToolResult emptyCallToolResult() {
		return CallToolResult.builder().content(List.of()).isError(false).build();
	}

	private static McpServerSession initializedSession(CapturingServerTransportProvider provider,
			MockServerTransport transport) {
		McpServerSession session = provider.sessionFactory.create(transport);
		session
			.handle(new JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "init",
					McpSchema.InitializeRequest.builder(ProtocolVersions.MCP_2025_11_25,
							new McpSchema.ClientCapabilities(null, null, null, null),
							McpSchema.Implementation.builder("test-client", "1.0.0").build())
						.build()))
			.block();
		session.handle(new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED)).block();
		return session;
	}

	private static JSONRPCResponse handleWithUser(McpServerSession session, MockServerTransport transport,
			JSONRPCRequest request, String user) {
		session.handle(request)
			.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, McpTransportContext.create(Map.of(USER_KEY, user))))
			.block();
		return (JSONRPCResponse) transport.sent.get(transport.sent.size() - 1);
	}

	private static String user(McpAsyncServerExchange exchange) {
		return user((exchange != null) ? exchange.transportContext() : McpTransportContext.EMPTY);
	}

	private static String user(McpTransportContext transportContext) {
		Object user = transportContext.get(USER_KEY);
		return (user != null) ? user.toString() : "anonymous";
	}

	private static String toolName(String user) {
		return user + "-tool";
	}

	private static String resourceUri(String user) {
		return "test://resources/" + user;
	}

	private static String resourceTemplateUri(String user) {
		return "test://resource-templates/" + user + "/{name}";
	}

	private static String resourceTemplateReadUri(String user) {
		return "test://resource-templates/" + user + "/guide";
	}

	private static String promptName(String user) {
		return user + "-prompt";
	}

	private static String text(McpSchema.Content content) {
		return ((McpSchema.TextContent) content).text();
	}

	private static final JsonSchemaValidator VALID_SCHEMA_VALIDATOR = new JsonSchemaValidator() {

		@Override
		public ValidationResponse validate(Map<String, Object> schema, Object structuredContent) {
			return ValidationResponse.asValid(null);
		}

	};

	private static final class UserAwareToolsRepository implements ToolsRepository {

		@Override
		public Mono<McpSchema.ListToolsResult> listTools(McpAsyncServerExchange exchange,
				McpSchema.PaginatedRequest request) {
			return Mono
				.just(McpSchema.ListToolsResult.builder(List.of(toolSpecification(user(exchange)).tool())).build());
		}

		@Override
		public Mono<McpServerFeatures.AsyncToolSpecification> resolveTool(String name,
				McpAsyncServerExchange exchange) {
			String user = user(exchange);
			if (!toolName(user).equals(name)) {
				return Mono.empty();
			}
			return Mono.just(toolSpecification(user));
		}

		private static McpServerFeatures.AsyncToolSpecification toolSpecification(String user) {
			McpSchema.Tool tool = McpSchema.Tool.builder().name(toolName(user)).inputSchema(EMPTY_JSON_SCHEMA).build();
			return new McpServerFeatures.AsyncToolSpecification(tool, (exchange, request) -> Mono
				.just(CallToolResult.builder().addTextContent(toolName(user(exchange))).build()));
		}

	}

	private static final class UserAwareResourcesRepository implements ResourcesRepository {

		@Override
		public Mono<McpSchema.ListResourcesResult> listResources(McpAsyncServerExchange exchange,
				McpSchema.PaginatedRequest request) {
			return Mono.just(McpSchema.ListResourcesResult.builder(List.of(resource(user(exchange)))).build());
		}

		@Override
		public Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates(McpAsyncServerExchange exchange,
				McpSchema.PaginatedRequest request) {
			return Mono
				.just(McpSchema.ListResourceTemplatesResult.builder(List.of(resourceTemplate(user(exchange)))).build());
		}

		@Override
		public Mono<McpServerFeatures.AsyncResourceSpecification> resolveResource(String uri,
				McpAsyncServerExchange exchange) {
			String user = user(exchange);
			if (!resourceUri(user).equals(uri)) {
				return Mono.empty();
			}
			return Mono.just(resourceSpecification(user));
		}

		@Override
		public Mono<McpServerFeatures.AsyncResourceTemplateSpecification> resolveResourceTemplate(String uri,
				McpAsyncServerExchange exchange) {
			String user = user(exchange);
			if (!uri.startsWith("test://resource-templates/" + user + "/")) {
				return Mono.empty();
			}
			return Mono.just(resourceTemplateSpecification(user));
		}

		private static McpServerFeatures.AsyncResourceSpecification resourceSpecification(String user) {
			return new McpServerFeatures.AsyncResourceSpecification(resource(user), (exchange, request) -> {
				var content = McpSchema.TextResourceContents.builder(request.uri(), user(exchange) + "-resource")
					.mimeType("text/plain")
					.build();
				return Mono.just(McpSchema.ReadResourceResult.builder(List.of(content)).build());
			});
		}

		private static McpSchema.Resource resource(String user) {
			return McpSchema.Resource.builder(resourceUri(user), user + " resource").build();
		}

		private static McpServerFeatures.AsyncResourceTemplateSpecification resourceTemplateSpecification(String user) {
			return new McpServerFeatures.AsyncResourceTemplateSpecification(resourceTemplate(user),
					(exchange, request) -> {
						var content = McpSchema.TextResourceContents
							.builder(request.uri(), user(exchange) + "-resource-template")
							.mimeType("text/plain")
							.build();
						return Mono.just(McpSchema.ReadResourceResult.builder(List.of(content)).build());
					});
		}

		private static McpSchema.ResourceTemplate resourceTemplate(String user) {
			return McpSchema.ResourceTemplate.builder(resourceTemplateUri(user), user + " resource template").build();
		}

	}

	private static final class UserAwarePromptsRepository implements PromptsRepository {

		@Override
		public Mono<McpSchema.ListPromptsResult> listPrompts(McpAsyncServerExchange exchange,
				McpSchema.PaginatedRequest request) {
			return Mono.just(McpSchema.ListPromptsResult.builder(List.of(prompt(user(exchange)))).build());
		}

		@Override
		public Mono<McpServerFeatures.AsyncPromptSpecification> resolvePrompt(String name,
				McpAsyncServerExchange exchange) {
			String user = user(exchange);
			if (!promptName(user).equals(name)) {
				return Mono.empty();
			}
			return Mono.just(promptSpecification(user));
		}

		private static McpServerFeatures.AsyncPromptSpecification promptSpecification(String user) {
			return new McpServerFeatures.AsyncPromptSpecification(prompt(user), (exchange, request) -> {
				var message = McpSchema.PromptMessage
					.builder(McpSchema.Role.USER, McpSchema.TextContent.builder(user(exchange) + "-prompt").build())
					.build();
				return Mono.just(McpSchema.GetPromptResult.builder(List.of(message)).build());
			});
		}

		private static McpSchema.Prompt prompt(String user) {
			return McpSchema.Prompt.builder(promptName(user)).build();
		}

	}

	private static final class CursorAwareToolsRepository implements ToolsRepository {

		private final AtomicReference<String> receivedCursor = new AtomicReference<>();

		@Override
		public Mono<McpSchema.ListToolsResult> listTools(McpAsyncServerExchange exchange,
				McpSchema.PaginatedRequest request) {
			this.receivedCursor.set(request.cursor());
			var tool = McpSchema.Tool.builder().name("visible-tool").inputSchema(EMPTY_JSON_SCHEMA).build();
			return Mono.just(McpSchema.ListToolsResult.builder(List.of(tool)).nextCursor("cursor-2").build());
		}

		@Override
		public Mono<McpServerFeatures.AsyncToolSpecification> resolveTool(String name,
				McpAsyncServerExchange exchange) {
			return Mono.empty();
		}

	}

	private static final class CapturingServerTransportProvider implements McpServerTransportProvider {

		private McpServerSession.Factory sessionFactory;

		private final List<String> notifiedMethods = new ArrayList<>();

		@Override
		public void setSessionFactory(McpServerSession.Factory sessionFactory) {
			this.sessionFactory = sessionFactory;
		}

		@Override
		public Mono<Void> notifyClients(String method, Object params) {
			this.notifiedMethods.add(method);
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public List<String> protocolVersions() {
			return List.of(ProtocolVersions.MCP_2025_11_25);
		}

	}

	private static final class MockServerTransport implements McpServerTransport {

		private final List<JSONRPCMessage> sent = new ArrayList<>();

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public Mono<Void> sendMessage(JSONRPCMessage message) {
			this.sent.add(message);
			return Mono.empty();
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return (T) data;
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

}
