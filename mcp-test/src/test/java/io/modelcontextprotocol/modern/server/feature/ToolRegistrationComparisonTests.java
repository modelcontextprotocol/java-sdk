/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncNotifier;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The same three tools registered two ways (#1200): today's repository, feature and
 * server, and {@link McpServerTool} on the server builder. Both servers must answer every
 * request alike.
 */
class ToolRegistrationComparisonTests {

	// What the tools are and do. Identical for both registrations.

	private static final Tool GREET = Tool
		.builder("greet", Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")), "required",
				List.of("name")))
		.description("Greets someone by name")
		.build();

	private static final Tool COUNT = Tool.builder("count", ToolsUtils.EMPTY_JSON_SCHEMA)
		.description("Counts to three, reporting progress")
		.build();

	private static final Tool ASK_NAME = Tool.builder("ask_name", ToolsUtils.EMPTY_JSON_SCHEMA)
		.description("Asks the user for their name")
		.build();

	private static CallToolResult greet(CallToolRequest request) {
		return text("Hello, " + request.arguments().get("name") + "!");
	}

	private static CallToolResult count(McpSyncNotifier notifier) {
		for (int i = 1; i <= 3; i++) {
			notifier.progress(i, 3.0, null);
		}
		return text("Counted to three");
	}

	private static CallToolOutcome askName(CallToolRequest request) {
		Object answer = request.inputResponses() != null ? request.inputResponses().get("name") : null;
		if (answer instanceof Map<?, ?> response && response.get("content") instanceof Map<?, ?> content) {
			return text("Hello, " + content.get("name") + "!");
		}
		return InputRequiredResult.builder()
			.elicit("name",
					ElicitFormRequest
						.builder("What is your name?",
								Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))))
						.build())
			.build();
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	// Today: implement a repository, wrap it in a feature, register the feature.

	/**
	 * A hand-written catalogue. It owns the name map, keeps list, find and call
	 * consistent, and must reject cursors it never issued.
	 */
	private static final class GreetingTools implements McpSyncToolRepository {

		private final Map<String, Tool> tools = new LinkedHashMap<>();

		GreetingTools() {
			for (Tool tool : List.of(GREET, COUNT, ASK_NAME)) {
				this.tools.put(tool.name(), tool);
			}
		}

		@Override
		public ToolsPage list(McpRequestContext ctx, String cursor) {
			if (cursor != null) {
				throw McpException.invalidParams("Invalid cursor");
			}
			return ToolsPage.of(List.copyOf(this.tools.values()));
		}

		@Override
		public Tool find(McpRequestContext ctx, String name) {
			return this.tools.get(name);
		}

		@Override
		public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
			return switch (request.name()) {
				case "greet" -> McpSyncResponse.result(greet(request));
				case "count" -> McpSyncResponse.streaming(notifier -> count(notifier));
				case "ask_name" -> McpSyncResponse.result(askName(request));
				default -> throw McpException.invalidParams("Unknown tool: " + request.name());
			};
		}

	}

	private static McpServer threeLayers() {
		return McpServer.builder().serverInfo(SERVER_INFO).feature(ToolsFeature.ofSync(new GreetingTools())).build();
	}

	// Proposed: one call per tool.

	private static McpServer shortcuts() {
		return McpServer.builder()
			.serverInfo(SERVER_INFO)
			.tool(McpServerTool.sync(GREET, (ctx, request) -> greet(request)))
			.tool(McpServerTool.syncStreaming(COUNT, (ctx, request, notifier) -> count(notifier)))
			.tool(McpServerTool.sync(ASK_NAME, (ctx, request) -> askName(request)))
			.build();
	}

	// Both servers answer alike.

	@Test
	void listsTheSameTools() {
		JSONRPCResponse response = assertSameResponse(new JSONRPCRequest("tools/list", 1, Map.of("_meta", meta())));

		List<?> tools = (List<?>) ((Map<?, ?>) response.result()).get("tools");
		List<Object> names = tools.stream().map(tool -> (Object) ((Map<?, ?>) tool).get("name")).toList();
		assertThat(names).containsExactly("greet", "count", "ask_name");
	}

	@Test
	void answersTheSameCall() {
		JSONRPCResponse response = assertSameResponse(call("greet", Map.of("arguments", Map.of("name", "Ada"))));

		assertThat(firstText(response)).isEqualTo("Hello, Ada!");
	}

	@Test
	void asyncHandlersAnswerTheSameCall() {
		McpServer async = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.tool(McpServerTool.async(GREET, (ctx, request) -> Mono.just(greet(request))))
			.build();
		JSONRPCRequest request = call("greet", Map.of("arguments", Map.of("name", "Ada")));

		assertThat(respond(async, request).block()).isEqualTo(respond(threeLayers(), request).block());
	}

	@Test
	void validatesArgumentsAgainstTheInputSchemaAlike() {
		JSONRPCResponse response = assertSameResponse(call("greet", Map.of("arguments", Map.of())));

		assertThat(((Map<?, ?>) response.result()).get("isError")).isEqualTo(true);
		assertThat(firstText(response)).startsWith("Invalid arguments");
	}

	@Test
	void rejectsAnUnknownToolAlike() {
		JSONRPCResponse response = assertSameResponse(call("nope", Map.of()));

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void rejectsACursorItNeverIssuedAlike() {
		JSONRPCRequest request = new JSONRPCRequest("tools/list", 1, Map.of("_meta", meta(), "cursor", "bogus"));

		assertThat(respond(shortcuts(), request).block().error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS)
			.isEqualTo(respond(threeLayers(), request).block().error().code());
	}

	@Test
	void asksForInputAndUsesTheAnswerAlike() {
		JSONRPCResponse first = assertSameResponse(call("ask_name", Map.of()));
		assertThat(((Map<?, ?>) first.result()).get("inputRequests")).isNotNull();

		JSONRPCResponse retry = assertSameResponse(call("ask_name", Map.of("inputResponses",
				Map.of("name", Map.of("action", "accept", "content", Map.of("name", "Ada"))))));
		assertThat(firstText(retry)).isEqualTo("Hello, Ada!");
	}

	@Test
	void streamsTheSameProgress() {
		JSONRPCRequest request = new JSONRPCRequest("tools/call", 1,
				Map.of("_meta", meta(MetaKeys.PROGRESS_TOKEN, "p1"), "name", "count"));

		List<JSONRPCMessage> expected = stream(threeLayers(), request);
		List<JSONRPCMessage> actual = stream(shortcuts(), request);

		assertThat(actual).isEqualTo(expected).hasSize(4);
	}

	// What only the shortcuts check, at registration rather than per request.

	@Test
	void aDuplicateToolNameFailsWhereItIsRegistered() {
		McpServer.Builder builder = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.tool(McpServerTool.sync(GREET, (ctx, request) -> greet(request)));

		assertThatIllegalArgumentException()
			.isThrownBy(() -> builder.tool(McpServerTool.sync(GREET, (ctx, request) -> text("again"))))
			.withMessage("Tool 'greet' is already registered");
	}

	@Test
	void anInvalidToolNameFailsWhereItIsRegistered() {
		Tool invalid = Tool.builder("has spaces", ToolsUtils.EMPTY_JSON_SCHEMA).build();

		assertThatIllegalArgumentException().isThrownBy(() -> McpServerTool.sync(invalid, (ctx, request) -> text("")));
	}

	@Test
	void shortcutsCannotBeMixedWithAnExplicitToolsFeature() {
		McpServer.Builder builder = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.tool(McpServerTool.sync(GREET, (ctx, request) -> greet(request)))
			.feature(ToolsFeature.ofSync(new GreetingTools()));

		assertThatIllegalStateException().isThrownBy(builder::build)
			.withMessageStartingWith(
					"Primitives registered with tool(...) cannot be combined with " + ToolsFeature.class.getName());
	}

	@Test
	void shortcutsCombineWithUnrelatedFeatures() {
		McpServer server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.tool(McpServerTool.sync(GREET, (ctx, request) -> greet(request)))
			.feature(CompletionsFeature
				.ofSync((ctx, request) -> CompleteResult.of(new CompleteResult.Completion(List.of()))))
			.build();

		assertThat(respond(server, call("greet", Map.of("arguments", Map.of("name", "Ada")))).block().error()).isNull();
	}

	private static JSONRPCRequest call(String name, Map<String, Object> params) {
		Map<String, Object> all = new LinkedHashMap<>(params);
		all.put("_meta", meta());
		all.put("name", name);
		return new JSONRPCRequest("tools/call", 1, all);
	}

	private static JSONRPCResponse assertSameResponse(JSONRPCRequest request) {
		JSONRPCResponse expected = respond(threeLayers(), request).block();
		JSONRPCResponse actual = respond(shortcuts(), request).block();
		assertThat(actual).isEqualTo(expected);
		return actual;
	}

	private static List<JSONRPCMessage> stream(McpServer server, JSONRPCRequest request) {
		McpTransportResponse response = server.handle(McpTransportContext.EMPTY, request).block();
		return ((McpTransportResponse.Streaming) response).messages().collectList().block();
	}

	private static String firstText(JSONRPCResponse response) {
		List<?> content = (List<?>) ((Map<?, ?>) response.result()).get("content");
		return (String) ((Map<?, ?>) content.get(0)).get("text");
	}

}
