package io.modelcontextprotocol.conformance.server.modern;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema.AudioContent;
import io.modelcontextprotocol.modern.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.ImageContent;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.McpSchema.PromptMessage;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.Role;
import io.modelcontextprotocol.modern.McpSchema.SamplingMessage;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.InputResponses;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpChangeBroadcaster;
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
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManager;
import io.modelcontextprotocol.util.McpUriTemplateManager;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conformance server for the 2026-07-28 (stateless) revision, built on the
 * {@code io.modelcontextprotocol.modern} server. Its tools, resources and prompts are
 * served from the SDK's in-memory repositories.
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

	private static final String X_MCP_HEADER_INPUT = """
			{
			  "type": "object",
			  "properties": {
			    "region": { "type": "string", "description": "mirrored into Mcp-Param-Region", "x-mcp-header": "Region" },
			    "level": { "type": "integer", "description": "non-mirrored argument" }
			  }
			}
			""";

	private static final String TEMPLATE_URI = "test://template/{id}/data";

	// Extracts {id} in the template's reader, which the resource repository only calls
	// for URIs that matched this template.
	private static final McpUriTemplateManager TEMPLATE_VARIABLES = new DefaultMcpUriTemplateManager(TEMPLATE_URI);

	private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

	public static void main(String[] args) throws Exception {
		int port = Integer.getInteger("port", DEFAULT_PORT);
		logger.info("Starting MCP Conformance Tests - Modern Servlet Server");

		McpChangeFeed changes = new McpChangeFeed();
		McpServer mcpServer = McpServer.builder()
			.serverInfo(Implementation.builder("mcp-modern-conformance-server", "1.0.0").build())
			.feature(ToolsFeature.ofSync(tools(changes)))
			.feature(ResourcesFeature.ofSync(resources()))
			.feature(PromptsFeature.ofSync(prompts()))
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

	private static McpSyncResponse<CallToolOutcome> text(String text) {
		return McpSyncResponse.result(CallToolResult.builder().addContent(TextContent.builder(text).build()).build());
	}

	private static CompleteResult completeFrom(List<String> candidates, CompleteRequest request) {
		String prefix = request.argument().value();
		return CompleteResult
			.of(new CompleteResult.Completion(candidates.stream().filter(value -> value.startsWith(prefix)).toList()));
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

	private static final ClientCapabilities.Elicitation FORM = new ClientCapabilities.Elicitation(
			new ClientCapabilities.Elicitation.Form(), null);

	private static final ClientCapabilities ELICITATION = ClientCapabilities.builder().elicitation(FORM).build();

	private static final ClientCapabilities SAMPLING = ClientCapabilities.builder().sampling().build();

	private static final ClientCapabilities ROOTS = ClientCapabilities.builder().roots().build();

	// Tools

	/**
	 * The tools of the conformance scenarios. {@code test_trigger_*} broadcast list
	 * changes without changing the catalog, so that the listings other scenarios check do
	 * not depend on the order in which scenarios run.
	 */
	private static InMemorySyncToolRepository tools(McpChangeBroadcaster changes) {
		return InMemorySyncToolRepository.builder()
			.tool(McpSyncTool.of(Tool.builder("test_simple_text", EMPTY_JSON_SCHEMA)
				.description("Returns simple text content for testing")
				.build(), (ctx, req) -> text("This is a simple text response for testing.")))
			.tool(McpSyncTool.of(
					Tool.builder("test_image_content", EMPTY_JSON_SCHEMA)
						.description("Returns image content for testing")
						.build(),
					(ctx, req) -> McpSyncResponse.result(CallToolResult.builder()
						.addContent(ImageContent.builder(RED_PIXEL_PNG, "image/png").build())
						.build())))
			.tool(McpSyncTool.of(
					Tool.builder("test_audio_content", EMPTY_JSON_SCHEMA)
						.description("Returns audio content for testing")
						.build(),
					(ctx, req) -> McpSyncResponse.result(CallToolResult.builder()
						.addContent(AudioContent.builder(MINIMAL_WAV, "audio/wav").build())
						.build())))
			.tool(McpSyncTool
				.of(Tool.builder("test_embedded_resource", EMPTY_JSON_SCHEMA)
					.description("Returns embedded resource content for testing")
					.build(),
						(ctx, req) -> McpSyncResponse.result(CallToolResult.builder()
							.addContent(EmbeddedResource
								.builder(TextResourceContents
									.builder("test://embedded-resource", "This is an embedded resource content.")
									.mimeType("text/plain")
									.build())
								.build())
							.build())))
			.tool(McpSyncTool
				.of(Tool.builder("test_multiple_content_types", EMPTY_JSON_SCHEMA)
					.description("Returns multiple content types for testing")
					.build(),
						(ctx, req) -> McpSyncResponse.result(CallToolResult.builder()
							.addContent(TextContent.builder("Multiple content types test:").build())
							.addContent(ImageContent.builder(RED_PIXEL_PNG, "image/png").build())
							.addContent(EmbeddedResource
								.builder(TextResourceContents
									.builder("test://mixed-content-resource", "{\"test\":\"data\",\"value\":123}")
									.mimeType("application/json")
									.build())
								.build())
							.build())))
			.tool(McpSyncTool.of(
					Tool.builder("test_error_handling", EMPTY_JSON_SCHEMA)
						.description("Tool that returns an error for testing error handling")
						.build(),
					(ctx, req) -> McpSyncResponse.result(CallToolResult.builder()
						.addContent(TextContent.builder("This tool intentionally returns an error for testing").build())
						.isError(true)
						.build())))
			.tool(McpSyncTool.of(Tool.builder("test_tool_with_progress", EMPTY_JSON_SCHEMA)
				.description("Tool that reports progress notifications")
				.build(), (ctx, req) -> McpSyncResponse.streaming(notifier -> {
					notifier.progress(0, 100.0, null);
					sleep(50);
					notifier.progress(50, 100.0, null);
					sleep(50);
					notifier.progress(100, 100.0, null);
					return CallToolResult.builder()
						.addContent(TextContent.builder("Tool execution completed with progress").build())
						.build();
				})))

			// SEP-1613 / SEP-2106 JSON Schema 2020-12 keyword preservation
			.tool(McpSyncTool.of(Tool.builder("json_schema_2020_12_tool", JSON, JSON_SCHEMA_2020_12_INPUT)
				.description("Tool with JSON Schema 2020-12 features (SEP-1613, SEP-2106)")
				.build(), (ctx, req) -> text("ok")))

			// SEP-2243 custom headers: the scenario exercises the first tool with an
			// x-mcp-header annotation, so this must stay the only annotated tool.
			.tool(McpSyncTool.of(Tool.builder("test_x_mcp_header", JSON, X_MCP_HEADER_INPUT)
				.description("Tests SEP-2243 Mcp-Param-* server-side validation")
				.build(), (ctx, req) -> {
					Object region = req.arguments() != null ? req.arguments().get("region") : null;
					return text("region=" + (region != null ? region : "<none>"));
				}))

			// SEP-2575 diagnostic tools
			.tool(McpSyncTool.of(Tool.builder("test_missing_capability", EMPTY_JSON_SCHEMA)
				.description("Test tool requiring sampling")
				.build(), SAMPLING, (ctx, req) -> text("Success")))
			.tool(McpSyncTool.of(Tool.builder("test_streaming_elicitation", EMPTY_JSON_SCHEMA)
				.description("Diagnostic tool validating response progress streams")
				.build(), (ctx, req) -> McpSyncResponse.streaming(notifier -> {
					notifier.progress(50, 100.0, null);
					return CallToolResult.builder()
						.addContent(TextContent.builder("Streaming complete").build())
						.build();
				})))
			// Logging is deprecated and unsupported, so no log is ever sent; the suite
			// checks exactly that.
			.tool(McpSyncTool.of(Tool.builder("test_logging_tool",
					EMPTY_JSON_SCHEMA)
				.description("Diagnostic logging validator tool")
				.build(),
					(ctx, req) -> McpSyncResponse.streaming(notifier -> CallToolResult.builder()
						.addContent(TextContent.builder("Logging evaluated").build())
						.build())))
			.tool(McpSyncTool.of(Tool.builder("test_trigger_tool_change", EMPTY_JSON_SCHEMA)
				.description("Emits a tools list-changed notification")
				.build(), (ctx, req) -> {
					changes.broadcast(new ServerChange.ToolsListChanged());
					return text("Mutation triggered");
				}))
			.tool(McpSyncTool.of(Tool.builder("test_trigger_prompt_change", EMPTY_JSON_SCHEMA)
				.description("Emits a prompts list-changed notification")
				.build(), (ctx, req) -> {
					changes.broadcast(new ServerChange.PromptsListChanged());
					return text("Mutation triggered");
				}))

			// SEP-2322 MRTR tools
			.tool(McpSyncTool.of(Tool.builder("test_input_required_result_elicitation", EMPTY_JSON_SCHEMA)
				.description("MRTR: returns InputRequiredResult with elicitation request")
				.build(), ELICITATION, (ctx, req) -> {
					Map<String, Object> content = acceptedContent(req.inputResponses(), "user_name");
					if (content == null || !(content.get("name") instanceof String name)) {
						return McpSyncResponse.result(InputRequiredResult.builder()
							.elicit("user_name", elicitString("What is your name?", "name"))
							.build());
					}
					return text("Hello, " + name + "!");
				}))
			.tool(McpSyncTool.of(Tool.builder("test_input_required_result_sampling", EMPTY_JSON_SCHEMA)
				.description("MRTR: returns InputRequiredResult with sampling request")
				.build(), SAMPLING, (ctx, req) -> {
					Map<String, Object> response = responseObject(req.inputResponses(), "capital_question");
					if (response == null) {
						return McpSyncResponse.result(InputRequiredResult.builder()
							.createMessage("capital_question", sample("What is the capital of France?", 100))
							.build());
					}
					Object content = response.get("content");
					Object answer = content instanceof Map<?, ?> m ? m.get("text") : null;
					return text("Sampling result: " + answer);
				}))
			.tool(McpSyncTool.of(Tool.builder("test_input_required_result_list_roots", EMPTY_JSON_SCHEMA)
				.description("MRTR: returns InputRequiredResult with roots/list request")
				.build(), ROOTS, (ctx, req) -> {
					Map<String, Object> response = responseObject(req.inputResponses(), "client_roots");
					if (response == null || !(response.get("roots") instanceof List<?> roots)) {
						return McpSyncResponse.result(InputRequiredResult.builder().listRoots("client_roots").build());
					}
					return text("Found " + roots.size() + " root(s): " + roots);
				}))
			.tool(McpSyncTool.of(Tool.builder("test_input_required_result_request_state", EMPTY_JSON_SCHEMA)
				.description("MRTR: returns InputRequiredResult with requestState")
				.build(), ELICITATION, (ctx, req) -> {
					Map<String, Object> content = acceptedContent(req.inputResponses(), "confirm");
					if ("request-state".equals(req.requestState()) && content != null
							&& Boolean.TRUE.equals(content.get("ok"))) {
						return text("state-ok: requestState validated");
					}
					return McpSyncResponse.result(InputRequiredResult.builder()
						.elicit("confirm", elicitConfirm())
						.requestState("request-state")
						.build());
				}))
			.tool(McpSyncTool.of(
					Tool.builder("test_input_required_result_multiple_inputs", EMPTY_JSON_SCHEMA)
						.description("MRTR: returns InputRequiredResult with multiple input requests")
						.build(),
					ClientCapabilities.builder().elicitation(FORM).sampling().roots().build(), (ctx, req) -> {
						Map<String, Object> user = acceptedContent(req.inputResponses(), "user_name");
						Map<String, Object> greeting = responseObject(req.inputResponses(), "greeting");
						Map<String, Object> roots = responseObject(req.inputResponses(), "client_roots");
						if ("multiple-inputs".equals(req.requestState()) && user != null && greeting != null
								&& roots != null) {
							return text("Name: " + user.get("name") + "; Roots: "
									+ (roots.get("roots") instanceof List<?> l ? l.size() : 0));
						}
						return McpSyncResponse.result(InputRequiredResult.builder()
							.elicit("user_name", elicitString("What is your name?", "name"))
							.createMessage("greeting", sample("Generate a greeting", 50))
							.listRoots("client_roots")
							.requestState("multiple-inputs")
							.build());
					}))
			.tool(McpSyncTool.of(Tool.builder("test_input_required_result_multi_round", EMPTY_JSON_SCHEMA)
				.description("MRTR: multi-round InputRequiredResult workflow")
				.build(), ELICITATION, (ctx, req) -> {
					String state = req.requestState();
					if (state != null && state.startsWith("round-1")) {
						Map<String, Object> step1 = acceptedContent(req.inputResponses(), "step1");
						if (step1 != null && step1.get("name") instanceof String name) {
							return McpSyncResponse.result(InputRequiredResult.builder()
								.elicit("step2", elicitString("Step 2: What is your favorite color?", "color"))
								.requestState("round-2:" + name)
								.build());
						}
					}
					if (state != null && state.startsWith("round-2:")) {
						Map<String, Object> step2 = acceptedContent(req.inputResponses(), "step2");
						if (step2 != null && step2.get("color") instanceof String color) {
							return text("Multi-round complete for " + state.substring("round-2:".length())
									+ " who likes " + color);
						}
					}
					return McpSyncResponse.result(InputRequiredResult.builder()
						.elicit("step1", elicitString("Step 1: What is your name?", "name"))
						.requestState("round-1")
						.build());
				}))
			// Integrity protection comes from the server's RequestStateCodec: a tampered
			// requestState is rejected before this handler runs.
			.tool(McpSyncTool.of(Tool.builder("test_input_required_result_tampered_state", EMPTY_JSON_SCHEMA)
				.description("MRTR: HMAC-signed requestState integrity test")
				.build(), ELICITATION, (ctx, req) -> {
					if ("tamper-test".equals(req.requestState())
							&& responseObject(req.inputResponses(), "confirm") != null) {
						return text("integrity-ok: state verified");
					}
					return McpSyncResponse.result(InputRequiredResult.builder()
						.elicit("confirm", elicitConfirm())
						.requestState("tamper-test")
						.build());
				}))
			.tool(McpSyncTool.of(Tool.builder("test_input_required_result_capabilities", EMPTY_JSON_SCHEMA)
				.description("MRTR: respects client capabilities in inputRequests")
				.build(), (ctx, req) -> {
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
					return McpSyncResponse.result(builder.build());
				}))
			.build();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// Resources

	private static InMemorySyncResourceRepository resources() {
		return InMemorySyncResourceRepository.builder()
			.resource(
					Resource.builder("test://static-text", "Static Text Resource")
						.description("A static text resource for testing")
						.mimeType("text/plain")
						.build(),
					McpSyncResource
						.of((ctx, req) -> textResource(req.uri(), "This is the content of the static text resource.")))
			.resource(
					Resource.builder("test://static-binary", "Static Binary Resource")
						.description("A static binary resource for testing")
						.mimeType("image/png")
						.build(),
					McpSyncResource.of((ctx, req) -> McpSyncResponse.result(ReadResourceResult
						.builder(List
							.of(BlobResourceContents.builder(req.uri(), RED_PIXEL_PNG).mimeType("image/png").build()))
						.build())))
			.resource(
					Resource.builder("test://watched-resource", "Watched Resource")
						.description("A resource that can be subscribed to for updates")
						.mimeType("text/plain")
						.build(),
					McpSyncResource.of((ctx, req) -> textResource(req.uri(), "This is a watched resource content.")))
			.template(
					McpSyncResourceTemplate
						.of(ResourceTemplate.builder(TEMPLATE_URI, "Template Resource")
							.description("A resource template for testing parameter substitution")
							.mimeType("application/json")
							.build())
						.withCompletionHandler((ctx, request) -> completeFrom(List.of("1", "2", "3"), request)),
					McpSyncResource.of((ctx, req) -> {
						String id = TEMPLATE_VARIABLES.extractVariableValues(req.uri()).get("id");
						String json = String
							.format("{\"id\":\"%s\",\"templateTest\":true,\"data\":\"Data for ID: %s\"}", id, id);
						return McpSyncResponse.result(ReadResourceResult
							.builder(List
								.of(TextResourceContents.builder(req.uri(), json).mimeType("application/json").build()))
							.build());
					}))
			.supportsSubscribe(true)
			.supportsCompletion(true)
			.build();
	}

	private static McpSyncResponse<ReadResourceOutcome> textResource(String uri, String text) {
		return McpSyncResponse.result(ReadResourceResult
			.builder(List.of(TextResourceContents.builder(uri, text).mimeType("text/plain").build()))
			.build());
	}

	// Prompts

	private static InMemorySyncPromptRepository prompts() {
		return InMemorySyncPromptRepository.builder()
			.prompt(McpSyncPrompt.of(
					Prompt.builder("test_simple_prompt").description("A simple prompt for testing").build(),
					(ctx, req) -> messages(PromptMessage
						.builder(Role.USER, TextContent.builder("This is a simple prompt for testing.").build())
						.build())))
			.prompt(McpSyncPrompt.of(Prompt.builder("test_prompt_with_arguments")
				.description("A prompt with arguments for testing")
				.arguments(List.of(
						PromptArgument.builder("arg1").description("First test argument").required(true).build(),
						PromptArgument.builder("arg2").description("Second test argument").required(true).build()))
				.build(), (ctx, req) -> {
					Map<String, String> args = req.arguments() == null ? Map.of() : req.arguments();
					String text = String.format("Prompt with arguments: arg1='%s', arg2='%s'", args.get("arg1"),
							args.get("arg2"));
					return messages(PromptMessage.builder(Role.USER, TextContent.builder(text).build()).build());
				}).withCompletionHandler((ctx, request) -> completeFrom(List.of("alpha", "beta", "gamma"), request)))
			.prompt(McpSyncPrompt.of(Prompt.builder("test_prompt_with_embedded_resource")
				.description("A prompt with embedded resource for testing")
				.arguments(List.of(PromptArgument.builder("resourceUri")
					.description("URI of the resource to embed")
					.required(true)
					.build()))
				.build(), (ctx, req) -> {
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
				}))
			.prompt(McpSyncPrompt.of(
					Prompt.builder("test_prompt_with_image")
						.description("A prompt with image content for testing")
						.build(),
					(ctx, req) -> messages(
							PromptMessage.builder(Role.USER, ImageContent.builder(RED_PIXEL_PNG, "image/png").build())
								.build(),
							PromptMessage
								.builder(Role.USER, TextContent.builder("Please analyze the image above.").build())
								.build())))
			.prompt(McpSyncPrompt.of(Prompt.builder("test_input_required_result_prompt")
				.description("MRTR: prompt that requires elicitation input")
				.build(), ELICITATION, (ctx, req) -> {
					Map<String, Object> content = acceptedContent(req.inputResponses(), "user_context");
					if (content == null || !(content.get("context") instanceof String context)) {
						return McpSyncResponse.result(InputRequiredResult.builder()
							.elicit("user_context", elicitString("What context should the prompt use?", "context"))
							.build());
					}
					return messages(PromptMessage
						.builder(Role.USER, TextContent.builder("Prompt with context: " + context).build())
						.build());
				}))
			.supportsCompletion(true)
			.build();
	}

	private static McpSyncResponse<GetPromptOutcome> messages(PromptMessage... messages) {
		return McpSyncResponse.result(GetPromptResult.builder(List.of(messages)).build());
	}

}
