/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
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
import io.modelcontextprotocol.modern.server.InputResponses;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncPrompt;
import io.modelcontextprotocol.modern.server.feature.McpSyncPromptRepository;
import io.modelcontextprotocol.modern.server.feature.McpSyncResource;
import io.modelcontextprotocol.modern.server.feature.McpSyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.PromptsFeature;
import io.modelcontextprotocol.modern.server.feature.PromptsPage;
import io.modelcontextprotocol.modern.server.feature.ResourceTemplatesPage;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesPage;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.util.ToolsUtils;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

/**
 * A modern server over Streamable HTTP in an embedded Tomcat, with the primitives the
 * client integration tests use:
 * <ul>
 * <li>tools, listed two per page: {@code echo}, {@code add}, {@code region} (mirrored
 * into {@code Mcp-Param-Region}), {@code progress}, {@code confirm} (one elicitation,
 * with {@code requestState}), {@code profile} (two rounds of elicitation),
 * {@code register} (form and URL elicitation in one round), {@code slow},
 * {@code change_tools} (broadcasts a tools change), {@code failing} (an error
 * result);</li>
 * <li>the resource {@code test://static}, the template {@code test://items/{id}}, and
 * {@code test://secret}, read after an elicitation;</li>
 * <li>the prompt {@code greeting} with the argument {@code name};</li>
 * <li>completions of {@code name} from {@code alice}, {@code albert}, {@code bob};</li>
 * <li>listen streams for tools, prompts and resource changes.</li>
 * </ul>
 */
final class IntegrationTestServer implements AutoCloseable {

	static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	static final int TOOLS_PER_PAGE = 2;

	private static final ClientCapabilities ELICITATION = ClientCapabilities.builder()
		.elicitation(new ClientCapabilities.Elicitation(new ClientCapabilities.Elicitation.Form(), null))
		.build();

	private static final ClientCapabilities FORM_AND_URL_ELICITATION = ClientCapabilities.builder()
		.elicitation(new ClientCapabilities.Elicitation(new ClientCapabilities.Elicitation.Form(),
				new ClientCapabilities.Elicitation.Url()))
		.build();

	private final TestChangePublisher changes = new TestChangePublisher();

	private final Tomcat tomcat;

	private final String endpoint;

	private IntegrationTestServer() {
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("integration-test-server", "1.0.0").build())
			.instructions("Use the tools.")
			.jsonMapper(JSON_MAPPER)
			.feature(ToolsFeature.ofSync(new Tools(this.changes)))
			.feature(ResourcesFeature.ofSync(new Resources()))
			.feature(PromptsFeature.ofSync(new Prompts()))
			.subscriptions(this.changes)
			.build();
		HttpServletMcpTransport servlet = HttpServletMcpTransport.builder(server)
			.jsonMapper(JSON_MAPPER)
			.endpoint("/mcp")
			// A failing keep-alive write is how the server notices a closed listen
			// stream.
			.keepAliveInterval(Duration.ofMillis(100))
			.build();
		int port = TomcatTestUtil.findAvailablePort();
		this.endpoint = "http://localhost:" + port + "/mcp";
		this.tomcat = TomcatTestUtil.createTomcatServer("", port, servlet);
	}

	static IntegrationTestServer start() {
		IntegrationTestServer server = new IntegrationTestServer();
		try {
			server.tomcat.start();
		}
		catch (LifecycleException ex) {
			throw new IllegalStateException(ex);
		}
		return server;
	}

	String endpoint() {
		return this.endpoint;
	}

	TestChangePublisher changes() {
		return this.changes;
	}

	@Override
	public void close() throws LifecycleException {
		this.tomcat.stop();
		this.tomcat.destroy();
	}

	static String text(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	private static CallToolResult textResult(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	private static ElicitFormRequest askFor(String message, String field, String type) {
		return ElicitFormRequest
			.builder(message, Map.of("type", "object", "properties", Map.of(field, Map.of("type", type)), "required",
					List.of(field)))
			.build();
	}

	private static Map<String, Object> accepted(Map<String, Object> inputResponses, String key) {
		ElicitResult result = InputResponses.get(inputResponses, key, ElicitResult.class, JSON_MAPPER);
		return result != null && result.action() == ElicitResult.Action.ACCEPT ? result.content() : null;
	}

	private static final class Tools implements McpSyncToolRepository {

		private final Map<String, McpSyncTool> tools = new LinkedHashMap<>();

		Tools(TestChangePublisher changes) {
			add(Tool.builder("echo", JSON_MAPPER, """
					{"type": "object", "properties": {"text": {"type": "string"}}}
					""").build(), (ctx, request) -> textResult(String.valueOf(request.arguments().get("text"))));
			add(Tool.builder("add", JSON_MAPPER, """
					{"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}}}
					""").build(), (ctx, request) -> {
				int a = ((Number) request.arguments().get("a")).intValue();
				int b = ((Number) request.arguments().get("b")).intValue();
				return textResult(String.valueOf(a + b));
			});
			add(Tool.builder("region", JSON_MAPPER, """
					{"type": "object", "properties": {"region": {"type": "string", "x-mcp-header": "Region"}}}
					""").build(), (ctx, request) -> textResult("region:" + request.arguments().get("region")));
			this.tools.put("progress", McpSyncTool.of(tool("progress"),
					(ctx, request) -> McpSyncResponse.<CallToolOutcome>streaming(notifier -> {
						notifier.progress(1, 2.0, "half way");
						notifier.progress(2, 2.0, "done");
						return textResult("progressed");
					})));
			add(tool("confirm"), ELICITATION, (ctx, request) -> {
				Map<String, Object> answer = accepted(request.inputResponses(), "confirm");
				if (answer == null || !"confirm-state".equals(request.requestState())) {
					return InputRequiredResult.builder()
						.elicit("confirm", askFor("Go ahead?", "ok", "boolean"))
						.requestState("confirm-state")
						.build();
				}
				return textResult("confirmed:" + answer.get("ok"));
			});
			add(tool("profile"), ELICITATION, (ctx, request) -> {
				String state = request.requestState();
				Map<String, Object> name = accepted(request.inputResponses(), "name");
				if ("asked-name".equals(state) && name != null) {
					return InputRequiredResult.builder()
						.elicit("color", askFor("Favourite colour?", "color", "string"))
						.requestState("asked-color:" + name.get("name"))
						.build();
				}
				Map<String, Object> color = accepted(request.inputResponses(), "color");
				if (state != null && state.startsWith("asked-color:") && color != null) {
					return textResult(state.substring("asked-color:".length()) + " likes " + color.get("color"));
				}
				return InputRequiredResult.builder()
					.elicit("name", askFor("Your name?", "name", "string"))
					.requestState("asked-name")
					.build();
			});
			add(tool("register"), FORM_AND_URL_ELICITATION, (ctx, request) -> {
				Map<String, Object> name = accepted(request.inputResponses(), "name");
				ElicitResult consent = InputResponses.get(request.inputResponses(), "consent", ElicitResult.class,
						JSON_MAPPER);
				if (name == null || consent == null) {
					return InputRequiredResult.builder()
						.elicit("name", askFor("Your name?", "name", "string"))
						.elicitUrl("consent", "Accept the terms", "https://example.com/terms")
						.build();
				}
				return textResult(name.get("name") + " " + consent.action().name().toLowerCase());
			});
			add(tool("slow"), (ctx, request) -> {
				try {
					Thread.sleep(1000);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return textResult("slow");
			});
			add(tool("change_tools"), (ctx, request) -> {
				changes.broadcast(new ServerChange.ToolsListChanged());
				return textResult("changed");
			});
			add(tool("failing"),
					(ctx, request) -> CallToolResult.builder()
						.addContent(TextContent.builder("it failed").build())
						.isError(true)
						.build());
		}

		private static Tool tool(String name) {
			return Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build();
		}

		private void add(Tool tool, Handler handler) {
			add(tool, ClientCapabilities.NONE, handler);
		}

		private void add(Tool tool, ClientCapabilities required, Handler handler) {
			this.tools.put(tool.name(), McpSyncTool.of(tool, required,
					(ctx, request) -> McpSyncResponse.result(handler.handle(ctx, request))));
		}

		@Override
		public ToolsPage list(McpRequestContext ctx, String cursor) {
			List<Tool> all = this.tools.values().stream().map(McpSyncTool::definition).toList();
			int from = cursor == null ? 0 : Integer.parseInt(cursor);
			int to = Math.min(from + TOOLS_PER_PAGE, all.size());
			String next = to < all.size() ? String.valueOf(to) : null;
			return new ToolsPage(all.subList(from, to), next, null, null);
		}

		@Override
		public McpSyncTool find(McpRequestContext ctx, String name) {
			return this.tools.get(name);
		}

		interface Handler {

			CallToolOutcome handle(McpRequestContext ctx, CallToolRequest request);

		}

	}

	private static final class Resources implements McpSyncResourceRepository {

		@Override
		public ResourcesPage list(McpRequestContext ctx, String cursor) {
			return ResourcesPage.of(List.of(Resource.builder("test://static", "static").build(),
					Resource.builder("test://secret", "secret").build()));
		}

		@Override
		public ResourceTemplatesPage listTemplates(McpRequestContext ctx, String cursor) {
			return ResourceTemplatesPage.of(List.of(ResourceTemplate.builder("test://items/{id}", "item").build()));
		}

		@Override
		public McpSyncResource find(McpRequestContext ctx, String uri) {
			if (uri.equals("test://static")) {
				return McpSyncResource.of((c, request) -> McpSyncResponse.result(contents(uri, "static text")));
			}
			if (uri.startsWith("test://items/")) {
				return McpSyncResource.of((c, request) -> McpSyncResponse
					.result(contents(uri, "item " + uri.substring("test://items/".length()))));
			}
			if (uri.equals("test://secret")) {
				return McpSyncResource.of(ELICITATION, (c, request) -> {
					Map<String, Object> answer = accepted(request.inputResponses(), "password");
					if (answer == null) {
						return McpSyncResponse.result(InputRequiredResult.builder()
							.elicit("password", askFor("Password?", "password", "string"))
							.build());
					}
					return McpSyncResponse.result(contents(uri, "secret for " + answer.get("password")));
				});
			}
			return null;
		}

		@Override
		public boolean supportsSubscribe() {
			return true;
		}

		private static ReadResourceResult contents(String uri, String text) {
			return ReadResourceResult.builder(List.of(TextResourceContents.builder(uri, text).build())).build();
		}

	}

	private static final class Prompts implements McpSyncPromptRepository {

		private static final Prompt GREETING = Prompt.builder("greeting")
			.arguments(List.of(PromptArgument.builder("name").required(true).build()))
			.build();

		@Override
		public PromptsPage list(McpRequestContext ctx, String cursor) {
			return PromptsPage.of(List.of(GREETING));
		}

		@Override
		public McpSyncPrompt find(McpRequestContext ctx, String name) {
			if (!name.equals(GREETING.name())) {
				return null;
			}
			return McpSyncPrompt
				.of(GREETING,
						(c, request) -> McpSyncResponse
							.result(GetPromptResult.builder(
									List.of(PromptMessage
										.builder(Role.USER,
												TextContent.builder("Hello, " + request.arguments().get("name"))
													.build())
										.build()))
								.build()))
				.withCompletionHandler((c,
						request) -> CompleteResult.of(new CompleteResult.Completion(Stream.of("alice", "albert", "bob")
							.filter(n -> n.startsWith(request.argument().value()))
							.toList())));
		}

		@Override
		public boolean supportsCompletion() {
			return true;
		}

	}

}
