/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.javacrumbs.jsonunit.core.Option;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

public abstract class AbstractStatelessIntegrationTests {

	protected ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	abstract protected void prepareClients(int port, String mcpEndpoint);

	abstract protected StatelessAsyncSpecification prepareAsyncServerBuilder();

	abstract protected StatelessSyncSpecification prepareSyncServerBuilder();

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void simple(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(1000))
			.build();

		try (
				// Create client without sampling capabilities
				var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
					.requestTimeout(Duration.ofSeconds(1000))
					.build()) {

			assertThat(client.initialize()).isNotNull();

		}
		finally {
			server.closeGracefully().block();
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

		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpStatelessServerFeatures.SyncToolSpecification tool1 = McpStatelessServerFeatures.SyncToolSpecification
			.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((ctx, request) -> {

				try {
					HttpResponse<String> response = HttpClient.newHttpClient()
						.send(HttpRequest.newBuilder()
							.uri(URI.create(
									"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md"))
							.GET()
							.build(), HttpResponse.BodyHandlers.ofString());
					String responseBody = response.body();
					assertThat(responseBody).isNotBlank();
				}
				catch (Exception e) {
					e.printStackTrace();
				}

				return callResponse;
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

			assertThat(response).isNotNull().isEqualTo(callResponse);
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testThrowingToolCallIsCaughtBeforeTimeout(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder()
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(McpStatelessServerFeatures.SyncToolSpecification.builder()
				.tool(Tool.builder()
					.name("tool1")
					.description("tool1 description")
					.inputSchema(emptyJsonSchema)
					.build())
				.callHandler((context, request) -> {
					// We trigger a timeout on blocking read, raising an exception
					Mono.never().block(Duration.ofSeconds(1));
					return null;
				})
				.build())
			.build();

		try (var mcpClient = clientBuilder.requestTimeout(Duration.ofMillis(6666)).build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// We expect the tool call to fail immediately with the exception raised by
			// the offending tool
			// instead of getting back a timeout.
			assertThatExceptionOfType(McpError.class)
				.isThrownBy(() -> mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of())))
				.withMessageContaining("Timeout on blocking read");
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolListChangeHandlingSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpStatelessServerFeatures.SyncToolSpecification tool1 = McpStatelessServerFeatures.SyncToolSpecification
			.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((ctx, request) -> {
				// perform a blocking call to a remote service
				try {
					HttpResponse<String> response = HttpClient.newHttpClient()
						.send(HttpRequest.newBuilder()
							.uri(URI.create(
									"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md"))
							.GET()
							.build(), HttpResponse.BodyHandlers.ofString());
					String responseBody = response.body();
					assertThat(responseBody).isNotBlank();
				}
				catch (Exception e) {
					e.printStackTrace();
				}
				return callResponse;
			})
			.build();

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			try {
				HttpResponse<String> response = HttpClient.newHttpClient()
					.send(HttpRequest.newBuilder()
						.uri(URI.create(
								"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md"))
						.GET()
						.build(), HttpResponse.BodyHandlers.ofString());
				String responseBody = response.body();
				assertThat(responseBody).isNotBlank();
			}
			catch (Exception e) {
				e.printStackTrace();
			}

			rootsRef.set(toolsUpdate);
		}).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			// Remove a tool
			mcpServer.removeTool("tool1");

			// Add a new tool
			McpStatelessServerFeatures.SyncToolSpecification tool2 = McpStatelessServerFeatures.SyncToolSpecification
				.builder()
				.tool(Tool.builder()
					.name("tool2")
					.description("tool2 description")
					.inputSchema(emptyJsonSchema)
					.build())
				.callHandler((exchange, request) -> callResponse)
				.build();

			mcpServer.addTool(tool2);
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testInitialize(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var mcpServer = prepareSyncServerBuilder().build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}
		finally {
			mcpServer.closeGracefully();
		}
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

		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
			.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> {
				String expression = (String) request.arguments().getOrDefault("expression", "2 + 3");
				double result = evaluateExpression(expression);
				return CallToolResult.builder()
					.structuredContent(
							Map.of("result", result, "operation", expression, "timestamp", "2024-01-01T10:00:00Z"))
					.build();
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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

			// In WebMVC, structured content is returned properly
			if (response.structuredContent() != null) {
				assertThat((Map<String, Object>) response.structuredContent()).containsEntry("result", 5.0)
					.containsEntry("operation", "2 + 3")
					.containsEntry("timestamp", "2024-01-01T10:00:00Z");
			}
			else {
				// Fallback to checking content if structured content is not available
				assertThat(response.content()).isNotEmpty();
			}

			assertThat(response.structuredContent()).isNotNull();
			assertThatJson(response.structuredContent()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("""
						{"result":5.0,"operation":"2 + 3","timestamp":"2024-01-01T10:00:00Z"}"""));
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputOfObjectArrayValidationSuccess(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema that returns an array of objects
		Map<String, Object> outputSchema = Map
			.of( // @formatter:off
			"type", "array",
			"items", Map.of(
				"type", "object",
				"properties", Map.of(
					"name", Map.of("type", "string"),
					"age", Map.of("type", "number")),					
				"required", List.of("name", "age"))); // @formatter:on

		Tool calculatorTool = Tool.builder()
			.name("getMembers")
			.description("Returns a list of members")
			.outputSchema(outputSchema)
			.build();

		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
			.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> {
				return CallToolResult.builder()
					.structuredContent(List.of(Map.of("name", "John", "age", 30), Map.of("name", "Peter", "age", 25)))
					.build();
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.build()) {
			assertThat(mcpClient.initialize()).isNotNull();

			// Call tool with valid structured output of type array
			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("getMembers", Map.of()));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isFalse();

			assertThat(response.structuredContent()).isNotNull();
			assertThatJson(response.structuredContent()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isArray()
				.hasSize(2)
				.containsExactlyInAnyOrder(json("""
						{"name":"John","age":30}"""), json("""
						{"name":"Peter","age":25}"""));
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputWithInHandlerError(String clientType) {
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

		// Handler that throws an exception to simulate an error
		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
			.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> CallToolResult.builder()
				.isError(true)
				.content(List.of(new TextContent("Error calling tool: Simulated in-handler error")))
				.build())
			.build();

		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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
			assertThat(response.isError()).isTrue();
			assertThat(response.content()).isNotEmpty();
			assertThat(response.content())
				.containsExactly(new McpSchema.TextContent("Error calling tool: Simulated in-handler error"));
			assertThat(response.structuredContent()).isNull();
		}
		finally {
			mcpServer.closeGracefully();
		}
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

		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
			.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> {
				// Return invalid structured output. Result should be number, missing
				// operation
				return CallToolResult.builder()
					.addTextContent("Invalid calculation")
					.structuredContent(Map.of("result", "not-a-number", "extra", "field"))
					.build();
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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
		finally {
			mcpServer.closeGracefully();
		}
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

		var tool = McpStatelessServerFeatures.SyncToolSpecification.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> {
				// Return result without structured content but tool has output schema
				return CallToolResult.builder().addTextContent("Calculation completed").build();
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputRuntimeToolAddition(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		// Start server without tools
		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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

			var toolSpec = McpStatelessServerFeatures.SyncToolSpecification.builder()
				.tool(dynamicTool)
				.callHandler((exchange, request) -> {
					int count = (Integer) request.arguments().getOrDefault("count", 1);
					return CallToolResult.builder()
						.addTextContent("Dynamic tool executed " + count + " times")
						.structuredContent(Map.of("message", "Dynamic execution", "count", count))
						.build();
				})
				.build();

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
		finally {
			mcpServer.closeGracefully();
		}
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
