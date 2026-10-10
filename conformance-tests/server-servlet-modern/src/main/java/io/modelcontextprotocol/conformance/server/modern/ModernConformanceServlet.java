package io.modelcontextprotocol.conformance.server.modern;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.AudioContent;
import io.modelcontextprotocol.modern.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.ImageContent;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.McpSchema.PromptMessage;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.Role;
import io.modelcontextprotocol.modern.McpSchema.SamplingMessage;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.InputResponses;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncNotifier;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.CompletionsFeature;
import io.modelcontextprotocol.modern.server.feature.McpChangeBroadcaster;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.McpSyncPromptRepository;
import io.modelcontextprotocol.modern.server.feature.McpSyncResourceRepository;
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
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conformance server for the 2026-07-28 (stateless) revision, built on the
 * {@code io.modelcontextprotocol.modern} server.
 */
public class ModernConformanceServlet {

	private static final Logger logger = LoggerFactory.getLogger(ModernConformanceServlet.class);

	private static final int DEFAULT_PORT = 8081;

	private static final String MCP_ENDPOINT = "/mcp";

	private static final Map<String, Object> EMPTY_JSON_SCHEMA = Map.of("type", "object", "properties", Map.of());

	// Minimal 1x1 red pixel PNG (base64 encoded)
	private static final String RED_PIXEL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

	// Minimal WAV file (base64 encoded) - 1 sample at 8kHz
	private static final String MINIMAL_WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQAAAAA=";

	private static final String JSON_SCHEMA_2020_12_INPUT = """
			{
			  "$schema": "https://json-schema.org/draft/2020-12/schema",
			  "type": "object",
			  "$defs": {
			    "address": {
			      "$anchor": "addressDef",
			      "type": "object",
			      "properties": { "street": { "type": "string" }, "city": { "type": "string" } }
			    }
			  },
			  "properties": {
			    "name": { "type": "string" },
			    "address": { "$ref": "#/$defs/address" },
			    "contactMethod": { "type": "string", "enum": ["phone", "email"] },
			    "phone": { "type": "string" },
			    "email": { "type": "string" }
			  },
			  "allOf": [{ "anyOf": [{ "required": ["phone"] }, { "required": ["email"] }] }],
			  "if": { "properties": { "contactMethod": { "const": "phone" } }, "required": ["contactMethod"] },
			  "then": { "required": ["phone"] },
			  "else": { "required": ["email"] },
			  "additionalProperties": false
			}
			""";

	private static final Pattern TEMPLATE_URI = Pattern.compile("test://template/(.+)/data");

	private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

	public static void main(String[] args) throws Exception {
		int port = Integer.getInteger("port", DEFAULT_PORT);
		logger.info("Starting MCP Conformance Tests - Modern Servlet Server");

		McpChangeFeed changes = new McpChangeFeed();

		McpServer mcpServer = McpServer.builder()
			.serverInfo(Implementation.builder("mcp-modern-conformance-server", "1.0.0").build())
			.feature(ToolsFeature.ofSync(new ConformanceTools(changes)))
			.feature(ResourcesFeature.ofSync(new ConformanceResources()))
			.feature(PromptsFeature.ofSync(new ConformancePrompts()))
			.feature(CompletionsFeature
				.ofSync((ctx, request) -> CompleteResult.of(new CompleteResult.Completion(List.of()))))
			.subscriptions(changes)
			.build();

		HttpServletMcpTransport transport = HttpServletMcpTransport.builder(mcpServer)
			.endpoint(MCP_ENDPOINT)
			.httpHeaderValidator(DefaultServerTransportSecurityValidator.builder()
				.allowedOrigin("http://localhost:*")
				.allowedHost("localhost:*")
				.build())
			.build();

		Tomcat tomcat = createEmbeddedTomcat(transport, port);
		try {
			tomcat.start();
			logger.info("Server URL: http://localhost:{}{}", port, MCP_ENDPOINT);
			tomcat.getServer().await();
		}
		catch (LifecycleException e) {
			logger.error("Failed to start Tomcat server", e);
			throw e;
		}
		finally {
			transport.closeGracefully();
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				logger.error("Error during Tomcat shutdown", e);
			}
		}
	}

	private static Tomcat createEmbeddedTomcat(HttpServletMcpTransport transport, int port) {
		Tomcat tomcat = new Tomcat();
		tomcat.setPort(port);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		Context context = tomcat.addContext("", baseDir);
		Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(transport);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		var connector = tomcat.getConnector();
		connector.setAsyncTimeout(30000);
		return tomcat;
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	private static ElicitFormRequest elicitString(String message, String field) {
		return ElicitFormRequest
			.builder(message, Map.of("type", "object", "properties", Map.of(field, Map.of("type", "string")),
					"required", List.of(field)))
			.build();
	}

	private static ElicitFormRequest elicitConfirm() {
		return ElicitFormRequest
			.builder("Please confirm", Map.of("type", "object", "properties", Map.of("ok", Map.of("type", "boolean")),
					"required", List.of("ok")))
			.build();
	}

	private static CreateMessageRequest sample(String prompt, int maxTokens) {
		return CreateMessageRequest
			.builder(List.of(SamplingMessage.builder(Role.USER, TextContent.builder(prompt).build()).build()),
					maxTokens)
			.build();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ElicitResponse(String action, Map<String, Object> content) {
	}

	/**
	 * The accepted elicitation content in {@code inputResponses[key]}, or {@code null} if
	 * there is no response or it was not accepted.
	 */
	private static Map<String, Object> acceptedContent(Map<String, Object> inputResponses, String key) {
		return InputResponses.get(inputResponses, key, ElicitResponse.class, JSON)
			.filter(response -> "accept".equals(response.action()))
			.map(ElicitResponse::content)
			.orElse(null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> responseObject(Map<String, Object> inputResponses, String key) {
		return InputResponses.get(inputResponses, key, Map.class, JSON).orElse(null);
	}

	@FunctionalInterface
	private interface Handler<R, O extends Result> {

		McpSyncResponse<O> handle(McpRequestContext ctx, R request);

	}

	@FunctionalInterface
	private interface StreamingTool {

		CallToolResult run(McpRequestContext ctx, CallToolRequest request, McpSyncNotifier notifier);

	}

	private static <R, O extends Result> Handler<R, O> respond(BiFunction<McpRequestContext, R, O> fn) {
		return (ctx, request) -> McpSyncResponse.result(fn.apply(ctx, request));
	}

	private static Handler<CallToolRequest, CallToolOutcome> stream(StreamingTool tool) {
		return (ctx, request) -> McpSyncResponse.streaming(notifier -> tool.run(ctx, request, notifier));
	}

	private record ToolEntry(Tool tool, Handler<CallToolRequest, CallToolOutcome> handler) {
	}

	private static final class ConformanceTools implements McpSyncToolRepository {

		private final Map<String, ToolEntry> tools = new LinkedHashMap<>();

		ConformanceTools(McpChangeBroadcaster changes) {
			add("test_simple_text", "Returns simple text content for testing",
					respond((ctx, req) -> text("This is a simple text response for testing.")));
			add("test_image_content", "Returns image content for testing",
					respond((ctx, req) -> CallToolResult.builder()
						.addContent(ImageContent.builder(RED_PIXEL_PNG, "image/png").build())
						.build()));
			add("test_audio_content", "Returns audio content for testing",
					respond((ctx, req) -> CallToolResult.builder()
						.addContent(AudioContent.builder(MINIMAL_WAV, "audio/wav").build())
						.build()));
			add("test_embedded_resource", "Returns embedded resource content for testing",
					respond((ctx,
							req) -> CallToolResult.builder()
								.addContent(
										EmbeddedResource
											.builder(TextResourceContents
												.builder("test://embedded-resource",
														"This is an embedded resource content.")
												.mimeType("text/plain")
												.build())
											.build())
								.build()));
			add("test_multiple_content_types", "Returns multiple content types for testing",
					respond((ctx,
							req) -> CallToolResult.builder()
								.addContent(TextContent.builder("Multiple content types test:").build())
								.addContent(ImageContent.builder(RED_PIXEL_PNG, "image/png").build())
								.addContent(
										EmbeddedResource
											.builder(TextResourceContents
												.builder("test://mixed-content-resource",
														"{\"test\":\"data\",\"value\":123}")
												.mimeType("application/json")
												.build())
											.build())
								.build()));
			add("test_error_handling", "Tool that returns an error for testing error handling",
					respond((ctx, req) -> CallToolResult.builder()
						.addContent(TextContent.builder("This tool intentionally returns an error for testing").build())
						.isError(true)
						.build()));
			add("test_tool_with_progress", "Tool that reports progress notifications", stream((ctx, req, notifier) -> {
				notifier.progress(0, 100.0, null);
				sleep(50);
				notifier.progress(50, 100.0, null);
				sleep(50);
				notifier.progress(100, 100.0, null);
				return text("Tool execution completed with progress");
			}));

			// SEP-1613 / SEP-2106 JSON Schema 2020-12 keyword preservation
			add(Tool.builder("json_schema_2020_12_tool", JSON, JSON_SCHEMA_2020_12_INPUT)
				.description("Tool with JSON Schema 2020-12 features (SEP-1613, SEP-2106)")
				.build(), respond((ctx, req) -> text("ok")));

			// SEP-2575 diagnostic tools
			add("test_missing_capability", "Test tool requiring sampling", (ctx, req) -> {
				if (!ctx.clientCapabilities().supportsSampling()) {
					throw McpException.missingClientCapability(ClientCapabilities.builder().sampling().build());
				}
				return McpSyncResponse.result(text("Success"));
			});
			add("test_streaming_elicitation", "Diagnostic tool validating response progress streams",
					stream((ctx, req, notifier) -> {
						notifier.progress(50, 100.0, null);
						return text("Streaming complete");
					}));
			// Logging is deprecated and unsupported, so no log is ever sent; the suite
			// checks exactly that.
			add("test_logging_tool", "Diagnostic logging validator tool",
					stream((ctx, req, notifier) -> text("Logging evaluated")));
			add("test_trigger_tool_change", "Emits a tools list-changed notification", respond((ctx, req) -> {
				changes.broadcast(new ServerChange.ToolsListChanged());
				return text("Mutation triggered");
			}));
			add("test_trigger_prompt_change", "Emits a prompts list-changed notification", respond((ctx, req) -> {
				changes.broadcast(new ServerChange.PromptsListChanged());
				return text("Mutation triggered");
			}));

			// SEP-2322 MRTR tools
			add("test_input_required_result_elicitation", "MRTR: returns InputRequiredResult with elicitation request",
					respond((ctx, req) -> {
						Map<String, Object> content = acceptedContent(req.inputResponses(), "user_name");
						if (content == null || !(content.get("name") instanceof String name)) {
							return InputRequiredResult.builder()
								.elicit("user_name", elicitString("What is your name?", "name"))
								.build();
						}
						return text("Hello, " + name + "!");
					}));
			add("test_input_required_result_sampling", "MRTR: returns InputRequiredResult with sampling request",
					respond((ctx, req) -> {
						Map<String, Object> response = responseObject(req.inputResponses(), "capital_question");
						if (response == null) {
							return InputRequiredResult.builder()
								.createMessage("capital_question", sample("What is the capital of France?", 100))
								.build();
						}
						Object content = response.get("content");
						Object answer = content instanceof Map<?, ?> m ? m.get("text") : null;
						return text("Sampling result: " + answer);
					}));
			add("test_input_required_result_list_roots", "MRTR: returns InputRequiredResult with roots/list request",
					respond((ctx, req) -> {
						Map<String, Object> response = responseObject(req.inputResponses(), "client_roots");
						if (response == null || !(response.get("roots") instanceof List<?> roots)) {
							return InputRequiredResult.builder().listRoots("client_roots").build();
						}
						return text("Found " + roots.size() + " root(s): " + roots);
					}));
			add("test_input_required_result_request_state", "MRTR: returns InputRequiredResult with requestState",
					respond((ctx, req) -> {
						Map<String, Object> content = acceptedContent(req.inputResponses(), "confirm");
						if ("request-state".equals(req.requestState()) && content != null
								&& Boolean.TRUE.equals(content.get("ok"))) {
							return text("state-ok: requestState validated");
						}
						return InputRequiredResult.builder()
							.elicit("confirm", elicitConfirm())
							.requestState("request-state")
							.build();
					}));
			add("test_input_required_result_multiple_inputs",
					"MRTR: returns InputRequiredResult with multiple input requests", respond((ctx, req) -> {
						Map<String, Object> user = acceptedContent(req.inputResponses(), "user_name");
						Map<String, Object> greeting = responseObject(req.inputResponses(), "greeting");
						Map<String, Object> roots = responseObject(req.inputResponses(), "client_roots");
						if ("multiple-inputs".equals(req.requestState()) && user != null && greeting != null
								&& roots != null) {
							return text("Name: " + user.get("name") + "; Roots: "
									+ (roots.get("roots") instanceof List<?> l ? l.size() : 0));
						}
						return InputRequiredResult.builder()
							.elicit("user_name", elicitString("What is your name?", "name"))
							.createMessage("greeting", sample("Generate a greeting", 50))
							.listRoots("client_roots")
							.requestState("multiple-inputs")
							.build();
					}));
			add("test_input_required_result_multi_round", "MRTR: multi-round InputRequiredResult workflow",
					respond((ctx, req) -> {
						String state = req.requestState();
						if (state != null && state.startsWith("round-1")) {
							Map<String, Object> step1 = acceptedContent(req.inputResponses(), "step1");
							if (step1 != null && step1.get("name") instanceof String name) {
								return InputRequiredResult.builder()
									.elicit("step2", elicitString("Step 2: What is your favorite color?", "color"))
									.requestState("round-2:" + name)
									.build();
							}
						}
						if (state != null && state.startsWith("round-2:")) {
							Map<String, Object> step2 = acceptedContent(req.inputResponses(), "step2");
							if (step2 != null && step2.get("color") instanceof String color) {
								return text("Multi-round complete for " + state.substring("round-2:".length())
										+ " who likes " + color);
							}
						}
						return InputRequiredResult.builder()
							.elicit("step1", elicitString("Step 1: What is your name?", "name"))
							.requestState("round-1")
							.build();
					}));
			// Integrity protection comes from the server's RequestStateCodec: a tampered
			// requestState is rejected before this handler runs.
			add("test_input_required_result_tampered_state", "MRTR: HMAC-signed requestState integrity test",
					respond((ctx, req) -> {
						if ("tamper-test".equals(req.requestState())
								&& responseObject(req.inputResponses(), "confirm") != null) {
							return text("integrity-ok: state verified");
						}
						return InputRequiredResult.builder()
							.elicit("confirm", elicitConfirm())
							.requestState("tamper-test")
							.build();
					}));
			add("test_input_required_result_capabilities", "MRTR: respects client capabilities in inputRequests",
					respond((ctx, req) -> {
						if (req.inputResponses() != null && !req.inputResponses().isEmpty()) {
							return text("capabilities-ok: received " + String.join(",", req.inputResponses().keySet()));
						}
						ClientCapabilities caps = ctx.clientCapabilities();
						if (!caps.supportsElicitationForm() && !caps.supportsSampling()) {
							return text("No supported capabilities declared");
						}
						InputRequiredResult.Builder builder = InputRequiredResult.builder()
							.requestState("capabilities-test:" + UUID.randomUUID());
						if (caps.supportsElicitationForm()) {
							builder.elicit("elicit_input", elicitString("Elicitation input", "value"));
						}
						if (caps.supportsSampling()) {
							builder.createMessage("sample_input", sample("Sample request", 50));
						}
						return builder.build();
					}));
		}

		private void add(String name, String description, Handler<CallToolRequest, CallToolOutcome> handler) {
			add(Tool.builder(name, EMPTY_JSON_SCHEMA).description(description).build(), handler);
		}

		private void add(Tool tool, Handler<CallToolRequest, CallToolOutcome> handler) {
			this.tools.put(tool.name(), new ToolEntry(tool, handler));
		}

		@Override
		public ToolsPage list(McpRequestContext ctx, String cursor) {
			return ToolsPage.of(this.tools.values().stream().map(ToolEntry::tool).toList());
		}

		@Override
		public Tool find(McpRequestContext ctx, String name) {
			ToolEntry entry = this.tools.get(name);
			return entry != null ? entry.tool() : null;
		}

		@Override
		public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
			ToolEntry entry = this.tools.get(request.name());
			if (entry == null) {
				throw McpException.invalidParams("Unknown tool: " + request.name());
			}
			return entry.handler().handle(ctx, request);
		}

		private static void sleep(long millis) {
			try {
				Thread.sleep(millis);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

	}

	private static final class ConformanceResources implements McpSyncResourceRepository {

		private static final List<Resource> RESOURCES = List.of(
				Resource.builder("test://static-text", "Static Text Resource")
					.description("A static text resource for testing")
					.mimeType("text/plain")
					.build(),
				Resource.builder("test://static-binary", "Static Binary Resource")
					.description("A static binary resource for testing")
					.mimeType("image/png")
					.build(),
				Resource.builder("test://watched-resource", "Watched Resource")
					.description("A resource that can be subscribed to for updates")
					.mimeType("text/plain")
					.build());

		@Override
		public ResourcesPage list(McpRequestContext ctx, String cursor) {
			return ResourcesPage.of(RESOURCES);
		}

		@Override
		public ResourceTemplatesPage listTemplates(McpRequestContext ctx, String cursor) {
			return ResourceTemplatesPage
				.of(List.of(ResourceTemplate.builder("test://template/{id}/data", "Template Resource")
					.description("A resource template for testing parameter substitution")
					.mimeType("application/json")
					.build()));
		}

		@Override
		public McpSyncResponse<ReadResourceOutcome> read(McpRequestContext ctx, ReadResourceRequest request) {
			String uri = request.uri();
			ReadResourceResult result = switch (uri) {
				case "test://static-text" -> textResource(uri, "This is the content of the static text resource.");
				case "test://watched-resource" -> textResource(uri, "This is a watched resource content.");
				case "test://static-binary" -> ReadResourceResult
					.builder(List.of(BlobResourceContents.builder(uri, RED_PIXEL_PNG).mimeType("image/png").build()))
					.build();
				default -> templateResource(uri);
			};
			if (result == null) {
				throw McpException.invalidParams("Unknown resource: " + uri, Map.of("uri", uri));
			}
			return McpSyncResponse.result(result);
		}

		@Override
		public boolean supportsSubscribe() {
			return true;
		}

		private static ReadResourceResult textResource(String uri, String text) {
			return ReadResourceResult
				.builder(List.of(TextResourceContents.builder(uri, text).mimeType("text/plain").build()))
				.build();
		}

		private static ReadResourceResult templateResource(String uri) {
			Matcher matcher = TEMPLATE_URI.matcher(uri);
			if (!matcher.matches()) {
				return null;
			}
			String id = matcher.group(1);
			String json = String.format("{\"id\":\"%s\",\"templateTest\":true,\"data\":\"Data for ID: %s\"}", id, id);
			return ReadResourceResult
				.builder(List.of(TextResourceContents.builder(uri, json).mimeType("application/json").build()))
				.build();
		}

	}

	private record PromptEntry(Prompt prompt, Handler<GetPromptRequest, GetPromptOutcome> handler) {
	}

	private static final class ConformancePrompts implements McpSyncPromptRepository {

		private final Map<String, PromptEntry> prompts = new LinkedHashMap<>();

		ConformancePrompts() {
			add(Prompt.builder("test_simple_prompt").description("A simple prompt for testing").build(),
					respond((ctx,
							req) -> messages(PromptMessage
								.builder(Role.USER, TextContent.builder("This is a simple prompt for testing.").build())
								.build())));
			add(Prompt.builder("test_prompt_with_arguments")
				.description("A prompt with arguments for testing")
				.arguments(List.of(
						PromptArgument.builder("arg1").description("First test argument").required(true).build(),
						PromptArgument.builder("arg2").description("Second test argument").required(true).build()))
				.build(), respond((ctx, req) -> {
					Map<String, String> args = req.arguments() == null ? Map.of() : req.arguments();
					String text = String.format("Prompt with arguments: arg1='%s', arg2='%s'", args.get("arg1"),
							args.get("arg2"));
					return messages(PromptMessage.builder(Role.USER, TextContent.builder(text).build()).build());
				}));
			add(Prompt.builder("test_prompt_with_embedded_resource")
				.description("A prompt with embedded resource for testing")
				.arguments(List.of(PromptArgument.builder("resourceUri")
					.description("URI of the resource to embed")
					.required(true)
					.build()))
				.build(), respond((ctx, req) -> {
					String resourceUri = req.arguments() == null ? null : req.arguments().get("resourceUri");
					EmbeddedResource resource = EmbeddedResource
						.builder(TextResourceContents.builder(resourceUri, "Embedded resource content for testing.")
							.mimeType("text/plain")
							.build())
						.build();
					return messages(PromptMessage.builder(Role.USER, resource).build(),
							PromptMessage
								.builder(Role.USER,
										TextContent.builder("Please process the embedded resource above.").build())
								.build());
				}));
			add(Prompt.builder("test_prompt_with_image").description("A prompt with image content for testing").build(),
					respond((ctx, req) -> messages(
							PromptMessage.builder(Role.USER, ImageContent.builder(RED_PIXEL_PNG, "image/png").build())
								.build(),
							PromptMessage
								.builder(Role.USER, TextContent.builder("Please analyze the image above.").build())
								.build())));
			add(Prompt.builder("test_input_required_result_prompt")
				.description("MRTR: prompt that requires elicitation input")
				.build(), respond((ctx, req) -> {
					Map<String, Object> content = acceptedContent(req.inputResponses(), "user_context");
					if (content == null || !(content.get("context") instanceof String context)) {
						return InputRequiredResult.builder()
							.elicit("user_context", elicitString("What context should the prompt use?", "context"))
							.build();
					}
					return messages(PromptMessage
						.builder(Role.USER, TextContent.builder("Prompt with context: " + context).build())
						.build());
				}));
		}

		private void add(Prompt prompt, Handler<GetPromptRequest, GetPromptOutcome> handler) {
			this.prompts.put(prompt.name(), new PromptEntry(prompt, handler));
		}

		private static GetPromptResult messages(PromptMessage... messages) {
			return GetPromptResult.builder(List.of(messages)).build();
		}

		@Override
		public PromptsPage list(McpRequestContext ctx, String cursor) {
			return PromptsPage.of(this.prompts.values().stream().map(PromptEntry::prompt).toList());
		}

		@Override
		public Prompt find(McpRequestContext ctx, String name) {
			PromptEntry entry = this.prompts.get(name);
			return entry != null ? entry.prompt() : null;
		}

		@Override
		public McpSyncResponse<GetPromptOutcome> get(McpRequestContext ctx, GetPromptRequest request) {
			PromptEntry entry = this.prompts.get(request.name());
			if (entry == null) {
				throw McpException.invalidParams("Unknown prompt: " + request.name());
			}
			return entry.handler().handle(ctx, request);
		}

	}

}
