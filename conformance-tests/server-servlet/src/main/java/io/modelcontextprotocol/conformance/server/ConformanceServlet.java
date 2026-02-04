package io.modelcontextprotocol.conformance.server;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.AudioContent;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.SamplingMessage;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConformanceServlet {

	private static final Logger logger = LoggerFactory.getLogger(ConformanceServlet.class);

	private static final int PORT = 8080;

	private static final String MCP_ENDPOINT = "/mcp";

	private static final JsonSchema EMPTY_JSON_SCHEMA = new JsonSchema("object", Collections.emptyMap(), null, null,
			null, null);

	// Minimal 1x1 red pixel PNG (base64 encoded)
	private static final String RED_PIXEL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

	// Minimal WAV file (base64 encoded) - 1 sample at 8kHz
	private static final String MINIMAL_WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQAAAAA=";

	public static void main(String[] args) throws Exception {
		logger.info("Starting MCP Conformance Tests - Servlet Server");

		HttpServletStreamableServerTransportProvider transportProvider = HttpServletStreamableServerTransportProvider
			.builder()
			.mcpEndpoint(MCP_ENDPOINT)
			.keepAliveInterval(Duration.ofSeconds(30))
			.build();

		// Build server with all conformance test features
		var mcpServer = McpServer.sync(transportProvider)
			.serverInfo("mcp-conformance-server", "1.0.0")
			.capabilities(ServerCapabilities.builder()
				.completions()
				.resources(true, false)
				.tools(false)
				.prompts(false)
				.build())
			.tools(createToolSpecs())
			.prompts(createPromptSpecs())
			.resources(createResourceSpecs())
			.resourceTemplates(createResourceTemplateSpecs())
			.completions(createCompletionSpecs())
			.requestTimeout(Duration.ofSeconds(30))
			.build();

		// Set up embedded Tomcat
		Tomcat tomcat = createEmbeddedTomcat(transportProvider);

		try {
			tomcat.start();
			logger.info("Conformance MCP Servlet Server started on port {} with endpoint {}", PORT, MCP_ENDPOINT);
			logger.info("Server URL: http://localhost:{}{}", PORT, MCP_ENDPOINT);

			// Keep the server running
			tomcat.getServer().await();
		}
		catch (LifecycleException e) {
			logger.error("Failed to start Tomcat server", e);
			throw e;
		}
		finally {
			logger.info("Shutting down MCP server...");
			mcpServer.closeGracefully();
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				logger.error("Error during Tomcat shutdown", e);
			}
		}
	}

	private static Tomcat createEmbeddedTomcat(HttpServletStreamableServerTransportProvider transportProvider) {
		Tomcat tomcat = new Tomcat();
		tomcat.setPort(PORT);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		Context context = tomcat.addContext("", baseDir);

		// Add the MCP servlet to Tomcat
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(transportProvider);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		var connector = tomcat.getConnector();
		connector.setAsyncTimeout(30000);
		return tomcat;
	}

	private static List<McpServerFeatures.SyncToolSpecification> createToolSpecs() {
		return List.of(
				// test_simple_text - Returns simple text content
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_simple_text")
						.description("Returns simple text content for testing")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_simple_text' called");
						return CallToolResult.builder()
							.content(List.of(new TextContent("This is a simple text response for testing.")))
							.isError(false)
							.build();
					})
					.build(),

				// test_image_content - Returns image content
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_image_content")
						.description("Returns image content for testing")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_image_content' called");
						return CallToolResult.builder()
							.content(List.of(new ImageContent(null, RED_PIXEL_PNG, "image/png")))
							.isError(false)
							.build();
					})
					.build(),

				// test_audio_content - Returns audio content
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_audio_content")
						.description("Returns audio content for testing")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_audio_content' called");
						return CallToolResult.builder()
							.content(List.of(new AudioContent(null, MINIMAL_WAV, "audio/wav")))
							.isError(false)
							.build();
					})
					.build(),

				// test_embedded_resource - Returns embedded resource content
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_embedded_resource")
						.description("Returns embedded resource content for testing")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_embedded_resource' called");
						TextResourceContents resourceContents = new TextResourceContents("test://embedded-resource",
								"text/plain", "This is an embedded resource content.");
						EmbeddedResource embeddedResource = new EmbeddedResource(null, resourceContents);
						return CallToolResult.builder().content(List.of(embeddedResource)).isError(false).build();
					})
					.build(),

				// test_multiple_content_types - Returns multiple content types
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_multiple_content_types")
						.description("Returns multiple content types for testing")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_multiple_content_types' called");
						TextResourceContents resourceContents = new TextResourceContents(
								"test://mixed-content-resource", "application/json",
								"{\"test\":\"data\",\"value\":123}");
						EmbeddedResource embeddedResource = new EmbeddedResource(null, resourceContents);
						return CallToolResult.builder()
							.content(List.of(new TextContent("Multiple content types test:"),
									new ImageContent(null, RED_PIXEL_PNG, "image/png"), embeddedResource))
							.isError(false)
							.build();
					})
					.build(),

				// test_tool_with_logging - Tool that sends log messages during execution
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_tool_with_logging")
						.description("Tool that sends log messages during execution")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_tool_with_logging' called");
						// Send log notifications
						exchange.loggingNotification(LoggingMessageNotification.builder()
							.level(LoggingLevel.INFO)
							.data("Tool execution started")
							.build());
						exchange.loggingNotification(LoggingMessageNotification.builder()
							.level(LoggingLevel.INFO)
							.data("Tool processing data")
							.build());
						exchange.loggingNotification(LoggingMessageNotification.builder()
							.level(LoggingLevel.INFO)
							.data("Tool execution completed")
							.build());
						return CallToolResult.builder()
							.content(List.of(new TextContent("Tool execution completed with logging")))
							.isError(false)
							.build();
					})
					.build(),

				// test_error_handling - Tool that always returns an error
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_error_handling")
						.description("Tool that returns an error for testing error handling")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_error_handling' called");
						return CallToolResult.builder()
							.content(List.of(new TextContent("This tool intentionally returns an error for testing")))
							.isError(true)
							.build();
					})
					.build(),

				// test_tool_with_progress - Tool that reports progress
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_tool_with_progress")
						.description("Tool that reports progress notifications")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_tool_with_progress' called");
						Object progressToken = request.meta().get("progressToken");
						if (progressToken != null) {
							// Send progress notifications sequentially
							exchange.progressNotification(new ProgressNotification(progressToken, 0.0, 100.0, null));
							// try {
							// Thread.sleep(50);
							// }
							// catch (InterruptedException e) {
							// Thread.currentThread().interrupt();
							// }
							exchange.progressNotification(new ProgressNotification(progressToken, 50.0, 100.0, null));
							// try {
							// Thread.sleep(50);
							// }
							// catch (InterruptedException e) {
							// Thread.currentThread().interrupt();
							// }
							exchange.progressNotification(new ProgressNotification(progressToken, 100.0, 100.0, null));
							return CallToolResult.builder()
								.content(List.of(new TextContent("Tool execution completed with progress")))
								.isError(false)
								.build();
						}
						else {
							// No progress token, just execute with delays
							// try {
							// Thread.sleep(100);
							// }
							// catch (InterruptedException e) {
							// Thread.currentThread().interrupt();
							// }
							return CallToolResult.builder()
								.content(List.of(new TextContent("Tool execution completed without progress")))
								.isError(false)
								.build();
						}
					})
					.build(),

				// test_sampling - Tool that requests LLM sampling from client
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_sampling")
						.description("Tool that requests LLM sampling from client")
						.inputSchema(new JsonSchema("object",
								Map.of("prompt",
										Map.of("type", "string", "description", "The prompt to send to the LLM")),
								List.of("prompt"), null, null, null))
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_sampling' called");
						String prompt = (String) request.arguments().get("prompt");

						// Request sampling from client
						CreateMessageRequest samplingRequest = CreateMessageRequest.builder()
							.messages(List.of(new SamplingMessage(Role.USER, new TextContent(prompt))))
							.maxTokens(100)
							.build();

						CreateMessageResult response = exchange.createMessage(samplingRequest);
						String responseText = "LLM response: " + ((TextContent) response.content()).text();
						return CallToolResult.builder()
							.content(List.of(new TextContent(responseText)))
							.isError(false)
							.build();
					})
					.build(),

				// test_elicitation - Tool that requests user input from client
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_elicitation")
						.description("Tool that requests user input from client")
						.inputSchema(new JsonSchema("object",
								Map.of("message",
										Map.of("type", "string", "description", "The message to show the user")),
								List.of("message"), null, null, null))
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_elicitation' called");
						String message = (String) request.arguments().get("message");

						// Request elicitation from client
						Map<String, Object> requestedSchema = Map.of("type", "object", "properties",
								Map.of("username", Map.of("type", "string", "description", "User's response"), "email",
										Map.of("type", "string", "description", "User's email address")),
								"required", List.of("username", "email"));

						ElicitRequest elicitRequest = new ElicitRequest(message, requestedSchema);

						ElicitResult response = exchange.createElicitation(elicitRequest);
						String responseText = "User response: action=" + response.action() + ", content="
								+ response.content();
						return CallToolResult.builder()
							.content(List.of(new TextContent(responseText)))
							.isError(false)
							.build();
					})
					.build(),

				// test_elicitation_sep1034_defaults - Tool with default values for all
				// primitive types
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_elicitation_sep1034_defaults")
						.description("Tool that requests elicitation with default values for all primitive types")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_elicitation_sep1034_defaults' called");

						// Create schema with default values for all primitive types
						Map<String, Object> requestedSchema = Map.of("type", "object", "properties",
								Map.of("name", Map.of("type", "string", "default", "John Doe"), "age",
										Map.of("type", "integer", "default", 30), "score",
										Map.of("type", "number", "default", 95.5), "status",
										Map.of("type", "string", "enum", List.of("active", "inactive", "pending"),
												"default", "active"),
										"verified", Map.of("type", "boolean", "default", true)),
								"required", List.of("name", "age", "score", "status", "verified"));

						ElicitRequest elicitRequest = new ElicitRequest("Please provide your information with defaults",
								requestedSchema);

						ElicitResult response = exchange.createElicitation(elicitRequest);
						String responseText = "Elicitation completed: action=" + response.action() + ", content="
								+ response.content();
						return CallToolResult.builder()
							.content(List.of(new TextContent(responseText)))
							.isError(false)
							.build();
					})
					.build(),

				// test_elicitation_sep1330_enums - Tool with enum schema improvements
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(Tool.builder()
						.name("test_elicitation_sep1330_enums")
						.description("Tool that requests elicitation with enum schema improvements")
						.inputSchema(EMPTY_JSON_SCHEMA)
						.build())
					.callHandler((exchange, request) -> {
						logger.info("Tool 'test_elicitation_sep1330_enums' called");

						// Create schema with all 5 enum variants
						Map<String, Object> requestedSchema = Map.of("type", "object", "properties", Map.of(
								// 1. Untitled single-select
								"untitledSingle",
								Map.of("type", "string", "enum", List.of("option1", "option2", "option3")),
								// 2. Titled single-select using oneOf with const/title
								"titledSingle",
								Map.of("type", "string", "oneOf",
										List.of(Map.of("const", "value1", "title", "First Option"),
												Map.of("const", "value2", "title", "Second Option"),
												Map.of("const", "value3", "title", "Third Option"))),
								// 3. Legacy titled using enumNames (deprecated)
								"legacyEnum",
								Map.of("type", "string", "enum", List.of("opt1", "opt2", "opt3"), "enumNames",
										List.of("Option One", "Option Two", "Option Three")),
								// 4. Untitled multi-select
								"untitledMulti",
								Map.of("type", "array", "items",
										Map.of("type", "string", "enum", List.of("option1", "option2", "option3"))),
								// 5. Titled multi-select using items.anyOf with
								// const/title
								"titledMulti",
								Map.of("type", "array", "items",
										Map.of("anyOf",
												List.of(Map.of("const", "value1", "title", "First Choice"),
														Map.of("const", "value2", "title", "Second Choice"),
														Map.of("const", "value3", "title", "Third Choice"))))),
								"required", List.of("untitledSingle", "titledSingle", "legacyEnum", "untitledMulti",
										"titledMulti"));

						ElicitRequest elicitRequest = new ElicitRequest("Select your preferences", requestedSchema);

						ElicitResult response = exchange.createElicitation(elicitRequest);
						String responseText = "Elicitation completed: action=" + response.action() + ", content="
								+ response.content();
						return CallToolResult.builder()
							.content(List.of(new TextContent(responseText)))
							.isError(false)
							.build();
					})
					.build());
	}

	private static List<McpServerFeatures.SyncPromptSpecification> createPromptSpecs() {
		return List.of(
				// test_simple_prompt - Simple prompt without arguments
				new McpServerFeatures.SyncPromptSpecification(
						new Prompt("test_simple_prompt", null, "A simple prompt for testing", List.of()),
						(exchange, request) -> {
							logger.info("Prompt 'test_simple_prompt' requested");
							return new GetPromptResult(null, List.of(new PromptMessage(Role.USER,
									new TextContent("This is a simple prompt for testing."))));
						}),

				// test_prompt_with_arguments - Prompt with arguments
				new McpServerFeatures.SyncPromptSpecification(
						new Prompt("test_prompt_with_arguments", null, "A prompt with arguments for testing",
								List.of(new PromptArgument("arg1", "First test argument", true),
										new PromptArgument("arg2", "Second test argument", true))),
						(exchange, request) -> {
							logger.info("Prompt 'test_prompt_with_arguments' requested");
							String arg1 = (String) request.arguments().get("arg1");
							String arg2 = (String) request.arguments().get("arg2");
							String text = String.format("Prompt with arguments: arg1='%s', arg2='%s'", arg1, arg2);
							return new GetPromptResult(null,
									List.of(new PromptMessage(Role.USER, new TextContent(text))));
						}),

				// test_prompt_with_embedded_resource - Prompt with embedded resource
				new McpServerFeatures.SyncPromptSpecification(
						new Prompt("test_prompt_with_embedded_resource", null,
								"A prompt with embedded resource for testing",
								List.of(new PromptArgument("resourceUri", "URI of the resource to embed", true))),
						(exchange, request) -> {
							logger.info("Prompt 'test_prompt_with_embedded_resource' requested");
							String resourceUri = (String) request.arguments().get("resourceUri");
							TextResourceContents resourceContents = new TextResourceContents(resourceUri, "text/plain",
									"Embedded resource content for testing.");
							EmbeddedResource embeddedResource = new EmbeddedResource(null, resourceContents);
							return new GetPromptResult(null,
									List.of(new PromptMessage(Role.USER, embeddedResource), new PromptMessage(Role.USER,
											new TextContent("Please process the embedded resource above."))));
						}),

				// test_prompt_with_image - Prompt with image content
				new McpServerFeatures.SyncPromptSpecification(new Prompt("test_prompt_with_image", null,
						"A prompt with image content for testing", List.of()), (exchange, request) -> {
							logger.info("Prompt 'test_prompt_with_image' requested");
							return new GetPromptResult(null, List.of(
									new PromptMessage(Role.USER, new ImageContent(null, RED_PIXEL_PNG, "image/png")),
									new PromptMessage(Role.USER, new TextContent("Please analyze the image above."))));
						}));
	}

	private static List<McpServerFeatures.SyncResourceSpecification> createResourceSpecs() {
		return List.of(
				// test://static-text - Static text resource
				new McpServerFeatures.SyncResourceSpecification(Resource.builder()
					.uri("test://static-text")
					.name("Static Text Resource")
					.description("A static text resource for testing")
					.mimeType("text/plain")
					.build(), (exchange, request) -> {
						logger.info("Resource 'test://static-text' requested");
						return new ReadResourceResult(List.of(new TextResourceContents("test://static-text",
								"text/plain", "This is the content of the static text resource.")));
					}),

				// test://static-binary - Static binary resource (image)
				new McpServerFeatures.SyncResourceSpecification(Resource.builder()
					.uri("test://static-binary")
					.name("Static Binary Resource")
					.description("A static binary resource for testing")
					.mimeType("image/png")
					.build(), (exchange, request) -> {
						logger.info("Resource 'test://static-binary' requested");
						return new ReadResourceResult(
								List.of(new BlobResourceContents("test://static-binary", "image/png", RED_PIXEL_PNG)));
					}),

				// test://watched-resource - Resource that can be subscribed to
				new McpServerFeatures.SyncResourceSpecification(Resource.builder()
					.uri("test://watched-resource")
					.name("Watched Resource")
					.description("A resource that can be subscribed to for updates")
					.mimeType("text/plain")
					.build(), (exchange, request) -> {
						logger.info("Resource 'test://watched-resource' requested");
						return new ReadResourceResult(List.of(new TextResourceContents("test://watched-resource",
								"text/plain", "This is a watched resource content.")));
					}));
	}

	private static List<McpServerFeatures.SyncResourceTemplateSpecification> createResourceTemplateSpecs() {
		return List.of(
				// test://template/{id}/data - Resource template with parameter
				// substitution
				new McpServerFeatures.SyncResourceTemplateSpecification(ResourceTemplate.builder()
					.uriTemplate("test://template/{id}/data")
					.name("Template Resource")
					.description("A resource template for testing parameter substitution")
					.mimeType("application/json")
					.build(), (exchange, request) -> {
						logger.info("Resource template 'test://template/{{id}}/data' requested for URI: {}",
								request.uri());
						// Extract id from URI
						String uri = request.uri();
						String id = uri.replaceAll("test://template/(.+)/data", "$1");
						String jsonContent = String
							.format("{\"id\":\"%s\",\"templateTest\":true,\"data\":\"Data for ID: %s\"}", id, id);
						return new ReadResourceResult(
								List.of(new TextResourceContents(uri, "application/json", jsonContent)));
					}));
	}

	private static List<McpServerFeatures.SyncCompletionSpecification> createCompletionSpecs() {
		return List.of(
				// Completion for test_prompt_with_arguments
				new McpServerFeatures.SyncCompletionSpecification(new PromptReference("test_prompt_with_arguments"),
						(exchange, request) -> {
							logger.info("Completion requested for prompt 'test_prompt_with_arguments', argument: {}",
									request.argument().name());
							// Return minimal completion with required fields
							return new CompleteResult(new CompleteResult.CompleteCompletion(List.of(), 0, false));
						}));
	}

}
