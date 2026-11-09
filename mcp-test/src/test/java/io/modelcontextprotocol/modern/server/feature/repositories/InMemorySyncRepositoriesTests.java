/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature.repositories;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.McpSchema.PromptMessage;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.Role;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.McpSyncPrompt;
import io.modelcontextprotocol.modern.server.feature.McpSyncResource;
import io.modelcontextprotocol.modern.server.feature.McpSyncResourceTemplate;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.PromptsFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.repository.InMemorySyncPromptRepository;
import io.modelcontextprotocol.modern.server.feature.repository.InMemorySyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.repository.InMemorySyncToolRepository;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the in-memory repositories through a real {@link McpServer}, so that the
 * features' handling of what the repositories return (unknown names, cursors, required
 * capabilities, completion) is covered too.
 *
 * @author Christian Tzolov
 */
class InMemorySyncRepositoriesTests {

	private static JSONRPCResponse call(McpServer server, String method, Object... params) {
		Map<String, Object> body = new HashMap<>();
		body.put("_meta", meta());
		for (int i = 0; i < params.length; i += 2) {
			body.put((String) params[i], params[i + 1]);
		}
		return respond(server, new JSONRPCRequest(method, 1, body)).block();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> result(JSONRPCResponse response) {
		assertThat(response.error()).isNull();
		return (Map<String, Object>) response.result();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> names(JSONRPCResponse response, String listKey, String nameKey) {
		return ((List<Map<String, Object>>) result(response).get(listKey)).stream()
			.map(entry -> entry.get(nameKey))
			.toList();
	}

	@SuppressWarnings("unchecked")
	private static List<String> completionValues(JSONRPCResponse response) {
		return (List<String>) ((Map<String, Object>) result(response).get("completion")).get("values");
	}

	@SuppressWarnings("unchecked")
	private static String firstText(JSONRPCResponse response, String listKey) {
		return (String) ((List<Map<String, Object>>) result(response).get(listKey)).get(0).get("text");
	}

	private static McpServer server(McpFeature feature) {
		return McpServer.builder().serverInfo(SERVER_INFO).feature(feature).build();
	}

	private static McpSyncTool tool(String name) {
		return McpSyncTool.of(Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build(),
				(ctx, request) -> McpSyncResponse.result(CallToolResult.builder()
					.addContent(TextContent.builder(name + ":" + request.arguments().get("text")).build())
					.build()));
	}

	private static McpSyncPrompt prompt(String name) {
		return McpSyncPrompt.of(
				Prompt.builder(name).arguments(List.of(PromptArgument.builder("language").build())).build(),
				(ctx, request) -> McpSyncResponse.result(GetPromptResult
					.builder(List.of(PromptMessage.builder(Role.USER, TextContent.builder("hello").build()).build()))
					.build()));
	}

	private static McpSyncResource reader(String prefix) {
		return McpSyncResource.of((ctx,
				request) -> McpSyncResponse.result(ReadResourceResult
					.builder(List.of(TextResourceContents.builder(request.uri(), prefix + request.uri()).build()))
					.build()));
	}

	private static McpSyncResourceTemplate template(String uriTemplate) {
		return McpSyncResourceTemplate.of(ResourceTemplate.builder(uriTemplate, uriTemplate).build());
	}

	// Tools

	@Test
	void toolRepositoryListsAndCallsTools() {
		McpServer server = server(ToolsFeature.ofSync(InMemorySyncToolRepository.builder().tool(tool("echo")).build()));

		assertThat(names(call(server, "tools/list"), "tools", "name")).containsExactly("echo");
		assertThat(firstText(call(server, "tools/call", "name", "echo", "arguments", Map.of("text", "hi")), "content"))
			.isEqualTo("echo:hi");
		assertThat(call(server, "tools/call", "name", "nope").error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void toolRequiringUndeclaredCapabilityIsRejectedBeforeTheHandlerRuns() {
		McpSyncTool sampling = McpSyncTool.of(Tool.builder("sample", ToolsUtils.EMPTY_JSON_SCHEMA).build(),
				ClientCapabilities.builder().sampling().build(), (ctx, request) -> {
					throw new AssertionError("handler must not run");
				});
		McpServer server = server(ToolsFeature.ofSync(InMemorySyncToolRepository.builder().tool(sampling).build()));

		assertThat(call(server, "tools/call", "name", "sample").error().code())
			.isEqualTo(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY);
	}

	@Test
	void toolRepositoryPagesByName() {
		InMemorySyncToolRepository repository = InMemorySyncToolRepository.builder()
			.tools(List.of(tool("c"), tool("a"), tool("b")))
			.pageSize(2)
			.build();
		McpServer server = server(ToolsFeature.ofSync(repository));

		JSONRPCResponse first = call(server, "tools/list");
		assertThat(names(first, "tools", "name")).containsExactly("a", "b");
		Object cursor = result(first).get("nextCursor");
		assertThat(cursor).isNotNull();

		JSONRPCResponse second = call(server, "tools/list", "cursor", cursor);
		assertThat(names(second, "tools", "name")).containsExactly("c");
		assertThat(result(second).get("nextCursor")).isNull();
	}

	@Test
	void cursorStaysValidWhenTheLastListedEntryIsRemoved() {
		InMemorySyncToolRepository repository = InMemorySyncToolRepository.builder()
			.tools(List.of(tool("a"), tool("b"), tool("c")))
			.pageSize(2)
			.build();
		McpServer server = server(ToolsFeature.ofSync(repository));
		Object cursor = result(call(server, "tools/list")).get("nextCursor");

		repository.remove("b");

		assertThat(names(call(server, "tools/list", "cursor", cursor), "tools", "name")).containsExactly("c");
	}

	@Test
	void unknownCursorIsInvalidParams() {
		McpServer unpaged = server(
				ToolsFeature.ofSync(InMemorySyncToolRepository.builder().tool(tool("echo")).build()));
		McpServer paged = server(
				ToolsFeature.ofSync(InMemorySyncToolRepository.builder().tool(tool("echo")).pageSize(1).build()));

		for (McpServer server : List.of(unpaged, paged)) {
			JSONRPCResponse response = call(server, "tools/list", "cursor", "stale");
			assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
			assertThat(response.error().message()).contains("stale");
		}
	}

	@Test
	void toolRepositoryBroadcastsChanges() {
		McpChangeFeed changes = new McpChangeFeed();
		InMemorySyncToolRepository repository = InMemorySyncToolRepository.builder().changeBroadcaster(changes).build();

		StepVerifier.create(changes.changes().take(2))
			.then(() -> repository.add(tool("echo")))
			.expectNext(new ServerChange.ToolsListChanged())
			.then(() -> repository.remove("echo"))
			.expectNext(new ServerChange.ToolsListChanged())
			.verifyComplete();

		assertThat(repository.list(null, null).tools()).isEmpty();
		assertThat(repository.remove("echo")).isFalse();
	}

	@Test
	void toolRepositoryRejectsDuplicates() {
		InMemorySyncToolRepository repository = InMemorySyncToolRepository.builder().tool(tool("echo")).build();

		assertThatThrownBy(() -> repository.add(tool("echo"))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("echo");
		assertThatThrownBy(() -> InMemorySyncToolRepository.builder().tool(tool("echo")).tool(tool("echo")).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void pageSizeMustBePositive() {
		assertThatThrownBy(() -> InMemorySyncToolRepository.builder().pageSize(0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// Prompts

	@Test
	void promptRepositoryListsAndGetsPrompts() {
		InMemorySyncPromptRepository repository = InMemorySyncPromptRepository.builder()
			.prompts(List.of(prompt("review"), prompt("greet")))
			.build();
		McpServer server = server(PromptsFeature.ofSync(repository));

		assertThat(names(call(server, "prompts/list"), "prompts", "name")).containsExactly("greet", "review");
		assertThat((List<?>) result(call(server, "prompts/get", "name", "greet")).get("messages")).hasSize(1);
		assertThat(call(server, "prompts/get", "name", "nope").error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void promptRepositoryBroadcastsChanges() {
		McpChangeFeed changes = new McpChangeFeed();
		InMemorySyncPromptRepository repository = InMemorySyncPromptRepository.builder()
			.changeBroadcaster(changes)
			.build();

		StepVerifier.create(changes.changes().take(2))
			.then(() -> repository.add(prompt("greet")))
			.expectNext(new ServerChange.PromptsListChanged())
			.then(() -> repository.remove("greet"))
			.expectNext(new ServerChange.PromptsListChanged())
			.verifyComplete();
	}

	@Test
	void promptCompletionIsServedWhenSupported() {
		InMemorySyncPromptRepository repository = InMemorySyncPromptRepository.builder()
			.prompt(prompt("greet").withCompletionHandler(
					(ctx, request) -> CompleteResult.of(new CompleteResult.Completion(List.of("java", "kotlin")))))
			.supportsCompletion(true)
			.build();
		McpServer server = server(PromptsFeature.ofSync(repository));

		JSONRPCResponse response = call(server, "completion/complete", "ref",
				Map.of("type", "ref/prompt", "name", "greet"), "argument", Map.of("name", "language", "value", ""));

		assertThat(completionValues(response)).containsExactly("java", "kotlin");
	}

	@Test
	void promptAddedAfterBuildIsCompleted() {
		InMemorySyncPromptRepository repository = InMemorySyncPromptRepository.builder()
			.supportsCompletion(true)
			.build();
		McpServer server = server(PromptsFeature.ofSync(repository));

		repository.add(prompt("greet").withCompletionHandler(
				(ctx, request) -> CompleteResult.of(new CompleteResult.Completion(List.of("java")))));

		JSONRPCResponse response = call(server, "completion/complete", "ref",
				Map.of("type", "ref/prompt", "name", "greet"), "argument", Map.of("name", "language", "value", ""));
		assertThat(completionValues(response)).containsExactly("java");
	}

	@Test
	void promptCompletionIsNotServedByDefault() {
		InMemorySyncPromptRepository repository = InMemorySyncPromptRepository.builder()
			.prompt(prompt("greet").withCompletionHandler(
					(ctx, request) -> CompleteResult.of(new CompleteResult.Completion(List.of("java")))))
			.build();
		McpServer server = server(PromptsFeature.ofSync(repository));

		JSONRPCResponse response = call(server, "completion/complete", "ref",
				Map.of("type", "ref/prompt", "name", "greet"), "argument", Map.of("name", "language", "value", ""));

		assertThat(response.error()).isNotNull();
	}

	// Resources

	@Test
	void resourceRepositoryReadsStaticAndTemplatedUris() {
		InMemorySyncResourceRepository repository = InMemorySyncResourceRepository.builder()
			.resource(Resource.builder("test://items/special", "special").build(), reader("static:"))
			.template(template("test://items/{id}"), reader("item:"))
			.build();
		McpServer server = server(ResourcesFeature.ofSync(repository));

		assertThat(names(call(server, "resources/list"), "resources", "uri")).containsExactly("test://items/special");
		assertThat(names(call(server, "resources/templates/list"), "resourceTemplates", "uriTemplate"))
			.containsExactly("test://items/{id}");
		// An exact resource takes precedence over a template matching the same URI.
		assertThat(firstText(call(server, "resources/read", "uri", "test://items/special"), "contents"))
			.isEqualTo("static:test://items/special");
		assertThat(firstText(call(server, "resources/read", "uri", "test://items/42"), "contents"))
			.isEqualTo("item:test://items/42");
		assertThat(call(server, "resources/read", "uri", "test://nope").error().code())
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void resourceRepositoryRejectsDuplicates() {
		InMemorySyncResourceRepository repository = InMemorySyncResourceRepository.builder()
			.resource(Resource.builder("test://a", "a").build(), reader(""))
			.template(template("test://items/{id}"), reader(""))
			.build();

		assertThatThrownBy(() -> repository.addResource(Resource.builder("test://a", "dup").build(), reader("")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("test://a");
		assertThatThrownBy(() -> repository.addTemplate(template("test://items/{id}"), reader("")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("test://items/{id}");
	}

	@Test
	void resourceRepositoryBroadcastsChanges() {
		McpChangeFeed changes = new McpChangeFeed();
		InMemorySyncResourceRepository repository = InMemorySyncResourceRepository.builder()
			.changeBroadcaster(changes)
			.supportsSubscribe(true)
			.build();

		StepVerifier.create(changes.changes().take(5))
			.then(() -> repository.addResource(Resource.builder("test://a", "a").build(), reader("")))
			.expectNext(new ServerChange.ResourcesListChanged())
			.then(() -> repository.addTemplate(template("test://items/{id}"), reader("")))
			.expectNext(new ServerChange.ResourcesListChanged())
			.then(() -> repository.resourceUpdated("test://a"))
			.expectNext(new ServerChange.ResourceUpdated("test://a"))
			.then(() -> repository.removeResource("test://a"))
			.expectNext(new ServerChange.ResourcesListChanged())
			.then(() -> repository.removeTemplate("test://items/{id}"))
			.expectNext(new ServerChange.ResourcesListChanged())
			.verifyComplete();

		assertThat(repository.supportsSubscribe()).isTrue();
		assertThat(repository.list(null, null).resources()).isEmpty();
		assertThat(repository.listTemplates(null, null).resourceTemplates()).isEmpty();
		assertThat(repository.removeResource("test://a")).isFalse();
		assertThat(repository.removeTemplate("test://items/{id}")).isFalse();
	}

	@Test
	void resourceRepositoryPagesResourcesAndTemplates() {
		InMemorySyncResourceRepository repository = InMemorySyncResourceRepository.builder()
			.resource(Resource.builder("test://b", "b").build(), reader(""))
			.resource(Resource.builder("test://a", "a").build(), reader(""))
			.template(template("test://y/{id}"), reader(""))
			.template(template("test://x/{id}"), reader(""))
			.pageSize(1)
			.build();
		McpServer server = server(ResourcesFeature.ofSync(repository));

		JSONRPCResponse resources = call(server, "resources/list");
		assertThat(names(resources, "resources", "uri")).containsExactly("test://a");
		assertThat(names(call(server, "resources/list", "cursor", result(resources).get("nextCursor")), "resources",
				"uri"))
			.containsExactly("test://b");

		JSONRPCResponse templates = call(server, "resources/templates/list");
		assertThat(names(templates, "resourceTemplates", "uriTemplate")).containsExactly("test://x/{id}");
		assertThat(names(call(server, "resources/templates/list", "cursor", result(templates).get("nextCursor")),
				"resourceTemplates", "uriTemplate"))
			.containsExactly("test://y/{id}");
	}

	@Test
	void templateCompletionIsServedWhenSupported() {
		InMemorySyncResourceRepository repository = InMemorySyncResourceRepository.builder()
			.template(
					template("test://items/{id}").withCompletionHandler(
							(ctx, request) -> CompleteResult.of(new CompleteResult.Completion(List.of("1", "2")))),
					reader(""))
			.supportsCompletion(true)
			.build();
		McpServer server = server(ResourcesFeature.ofSync(repository));

		JSONRPCResponse response = call(server, "completion/complete", "ref",
				Map.of("type", "ref/resource", "uri", "test://items/{id}"), "argument",
				Map.of("name", "id", "value", ""));

		assertThat(completionValues(response)).containsExactly("1", "2");
		assertThat(repository.findTemplate(null, "test://items/42")).isNull();
	}

}
