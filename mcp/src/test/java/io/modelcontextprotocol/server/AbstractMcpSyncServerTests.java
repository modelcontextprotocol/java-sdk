/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpTransport;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for the {@link McpSyncServer} that can be used with different
 * {@link McpTransport} implementations.
 *
 * @author Christian Tzolov
 */
// KEEP IN SYNC with the class in mcp-test module
public abstract class AbstractMcpSyncServerTests {

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
		// onStart();
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
		assertThatThrownBy(() -> McpServer.sync((ServerMcpTransport) null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport must not be null");

		assertThatThrownBy(() -> McpServer.sync(createMcpTransport()).serverInfo(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Server info must not be null");
	}

	@Test
	void testGracefulShutdown() {
		var mcpSyncServer = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testImmediateClose() {
		var mcpSyncServer = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.close()).doesNotThrowAnyException();
	}

	@Test
	void testGetAsyncServer() {
		var mcpSyncServer = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThat(mcpSyncServer.getAsyncServer()).isNotNull();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
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
		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		Tool newTool = new McpSchema.Tool("new-tool", "New test tool", emptyJsonSchema);
		assertThatCode(() -> mcpSyncServer
			.addTool(new McpServerFeatures.SyncToolRegistration(newTool, args -> new CallToolResult(List.of(), false))))
			.doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddDuplicateTool() {
		Tool duplicateTool = new McpSchema.Tool(TEST_TOOL_NAME, "Duplicate tool", emptyJsonSchema);

		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(duplicateTool, args -> new CallToolResult(List.of(), false))
			.build();

		assertThatThrownBy(() -> mcpSyncServer.addTool(new McpServerFeatures.SyncToolRegistration(duplicateTool,
				args -> new CallToolResult(List.of(), false))))
			.isInstanceOf(McpError.class)
			.hasMessage("Tool with name '" + TEST_TOOL_NAME + "' already exists");

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testRemoveTool() {
		Tool tool = new McpSchema.Tool(TEST_TOOL_NAME, "Test tool", emptyJsonSchema);

		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(tool, args -> new CallToolResult(List.of(), false))
			.build();

		assertThatCode(() -> mcpSyncServer.removeTool(TEST_TOOL_NAME)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentTool() {
		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.removeTool("nonexistent-tool")).isInstanceOf(McpError.class)
			.hasMessage("Tool with name 'nonexistent-tool' not found");

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testNotifyToolsListChanged() {
		var mcpSyncServer = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.notifyToolsListChanged()).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resources Tests
	// ---------------------------------------

	@Test
	void testNotifyResourcesListChanged() {
		var mcpSyncServer = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.notifyResourcesListChanged()).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddResource() {
		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "text/plain", "Test resource description",
				null);
		McpServerFeatures.SyncResourceRegistration registration = new McpServerFeatures.SyncResourceRegistration(
				resource, req -> new ReadResourceResult(List.of()));

		assertThatCode(() -> mcpSyncServer.addResource(registration)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithNullRegistration() {
		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.addResource((McpServerFeatures.SyncResourceRegistration) null))
			.isInstanceOf(McpError.class)
			.hasMessage("Resource must not be null");

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithoutCapability() {
		var serverWithoutResources = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "text/plain", "Test resource description",
				null);
		McpServerFeatures.SyncResourceRegistration registration = new McpServerFeatures.SyncResourceRegistration(
				resource, req -> new ReadResourceResult(List.of()));

		assertThatThrownBy(() -> serverWithoutResources.addResource(registration)).isInstanceOf(McpError.class)
			.hasMessage("Server must be configured with resource capabilities");
	}

	@Test
	void testRemoveResourceWithoutCapability() {
		var serverWithoutResources = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThatThrownBy(() -> serverWithoutResources.removeResource(TEST_RESOURCE_URI)).isInstanceOf(McpError.class)
			.hasMessage("Server must be configured with resource capabilities");
	}

	// ---------------------------------------
	// Prompts Tests
	// ---------------------------------------

	@Test
	void testNotifyPromptsListChanged() {
		var mcpSyncServer = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.notifyPromptsListChanged()).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddPromptWithNullRegistration() {
		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(false).build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.addPrompt((McpServerFeatures.SyncPromptRegistration) null))
			.isInstanceOf(McpError.class)
			.hasMessage("Prompt registration must not be null");
	}

	@Test
	void testAddPromptWithoutCapability() {
		var serverWithoutPrompts = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", List.of());
		McpServerFeatures.SyncPromptRegistration registration = new McpServerFeatures.SyncPromptRegistration(prompt,
				req -> new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content")))));

		assertThatThrownBy(() -> serverWithoutPrompts.addPrompt(registration)).isInstanceOf(McpError.class)
			.hasMessage("Server must be configured with prompt capabilities");
	}

	@Test
	void testRemovePromptWithoutCapability() {
		var serverWithoutPrompts = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThatThrownBy(() -> serverWithoutPrompts.removePrompt(TEST_PROMPT_NAME)).isInstanceOf(McpError.class)
			.hasMessage("Server must be configured with prompt capabilities");
	}

	@Test
	void testRemovePrompt() {
		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", List.of());
		McpServerFeatures.SyncPromptRegistration registration = new McpServerFeatures.SyncPromptRegistration(prompt,
				req -> new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content")))));

		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.prompts(registration)
			.build();

		assertThatCode(() -> mcpSyncServer.removePrompt(TEST_PROMPT_NAME)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentPrompt() {
		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.removePrompt("nonexistent-prompt")).isInstanceOf(McpError.class)
			.hasMessage("Prompt with name 'nonexistent-prompt' not found");

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------

	@Test
	void testRootsChangeConsumers() {
		// Test with single consumer
		var rootsReceived = new McpSchema.Root[1];
		var consumerCalled = new boolean[1];

		var singleConsumerServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.rootsChangeConsumers(List.of(roots -> {
				consumerCalled[0] = true;
				if (!roots.isEmpty()) {
					rootsReceived[0] = roots.get(0);
				}
			}))
			.build();

		assertThat(singleConsumerServer).isNotNull();
		assertThatCode(() -> singleConsumerServer.closeGracefully()).doesNotThrowAnyException();
		onClose();

		// Test with multiple consumers
		var consumer1Called = new boolean[1];
		var consumer2Called = new boolean[1];
		var rootsContent = new List[1];

		var multipleConsumersServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.rootsChangeConsumers(List.of(roots -> {
				consumer1Called[0] = true;
				rootsContent[0] = roots;
			}, roots -> consumer2Called[0] = true))
			.build();

		assertThat(multipleConsumersServer).isNotNull();
		assertThatCode(() -> multipleConsumersServer.closeGracefully()).doesNotThrowAnyException();
		onClose();

		// Test error handling
		var errorHandlingServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.rootsChangeConsumers(List.of(roots -> {
				throw new RuntimeException("Test error");
			}))
			.build();

		assertThat(errorHandlingServer).isNotNull();
		assertThatCode(() -> errorHandlingServer.closeGracefully()).doesNotThrowAnyException();
		onClose();

		// Test without consumers
		var noConsumersServer = McpServer.sync(createMcpTransport()).serverInfo("test-server", "1.0.0").build();

		assertThat(noConsumersServer).isNotNull();
		assertThatCode(() -> noConsumersServer.closeGracefully()).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@Test
	void testLoggingLevels() {
		var mcpSyncServer = McpServer.sync(createMcpTransport())
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

			assertThatCode(() -> mcpSyncServer.loggingNotification(notification)).doesNotThrowAnyException();
		}
	}

	@Test
	void testLoggingWithoutCapability() {
		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().build()) // No logging capability
			.build();

		var notification = McpSchema.LoggingMessageNotification.builder()
			.level(McpSchema.LoggingLevel.INFO)
			.logger("test-logger")
			.data("Test log message")
			.build();

		assertThatCode(() -> mcpSyncServer.loggingNotification(notification)).doesNotThrowAnyException();
	}

	@Test
	void testLoggingWithNullNotification() {
		var mcpSyncServer = McpServer.sync(createMcpTransport())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().logging().build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.loggingNotification(null)).isInstanceOf(McpError.class);
	}

}
