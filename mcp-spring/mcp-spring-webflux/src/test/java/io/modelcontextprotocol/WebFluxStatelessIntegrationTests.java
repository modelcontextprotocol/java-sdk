/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebFluxStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.server.McpTransportContext;
import net.javacrumbs.jsonunit.core.Option;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class WebFluxStatelessIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxStatelessServerTransport mcpStreamableServerTransport;

	ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	@BeforeEach
	public void before() {
		this.mcpStreamableServerTransport = WebFluxStatelessServerTransport.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.build();

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(mcpStreamableServerTransport.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build()).initializationTimeout(Duration.ofHours(10)).requestTimeout(Duration.ofHours(10)));
		clientBuilders
			.put("webflux", McpClient
				.sync(WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
					.endpoint(CUSTOM_MESSAGE_ENDPOINT)
					.build())
				.initializationTimeout(Duration.ofHours(10))
				.requestTimeout(Duration.ofHours(10)));

	}

	@AfterEach
	public void after() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

	String emptyJsonSchema = """
			{
			"$schema": "http://json-schema.org/draft-07/schema#",
			"type": "object",
			"properties": {}
			}
			""";

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolCallSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var callResponse = new CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpStatelessServerFeatures.SyncToolSpecification tool1 = new McpStatelessServerFeatures.SyncToolSpecification(
				new Tool("tool1", "tool1 description", emptyJsonSchema), (transportContext, request) -> {
					// perform a blocking call to a remote service
					String response = RestClient.create()
						.get()
						.uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
						.retrieve()
						.body(String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpStreamableServerTransport)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testInitialize(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var mcpServer = McpServer.sync(mcpStreamableServerTransport).build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Completion Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : Completion call")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCompletionShouldReturnExpectedSuggestions(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		var expectedValues = List.of("python", "pytorch", "pyside");
		var completionResponse = new CompleteResult(new CompleteResult.CompleteCompletion(expectedValues, 10, // total
				true // hasMore
		));

		AtomicReference<CompleteRequest> samplingRequest = new AtomicReference<>();
		BiFunction<McpTransportContext, CompleteRequest, CompleteResult> completionHandler = (transportContext,
				request) -> {
			samplingRequest.set(request);
			return completionResponse;
		};

		var mcpServer = McpServer.sync(mcpStreamableServerTransport)
			.capabilities(ServerCapabilities.builder().completions().build())
			.prompts(new McpStatelessServerFeatures.SyncPromptSpecification(
					new Prompt("code_review", "Code review", "this is code review prompt",
							List.of(new PromptArgument("language", "Language", "string", false))),
					(transportContext, getPromptRequest) -> null))
			.completions(new McpStatelessServerFeatures.SyncCompletionSpecification(
					new PromptReference("ref/prompt", "code_review", "Code review"), completionHandler))
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CompleteRequest request = new CompleteRequest(
					new PromptReference("ref/prompt", "code_review", "Code review"),
					new CompleteRequest.CompleteArgument("language", "py"));

			CompleteResult result = mcpClient.completeCompletion(request);

			assertThat(result).isNotNull();

			assertThat(samplingRequest.get().argument().name()).isEqualTo("language");
			assertThat(samplingRequest.get().argument().value()).isEqualTo("py");
			assertThat(samplingRequest.get().ref().type()).isEqualTo("ref/prompt");
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tool Structured Output Schema Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputValidationSuccess(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> outputSchema = Map.of(
				"type", "object", "properties", Map.of("result", Map.of("type", "number"), "operation",
						Map.of("type", "string"), "timestamp", Map.of("type", "string")),
				"required", List.of("result", "operation"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpStatelessServerFeatures.SyncToolSpecification tool = new McpStatelessServerFeatures.SyncToolSpecification(
				calculatorTool, (transportContext, request) -> {
					String expression = (String) request.arguments().getOrDefault("expression", "2 + 3");
					double result = evaluateExpression(expression);
					return CallToolResult.builder()
						.structuredContent(
								Map.of("result", result, "operation", expression, "timestamp", "2024-01-01T10:00:00Z"))
						.build();
				});

		var mcpServer = McpServer.sync(mcpStreamableServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Verify tool is listed with output schema
			var toolsList = mcpClient.listTools();
			assertThat(toolsList.tools()).hasSize(1);
			assertThat(toolsList.tools().get(0).name()).isEqualTo("calculator");
			// Note: outputSchema might be null in sync server, but validation still works

			// Call tool with valid structured output
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isFalse();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);

			assertThatJson(((McpSchema.TextContent) response.content().get(0)).text()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("""
						{"result":5.0,"operation":"2 + 3","timestamp":"2024-01-01T10:00:00Z"}"""));

			assertThat(response.structuredContent()).isNotNull();
			assertThatJson(response.structuredContent()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("""
						{"result":5.0,"operation":"2 + 3","timestamp":"2024-01-01T10:00:00Z"}"""));
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputValidationFailure(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> outputSchema = Map.of("type", "object", "properties",
				Map.of("result", Map.of("type", "number"), "operation", Map.of("type", "string")), "required",
				List.of("result", "operation"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpStatelessServerFeatures.SyncToolSpecification tool = new McpStatelessServerFeatures.SyncToolSpecification(
				calculatorTool, (transportContext, request) -> {
					// Return invalid structured output. Result should be number, missing
					// operation
					return CallToolResult.builder()
						.addTextContent("Invalid calculation")
						.structuredContent(Map.of("result", "not-a-number", "extra", "field"))
						.build();
				});

		var mcpServer = McpServer.sync(mcpStreamableServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call tool with invalid structured output
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isTrue();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);

			String errorMessage = ((McpSchema.TextContent) response.content().get(0)).text();
			assertThat(errorMessage).contains("Validation failed");
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputMissingStructuredContent(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> outputSchema = Map.of("type", "object", "properties",
				Map.of("result", Map.of("type", "number")), "required", List.of("result"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpStatelessServerFeatures.SyncToolSpecification tool = new McpStatelessServerFeatures.SyncToolSpecification(
				calculatorTool, (transportContext, request) -> {
					// Return result without structured content but tool has output schema
					return CallToolResult.builder().addTextContent("Calculation completed").build();
				});

		var mcpServer = McpServer.sync(mcpStreamableServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.instructions("bla")
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call tool that should return structured content but doesn't
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isTrue();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);

			String errorMessage = ((McpSchema.TextContent) response.content().get(0)).text();
			assertThat(errorMessage).isEqualTo(
					"Response missing structured content which is expected when calling tool with non-empty outputSchema");
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputRuntimeToolAddition(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		// Start server without tools
		var mcpServer = McpServer.sync(mcpStreamableServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Initially no tools
			assertThat(mcpClient.listTools().tools()).isEmpty();

			// Add tool with output schema at runtime
			Map<String, Object> outputSchema = Map.of("type", "object", "properties",
					Map.of("message", Map.of("type", "string"), "count", Map.of("type", "integer")), "required",
					List.of("message", "count"));

			Tool dynamicTool = Tool.builder()
				.name("dynamic-tool")
				.description("Dynamically added tool")
				.outputSchema(outputSchema)
				.build();

			McpStatelessServerFeatures.SyncToolSpecification toolSpec = new McpStatelessServerFeatures.SyncToolSpecification(
					dynamicTool, (transportContext, request) -> {
						int count = (Integer) request.arguments().getOrDefault("count", 1);
						return CallToolResult.builder()
							.addTextContent("Dynamic tool executed " + count + " times")
							.structuredContent(Map.of("message", "Dynamic execution", "count", count))
							.build();
					});

			// Add tool to server
			mcpServer.addTool(toolSpec);

			// Wait for tool list change notification
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(mcpClient.listTools().tools()).hasSize(1);
			});

			// Verify tool was added with output schema
			var toolsList = mcpClient.listTools();
			assertThat(toolsList.tools()).hasSize(1);
			assertThat(toolsList.tools().get(0).name()).isEqualTo("dynamic-tool");
			// Note: outputSchema might be null in sync server, but validation still works

			// Call dynamically added tool
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("dynamic-tool", Map.of("count", 3)));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isFalse();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) response.content().get(0)).text())
				.isEqualTo("Dynamic tool executed 3 times");

			assertThat(response.structuredContent()).isNotNull();
			assertThatJson(response.structuredContent()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("""
						{"count":3,"message":"Dynamic execution"}"""));
		}

		mcpServer.close();
	}

	private double evaluateExpression(String expression) {
		// Simple expression evaluator for testing
		return switch (expression) {
			case "2 + 3" -> 5.0;
			case "10 * 2" -> 20.0;
			case "7 + 8" -> 15.0;
			case "5 + 3" -> 8.0;
			default -> 0.0;
		};
	}

}
