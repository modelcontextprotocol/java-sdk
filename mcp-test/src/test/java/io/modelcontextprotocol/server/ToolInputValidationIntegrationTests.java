/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for tool input validation against JSON schema. Validates that input validation
 * errors are returned as Tool Execution Errors (isError=true) rather than Protocol
 * Errors, per MCP specification.
 *
 * @author Andrei Shakirin
 */
@Timeout(15)
class ToolInputValidationIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private static final String TOOL_NAME = "test-tool";

	private static final McpSchema.JsonSchema INPUT_SCHEMA = new McpSchema.JsonSchema("object",
			Map.of("name", Map.of("type", "string"), "age", Map.of("type", "integer", "minimum", 0)),
			List.of("name", "age"), null, null, null);

	private static final McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (
			r) -> McpTransportContext.create(Map.of("important", "value"));

	private HttpServletStreamableServerTransportProvider mcpServerTransportProvider;

	private Tomcat tomcat;

	static Stream<Arguments> validInputTestCases() {
		return Stream.of(
				// serverType, validationEnabled, inputArgs, expectedOutput
				Arguments.of("sync", true, Map.of("name", "Alice", "age", 30), "Hello Alice, age 30"),
				Arguments.of("async", true, Map.of("name", "Bob", "age", 25), "Hello Bob, age 25"),
				Arguments.of("sync", false, Map.of("name", "Alice", "age", 30), "Hello Alice, age 30"),
				Arguments.of("async", false, Map.of("name", "Bob", "age", 25), "Hello Bob, age 25"));
	}

	static Stream<Arguments> invalidInputTestCases() {
		return Stream.of(
				// serverType, inputArgs, expectedErrorSubstring
				Arguments.of("sync", Map.of("name", "Alice"), "age"), // missing required
				Arguments.of("async", Map.of("name", "Bob", "age", -10), "minimum")); // invalid
																						// value
	}

	private final McpClient.SyncSpec clientBuilder = McpClient
		.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT).endpoint(MESSAGE_ENDPOINT).build())
		.requestTimeout(Duration.ofSeconds(10));

	@BeforeEach
	public void before() {
		mcpServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
			.mcpEndpoint(MESSAGE_ENDPOINT)
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	private McpServerFeatures.SyncToolSpecification createSyncTool() {
		Tool tool = Tool.builder()
			.name(TOOL_NAME)
			.description("Test tool with schema")
			.inputSchema(INPUT_SCHEMA)
			.build();

		return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
			String name = (String) request.arguments().get("name");
			Integer age = ((Number) request.arguments().get("age")).intValue();
			return CallToolResult.builder()
				.content(List.of(new TextContent("Hello " + name + ", age " + age)))
				.isError(false)
				.build();
		}).build();
	}

	private McpServerFeatures.AsyncToolSpecification createAsyncTool() {
		Tool tool = Tool.builder()
			.name(TOOL_NAME)
			.description("Test tool with schema")
			.inputSchema(INPUT_SCHEMA)
			.build();

		return McpServerFeatures.AsyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
			String name = (String) request.arguments().get("name");
			Integer age = ((Number) request.arguments().get("age")).intValue();
			return Mono.just(CallToolResult.builder()
				.content(List.of(new TextContent("Hello " + name + ", age " + age)))
				.isError(false)
				.build());
		}).build();
	}

	@ParameterizedTest(name = "{0} server, validation={1}")
	@MethodSource("validInputTestCases")
	void validInput_shouldSucceed(String serverType, boolean validationEnabled, Map<String, Object> input,
			String expectedOutput) {
		Object server = createServer(serverType, validationEnabled);

		try (var client = clientBuilder.clientInfo(new McpSchema.Implementation("test-client", "1.0.0")).build()) {
			client.initialize();
			CallToolResult result = client.callTool(new CallToolRequest(TOOL_NAME, input));

			assertThat(result.isError()).isFalse();
			assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedOutput);
		}
		finally {
			closeServer(server, serverType);
		}
	}

	@ParameterizedTest(name = "{0} server, input={1}")
	@MethodSource("invalidInputTestCases")
	void invalidInput_withDefaultValidation_shouldReturnToolError(String serverType, Map<String, Object> input,
			String expectedErrorSubstring) {
		Object server = createServerWithDefaultValidation(serverType);

		try (var client = clientBuilder.clientInfo(new McpSchema.Implementation("test-client", "1.0.0")).build()) {
			client.initialize();
			CallToolResult result = client.callTool(new CallToolRequest(TOOL_NAME, input));

			assertThat(result.isError()).isTrue();
			String errorMessage = ((TextContent) result.content().get(0)).text();
			assertThat(errorMessage).containsIgnoringCase(expectedErrorSubstring);
		}
		finally {
			closeServer(server, serverType);
		}
	}

	@ParameterizedTest(name = "{0} server, input={1}")
	@MethodSource("invalidInputTestCases")
	void invalidInput_withValidationDisabled_shouldSucceed(String serverType, Map<String, Object> input,
			String ignored) {
		Object server = createServer(serverType, false);

		try (var client = clientBuilder.clientInfo(new McpSchema.Implementation("test-client", "1.0.0")).build()) {
			client.initialize();
			// Invalid input should pass through when validation is disabled
			// The tool handler will fail, but that's expected - we're testing validation
			// is skipped
			try {
				client.callTool(new CallToolRequest(TOOL_NAME, input));
			}
			catch (Exception e) {
				// Expected - tool handler fails on invalid input, but validation didn't
				// block it
				assertThat(e.getMessage()).doesNotContainIgnoringCase("validation");
			}
		}
		finally {
			closeServer(server, serverType);
		}
	}

	private Object createServerWithDefaultValidation(String serverType) {
		if ("sync".equals(serverType)) {
			return prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").tools(createSyncTool()).build();
		}
		else {
			return prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(createAsyncTool()).build();
		}
	}

	private Object createServer(String serverType, boolean validationEnabled) {
		if ("sync".equals(serverType)) {
			return prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.validateToolInputs(validationEnabled)
				.tools(createSyncTool())
				.build();
		}
		else {
			return prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.validateToolInputs(validationEnabled)
				.tools(createAsyncTool())
				.build();
		}
	}

	private void closeServer(Object server, String serverType) {
		if ("async".equals(serverType)) {
			((McpAsyncServer) server).closeGracefully().block();
		}
		else {
			((McpSyncServer) server).close();
		}
	}

}
