/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature.repositories;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
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
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.McpAsyncPrompt;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResource;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResourceTemplate;
import io.modelcontextprotocol.modern.server.feature.McpAsyncTool;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.PromptsFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.modern.server.feature.repository.InMemoryAsyncPromptRepository;
import io.modelcontextprotocol.modern.server.feature.repository.InMemoryAsyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.repository.InMemoryAsyncToolRepository;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the async in-memory repositories through a real {@link McpServer}. They mirror
 * the sync repositories, whose edge cases {@link InMemorySyncRepositoriesTests} covers in
 * depth; these tests cover the main behaviour through the async API, plus what it adds:
 * results delivered as {@code Mono}s, read lazily, with cursor errors as error signals.
 *
 * @author Christian Tzolov
 */
class InMemoryAsyncRepositoriesTests {

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
	private static List<Object> names(JSONRPCResponse response, String listKey) {
		return ((List<Map<String, Object>>) result(response).get(listKey)).stream()
			.map(entry -> entry.get("name"))
			.toList();
	}

	private static McpServer server(McpFeature feature) {
		return McpServer.builder().serverInfo(SERVER_INFO).feature(feature).build();
	}

	private static McpAsyncTool tool(String name) {
		return McpAsyncTool.of(Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build(),
				(ctx, request) -> Mono.just(McpAsyncResponse.result(CallToolResult.builder()
					.addContent(TextContent.builder(name + ":" + request.arguments().get("text")).build())
					.build())));
	}

	private static McpAsyncPrompt prompt(String name) {
		return McpAsyncPrompt.of(
				Prompt.builder(name).arguments(List.of(PromptArgument.builder("language").build())).build(),
				(ctx, request) -> Mono.just(McpAsyncResponse.result(GetPromptResult
					.builder(List.of(PromptMessage.builder(Role.USER, TextContent.builder("hello").build()).build()))
					.build())));
	}

	@Test
	@SuppressWarnings("unchecked")
	void toolRepositoryListsAndCallsTools() {
		McpServer server = server(ToolsFeature
			.ofAsync(InMemoryAsyncToolRepository.builder().tools(List.of(tool("b"), tool("a"))).build()));

		assertThat(names(call(server, "tools/list"), "tools")).containsExactly("a", "b");
		Map<String, Object> called = result(call(server, "tools/call", "name", "a", "arguments", Map.of("text", "hi")));
		assertThat(((List<Map<String, Object>>) called.get("content")).get(0).get("text")).isEqualTo("a:hi");
		assertThat(call(server, "tools/call", "name", "nope").error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void toolRepositoryPagesByName() {
		McpServer server = server(ToolsFeature.ofAsync(InMemoryAsyncToolRepository.builder()
			.tools(List.of(tool("c"), tool("a"), tool("b")))
			.pageSize(2)
			.build()));

		JSONRPCResponse first = call(server, "tools/list");
		assertThat(names(first, "tools")).containsExactly("a", "b");
		JSONRPCResponse second = call(server, "tools/list", "cursor", result(first).get("nextCursor"));
		assertThat(names(second, "tools")).containsExactly("c");
		assertThat(result(second).get("nextCursor")).isNull();
	}

	@Test
	void unknownCursorIsAnErrorSignal() {
		InMemoryAsyncToolRepository repository = InMemoryAsyncToolRepository.builder().tool(tool("a")).build();

		// Not thrown from list(): an async repository reports failures through the Mono.
		Mono<ToolsPage> page = repository.list(null, "stale");

		StepVerifier.create(page).expectError(McpException.class).verify();
		assertThat(call(server(ToolsFeature.ofAsync(repository)), "tools/list", "cursor", "stale").error().code())
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void listAndFindReadTheCatalogWhenSubscribed() {
		InMemoryAsyncToolRepository repository = InMemoryAsyncToolRepository.builder().build();
		Mono<ToolsPage> list = repository.list(null, null);
		Mono<McpAsyncTool> find = repository.find(null, "a");

		repository.add(tool("a"));

		StepVerifier.create(list)
			.assertNext(page -> assertThat(page.tools()).extracting(Tool::name).containsExactly("a"))
			.verifyComplete();
		StepVerifier.create(find).expectNextCount(1).verifyComplete();
		StepVerifier.create(repository.find(null, "nope")).verifyComplete();
	}

	@Test
	void toolRepositoryBroadcastsChangesAndRejectsDuplicates() {
		McpChangeFeed changes = new McpChangeFeed();
		InMemoryAsyncToolRepository repository = InMemoryAsyncToolRepository.builder()
			.changeBroadcaster(changes)
			.build();

		StepVerifier.create(changes.changes().take(2))
			.then(() -> repository.add(tool("a")))
			.expectNext(new ServerChange.ToolsListChanged())
			.then(() -> repository.remove("a"))
			.expectNext(new ServerChange.ToolsListChanged())
			.verifyComplete();

		repository.add(tool("a"));
		assertThatThrownBy(() -> repository.add(tool("a"))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("a");
	}

	@Test
	void promptRepositoryListsAndGetsPrompts() {
		McpServer server = server(PromptsFeature.ofAsync(
				InMemoryAsyncPromptRepository.builder().prompts(List.of(prompt("review"), prompt("greet"))).build()));

		assertThat(names(call(server, "prompts/list"), "prompts")).containsExactly("greet", "review");
		assertThat((List<?>) result(call(server, "prompts/get", "name", "greet")).get("messages")).hasSize(1);
		assertThat(call(server, "prompts/get", "name", "nope").error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void promptRepositoryBroadcastsChanges() {
		McpChangeFeed changes = new McpChangeFeed();
		InMemoryAsyncPromptRepository repository = InMemoryAsyncPromptRepository.builder()
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
	@SuppressWarnings("unchecked")
	void promptCompletionIsServedWhenSupported() {
		InMemoryAsyncPromptRepository repository = InMemoryAsyncPromptRepository.builder()
			.prompt(prompt("greet").withCompletionHandler((ctx, request) -> Mono
				.just(CompleteResult.of(new CompleteResult.Completion(List.of("java", "kotlin"))))))
			.supportsCompletion(true)
			.build();
		McpServer server = server(PromptsFeature.ofAsync(repository));

		JSONRPCResponse response = call(server, "completion/complete", "ref",
				Map.of("type", "ref/prompt", "name", "greet"), "argument", Map.of("name", "language", "value", ""));

		Map<String, Object> completion = (Map<String, Object>) result(response).get("completion");
		assertThat((List<String>) completion.get("values")).containsExactly("java", "kotlin");
	}

	@Test
	void promptCompletionIsNotServedByDefault() {
		McpServer server = server(PromptsFeature.ofAsync(InMemoryAsyncPromptRepository.builder()
			.prompt(prompt("greet").withCompletionHandler(
					(ctx, request) -> Mono.just(CompleteResult.of(new CompleteResult.Completion(List.of("java"))))))
			.build()));

		JSONRPCResponse response = call(server, "completion/complete", "ref",
				Map.of("type", "ref/prompt", "name", "greet"), "argument", Map.of("name", "language", "value", ""));

		assertThat(response.error()).isNotNull();
	}

	private static McpAsyncResource reader(String prefix) {
		return McpAsyncResource.of((ctx,
				request) -> Mono.just(McpAsyncResponse.result(ReadResourceResult
					.builder(List.of(TextResourceContents.builder(request.uri(), prefix + request.uri()).build()))
					.build())));
	}

	private static McpAsyncResourceTemplate template(String uriTemplate) {
		return McpAsyncResourceTemplate.of(ResourceTemplate.builder(uriTemplate, uriTemplate).build());
	}

	@SuppressWarnings("unchecked")
	private static String readText(McpServer server, String uri) {
		Map<String, Object> read = result(call(server, "resources/read", "uri", uri));
		return (String) ((List<Map<String, Object>>) read.get("contents")).get(0).get("text");
	}

	@Test
	@SuppressWarnings("unchecked")
	void resourceRepositoryListsAndReadsStaticAndTemplatedUris() {
		McpServer server = server(ResourcesFeature.ofAsync(InMemoryAsyncResourceRepository.builder()
			.resource(Resource.builder("test://items/special", "special").build(), reader("static:"))
			.template(template("test://items/{id}"), reader("item:"))
			.build()));

		List<Map<String, Object>> resources = (List<Map<String, Object>>) result(call(server, "resources/list"))
			.get("resources");
		assertThat(resources).extracting(resource -> resource.get("uri")).containsExactly("test://items/special");
		List<Map<String, Object>> templates = (List<Map<String, Object>>) result(
				call(server, "resources/templates/list"))
			.get("resourceTemplates");
		assertThat(templates).extracting(template -> template.get("uriTemplate")).containsExactly("test://items/{id}");
		// An exact resource takes precedence over a template matching the same URI.
		assertThat(readText(server, "test://items/special")).isEqualTo("static:test://items/special");
		assertThat(readText(server, "test://items/42")).isEqualTo("item:test://items/42");
		assertThat(call(server, "resources/read", "uri", "test://nope").error().code())
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void resourceRepositoryReadsTheCatalogWhenSubscribed() {
		InMemoryAsyncResourceRepository repository = InMemoryAsyncResourceRepository.builder().build();
		Mono<McpAsyncResource> find = repository.find(null, "test://items/42");
		Mono<McpAsyncResourceTemplate> findTemplate = repository.findTemplate(null, "test://items/{id}");

		repository.addTemplate(template("test://items/{id}"), reader(""));

		StepVerifier.create(find).expectNextCount(1).verifyComplete();
		StepVerifier.create(findTemplate).expectNextCount(1).verifyComplete();
		StepVerifier.create(repository.find(null, "test://nope")).verifyComplete();
		StepVerifier.create(repository.findTemplate(null, "test://items/42")).verifyComplete();
		StepVerifier.create(repository.listTemplates(null, "stale")).expectError(McpException.class).verify();
	}

	@Test
	void resourceRepositoryBroadcastsChanges() {
		McpChangeFeed changes = new McpChangeFeed();
		InMemoryAsyncResourceRepository repository = InMemoryAsyncResourceRepository.builder()
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
		assertThat(repository.removeResource("test://a")).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void templateCompletionIsServedWhenSupported() {
		McpServer server = server(ResourcesFeature.ofAsync(InMemoryAsyncResourceRepository.builder()
			.template(template("test://items/{id}").withCompletionHandler(
					(ctx, request) -> Mono.just(CompleteResult.of(new CompleteResult.Completion(List.of("1", "2"))))),
					reader(""))
			.supportsCompletion(true)
			.build()));

		JSONRPCResponse response = call(server, "completion/complete", "ref",
				Map.of("type", "ref/resource", "uri", "test://items/{id}"), "argument",
				Map.of("name", "id", "value", ""));

		Map<String, Object> completion = (Map<String, Object>) result(response).get("completion");
		assertThat((List<String>) completion.get("values")).containsExactly("1", "2");
	}

}
