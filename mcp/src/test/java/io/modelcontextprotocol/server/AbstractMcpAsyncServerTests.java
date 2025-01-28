/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransport;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for the {@link McpAsyncServer} that can be used with different
 * {@link McpTransport} implementations.
 *
 * @author Christian Tzolov
 */
// KEEP IN SYNC with the class in mcp-test module
public abstract class AbstractMcpAsyncServerTests {

	private static final String TEST_TOOL_NAME = "test-tool";

	private static final String TEST_RESOURCE_URI = "test://resource";

	private static final String TEST_PROMPT_NAME = "test-prompt";

	abstract protected ServerMcpTransport createMcpTransport();

	protected void onStart() {
	}

	protected void onClose() {
	}

	@BeforeEach
	void setUp() {
	}

	@AfterEach
	void tearDown() {
		onClose();
	}

	// ---------------------------------------
	// Server Lifecycle Tests
	// ---------------------------------------

	@Test
	void testConstructorWithInvalidArguments() {
		assertThatThrownBy(() -> McpServer.async(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport must not be null");

		assertThatThrownBy(() -> McpServer.async(createMcpTransport()).serverInfo((McpSchema.Implementation) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Server info must not be null");
	}

	@Test
	void testGracefulShutdown() {
		var mcpAsyncServer = McpServer.async(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(mcpAsyncServer.closeGracefully()).verifyComplete();
	}

	@Test
	void testImmediateClose() {
		var mcpAsyncServer = McpServer.async(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpAsyncServer.close()).doesNotThrowAnyException();
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

	@Test
	void testAddTool() {
		Tool newTool = new McpSchema.Tool("new-tool", "New test tool", emptyJsonSchema);
		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		StepVerifier.create(mcpAsyncServer.addTool(new McpServerFeatures.AsyncToolRegistration(newTool,
				args -> Mono.just(new CallToolResult(List.of(), false)))))
			.verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddDuplicateTool() {
		Tool duplicateTool = new McpSchema.Tool(TEST_TOOL_NAME, "Duplicate tool", emptyJsonSchema);

		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(duplicateTool, args -> Mono.just(new CallToolResult(List.of(), false)))
			.build();

		StepVerifier.create(mcpAsyncServer.addTool(new McpServerFeatures.AsyncToolRegistration(duplicateTool,
				args -> Mono.just(new CallToolResult(List.of(), false)))))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpError.class)
					.hasMessage("Tool with name '" + TEST_TOOL_NAME + "' already exists");
			});

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveTool() {
		Tool too = new McpSchema.Tool(TEST_TOOL_NAME, "Duplicate tool", emptyJsonSchema);

		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(too, args -> Mono.just(new CallToolResult(List.of(), false)))
			.build();

		StepVerifier.create(mcpAsyncServer.removeTool(TEST_TOOL_NAME)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentTool() {
		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		StepVerifier.create(mcpAsyncServer.removeTool("nonexistent-tool")).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class).hasMessage("Tool with name 'nonexistent-tool' not found");
		});

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testNotifyToolsListChanged() {
		Tool too = new McpSchema.Tool(TEST_TOOL_NAME, "Duplicate tool", emptyJsonSchema);

		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(too, args -> Mono.just(new CallToolResult(List.of(), false)))
			.build();

		StepVerifier.create(mcpAsyncServer.notifyToolsListChanged()).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resources Tests
	// ---------------------------------------

	@Test
	void testNotifyResourcesListChanged() {
		var mcpAsyncServer = McpServer.async(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(mcpAsyncServer.notifyResourcesListChanged()).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResource() {
		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "text/plain", "Test resource description",
				null);
		McpServerFeatures.AsyncResourceRegistration registration = new McpServerFeatures.AsyncResourceRegistration(
				resource, req -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(mcpAsyncServer.addResource(registration)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithNullRegistration() {
		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		StepVerifier.create(mcpAsyncServer.addResource((McpServerFeatures.AsyncResourceRegistration) null))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpError.class).hasMessage("Resource must not be null");
			});

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "text/plain", "Test resource description",
				null);
		McpServerFeatures.AsyncResourceRegistration registration = new McpServerFeatures.AsyncResourceRegistration(
				resource, req -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(serverWithoutResources.addResource(registration)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Server must be configured with resource capabilities");
		});
	}

	@Test
	void testRemoveResourceWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.build();

		StepVerifier.create(serverWithoutResources.removeResource(TEST_RESOURCE_URI)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Server must be configured with resource capabilities");
		});
	}

	// ---------------------------------------
	// Prompts Tests
	// ---------------------------------------

	@Test
	void testNotifyPromptsListChanged() {
		var mcpAsyncServer = McpServer.async(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(mcpAsyncServer.notifyPromptsListChanged()).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddPromptWithNullRegistration() {
		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(false).build())
			.build();

		StepVerifier.create(mcpAsyncServer.addPrompt((McpServerFeatures.AsyncPromptRegistration) null))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpError.class).hasMessage("Prompt registration must not be null");
			});
	}

	@Test
	void testAddPromptWithoutCapability() {
		// Create a server without prompt capabilities
		McpAsyncServer serverWithoutPrompts = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.build();

		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", List.of());
		McpServerFeatures.AsyncPromptRegistration registration = new McpServerFeatures.AsyncPromptRegistration(prompt,
				req -> Mono.just(new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content"))))));

		StepVerifier.create(serverWithoutPrompts.addPrompt(registration)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Server must be configured with prompt capabilities");
		});
	}

	@Test
	void testRemovePromptWithoutCapability() {
		// Create a server without prompt capabilities
		McpAsyncServer serverWithoutPrompts = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.build();

		StepVerifier.create(serverWithoutPrompts.removePrompt(TEST_PROMPT_NAME)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Server must be configured with prompt capabilities");
		});
	}

	@Test
	void testRemovePrompt() {
		String TEST_PROMPT_NAME_TO_REMOVE = "TEST_PROMPT_NAME678";

		Prompt prompt = new Prompt(TEST_PROMPT_NAME_TO_REMOVE, "Test Prompt", List.of());
		McpServerFeatures.AsyncPromptRegistration registration = new McpServerFeatures.AsyncPromptRegistration(prompt,
				req -> Mono.just(new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content"))))));

		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.prompts(registration)
			.build();

		StepVerifier.create(mcpAsyncServer.removePrompt(TEST_PROMPT_NAME_TO_REMOVE)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentPrompt() {
		var mcpAsyncServer2 = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.build();

		StepVerifier.create(mcpAsyncServer2.removePrompt("nonexistent-prompt")).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Prompt with name 'nonexistent-prompt' not found");
		});

		assertThatCode(() -> mcpAsyncServer2.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------

	@Test
	void testRootsChangeConsumers() {
		// Test with single consumer
		var rootsReceived = new McpSchema.Root[1];
		var consumerCalled = new boolean[1];

		var singleConsumerServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.rootsChangeConsumers(List.of(roots -> Mono.fromRunnable(() -> {
				consumerCalled[0] = true;
				if (!roots.isEmpty()) {
					rootsReceived[0] = roots.get(0);
				}
			})))
			.build();

		assertThat(singleConsumerServer).isNotNull();
		assertThatCode(() -> singleConsumerServer.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
		onClose();

		// Test with multiple consumers
		var consumer1Called = new boolean[1];
		var consumer2Called = new boolean[1];
		var rootsContent = new List[1];

		var multipleConsumersServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.rootsChangeConsumers(List.of(roots -> Mono.fromRunnable(() -> {
				consumer1Called[0] = true;
				rootsContent[0] = roots;
			}), roots -> Mono.fromRunnable(() -> consumer2Called[0] = true)))
			.build();

		assertThat(multipleConsumersServer).isNotNull();
		assertThatCode(() -> multipleConsumersServer.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
		onClose();

		// Test error handling
		var errorHandlingServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.rootsChangeConsumers(List.of(roots -> {
				throw new RuntimeException("Test error");
			}))
			.build();

		assertThat(errorHandlingServer).isNotNull();
		assertThatCode(() -> errorHandlingServer.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
		onClose();

		// Test without consumers
		var noConsumersServer = McpServer.async(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThat(noConsumersServer).isNotNull();
		assertThatCode(() -> noConsumersServer.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@Test
	void testLoggingLevels() {
		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().logging().build())
			.build();

		// Test all logging levels
		for (McpSchema.LoggingLevel level : McpSchema.LoggingLevel.values()) {
			var notification = McpSchema.LoggingMessageNotification.builder()
				.level(level)
				.logger("test-logger")
				.data("Test message with level " + level)
				.build();

			StepVerifier.create(mcpAsyncServer.loggingNotification(notification)).verifyComplete();
		}
	}

	@Test
	void testLoggingWithoutCapability() {
		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().build()) // No logging capability
			.build();

		var notification = McpSchema.LoggingMessageNotification.builder()
			.level(McpSchema.LoggingLevel.INFO)
			.logger("test-logger")
			.data("Test log message")
			.build();

		StepVerifier.create(mcpAsyncServer.loggingNotification(notification)).verifyComplete();
	}

	@Test
	void testLoggingWithNullNotification() {
		var mcpAsyncServer = McpServer.async(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().logging().build())
			.build();

		StepVerifier.create(mcpAsyncServer.loggingNotification(null)).verifyError(McpError.class);
	}

}
