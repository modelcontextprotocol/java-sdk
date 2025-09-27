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
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for the {@link McpSyncServer} that can be used with different
 * {@link McpServerTransportProvider} implementations.
 *
 * @author Christian Tzolov
 */
// KEEP IN SYNC with the class in mcp-test module
public abstract class AbstractMcpSyncServerTests {

	private static final String TEST_TOOL_NAME = "test-tool";

	private static final String TEST_RESOURCE_URI = "test://resource";

	private static final String TEST_PROMPT_NAME = "test-prompt";

	abstract protected McpServer.SyncSpecification<?> prepareSyncServerBuilder();

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
		assertThatThrownBy(() -> McpServer.sync((McpServerTransportProvider) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport provider must not be null");

		assertThatThrownBy(() -> prepareSyncServerBuilder().serverInfo(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Server info must not be null");
	}

	@Test
	void testGracefulShutdown() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testImmediateClose() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.close()).doesNotThrowAnyException();
	}

	@Test
	void testGetAsyncServer() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThat(mcpSyncServer.getAsyncServer()).isNotNull();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

	@Test
	@Deprecated
	void testAddTool() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		Tool newTool = McpSchema.Tool.builder()
			.name("new-tool")
			.title("New test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		assertThatCode(() -> mcpSyncServer.addTool(new McpServerFeatures.SyncToolSpecification(newTool,
				(exchange, args) -> new CallToolResult(List.of(), false))))
			.doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddToolCall() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		Tool newTool = McpSchema.Tool.builder()
			.name("new-tool")
			.title("New test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		assertThatCode(() -> mcpSyncServer.addTool(McpServerFeatures.SyncToolSpecification.builder()
			.tool(newTool)
			.callHandler((exchange, request) -> new CallToolResult(List.of(), false))
			.build())).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	@Deprecated
	void testAddDuplicateTool() {
		Tool duplicateTool = McpSchema.Tool.builder()
			.name(TEST_TOOL_NAME)
			.title("Duplicate tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(duplicateTool, (exchange, args) -> new CallToolResult(List.of(), false))
			.build();

		assertThatThrownBy(() -> mcpSyncServer.addTool(new McpServerFeatures.SyncToolSpecification(duplicateTool,
				(exchange, args) -> new CallToolResult(List.of(), false))))
			.isInstanceOf(McpError.class)
			.hasMessage("Tool with name '" + TEST_TOOL_NAME + "' already exists");

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddDuplicateToolCall() {
		Tool duplicateTool = McpSchema.Tool.builder()
			.name(TEST_TOOL_NAME)
			.title("Duplicate tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.toolCall(duplicateTool, (exchange, request) -> new CallToolResult(List.of(), false))
			.build();

		assertThatThrownBy(() -> mcpSyncServer.addTool(McpServerFeatures.SyncToolSpecification.builder()
			.tool(duplicateTool)
			.callHandler((exchange, request) -> new CallToolResult(List.of(), false))
			.build())).isInstanceOf(McpError.class)
			.hasMessage("Tool with name '" + TEST_TOOL_NAME + "' already exists");

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testDuplicateToolCallDuringBuilding() {
		Tool duplicateTool = McpSchema.Tool.builder()
			.name("duplicate-build-toolcall")
			.title("Duplicate toolcall during building")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		assertThatThrownBy(() -> prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.toolCall(duplicateTool, (exchange, request) -> new CallToolResult(List.of(), false))
			.toolCall(duplicateTool, (exchange, request) -> new CallToolResult(List.of(), false)) // Duplicate!
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Tool with name 'duplicate-build-toolcall' is already registered.");
	}

	@Test
	void testDuplicateToolsInBatchListRegistration() {
		Tool duplicateTool = McpSchema.Tool.builder()
			.name("batch-list-tool")
			.title("Duplicate tool in batch list")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		List<McpServerFeatures.SyncToolSpecification> specs = List.of(
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(duplicateTool)
					.callHandler((exchange, request) -> new CallToolResult(List.of(), false))
					.build(),
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(duplicateTool)
					.callHandler((exchange, request) -> new CallToolResult(List.of(), false))
					.build() // Duplicate!
		);

		assertThatThrownBy(() -> prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(specs)
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Tool with name 'batch-list-tool' is already registered.");
	}

	@Test
	void testDuplicateToolsInBatchVarargsRegistration() {
		Tool duplicateTool = McpSchema.Tool.builder()
			.name("batch-varargs-tool")
			.title("Duplicate tool in batch varargs")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		assertThatThrownBy(() -> prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.SyncToolSpecification.builder()
				.tool(duplicateTool)
				.callHandler((exchange, request) -> new CallToolResult(List.of(), false))
				.build(),
					McpServerFeatures.SyncToolSpecification.builder()
						.tool(duplicateTool)
						.callHandler((exchange, request) -> new CallToolResult(List.of(), false))
						.build() // Duplicate!
			)
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Tool with name 'batch-varargs-tool' is already registered.");
	}

	@Test
	void testRemoveTool() {
		Tool tool = McpSchema.Tool.builder()
			.name(TEST_TOOL_NAME)
			.title("Test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.toolCall(tool, (exchange, args) -> new CallToolResult(List.of(), false))
			.build();

		assertThatCode(() -> mcpSyncServer.removeTool(TEST_TOOL_NAME)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentTool() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.removeTool("nonexistent-tool")).isInstanceOf(McpError.class)
			.hasMessage("Tool with name 'nonexistent-tool' not found");

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testNotifyToolsListChanged() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.notifyToolsListChanged()).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resources Tests
	// ---------------------------------------

	@Test
	void testNotifyResourcesListChanged() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.notifyResourcesListChanged()).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testNotifyResourcesUpdated() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer
			.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(TEST_RESOURCE_URI)))
			.doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddResource() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "text/plain", "Test resource description",
				null);
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(List.of()));

		assertThatCode(() -> mcpSyncServer.addResource(specification)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithNullSpecification() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.addResource((McpServerFeatures.SyncResourceSpecification) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Resource must not be null");

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithoutCapability() {
		var serverWithoutResources = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "text/plain", "Test resource description",
				null);
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(List.of()));

		assertThatThrownBy(() -> serverWithoutResources.addResource(specification))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Server must be configured with resource capabilities");
	}

	@Test
	void testRemoveResourceWithoutCapability() {
		var serverWithoutResources = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatThrownBy(() -> serverWithoutResources.removeResource(TEST_RESOURCE_URI))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Server must be configured with resource capabilities");
	}

	@Test
	void testListResources() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "text/plain", "Test resource description",
				null);
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(List.of()));

		mcpSyncServer.addResource(specification);
		List<McpSchema.Resource> resources = mcpSyncServer.listResources();

		assertThat(resources).hasSize(1);
		assertThat(resources.get(0).uri()).isEqualTo(TEST_RESOURCE_URI);

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testRemoveResource() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "text/plain", "Test resource description",
				null);
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(List.of()));

		mcpSyncServer.addResource(specification);
		assertThatCode(() -> mcpSyncServer.removeResource(TEST_RESOURCE_URI)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentResource() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		// Removing a non-existent resource should complete successfully (no error)
		// as per the new implementation that just logs a warning
		assertThatCode(() -> mcpSyncServer.removeResource("nonexistent://resource")).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resource Template Tests
	// ---------------------------------------

	@Test
	void testAddResourceTemplate() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate("test://template/{id}")
			.name("test-template")
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(List.of()));

		assertThatCode(() -> mcpSyncServer.addResourceTemplate(specification)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceTemplateWithoutCapability() {
		// Create a server without resource capabilities
		var serverWithoutResources = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate("test://template/{id}")
			.name("test-template")
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(List.of()));

		assertThatThrownBy(() -> serverWithoutResources.addResourceTemplate(specification))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Server must be configured with resource capabilities");
	}

	@Test
	void testRemoveResourceTemplate() {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate("test://template/{id}")
			.name("test-template")
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(List.of()));

		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.resourceTemplates(specification)
			.build();

		assertThatCode(() -> mcpSyncServer.removeResourceTemplate("test://template/{id}")).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testRemoveResourceTemplateWithoutCapability() {
		// Create a server without resource capabilities
		var serverWithoutResources = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatThrownBy(() -> serverWithoutResources.removeResourceTemplate("test://template/{id}"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Server must be configured with resource capabilities");
	}

	@Test
	void testRemoveNonexistentResourceTemplate() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		// Removing a non-existent resource template should complete successfully (no
		// error)
		// as per the new implementation that just logs a warning
		assertThatCode(() -> mcpSyncServer.removeResourceTemplate("nonexistent://template/{id}"))
			.doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testListResourceTemplates() {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate("test://template/{id}")
			.name("test-template")
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(List.of()));

		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.resourceTemplates(specification)
			.build();

		List<McpSchema.ResourceTemplate> templates = mcpSyncServer.listResourceTemplates();

		assertThat(templates).isNotNull();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Prompts Tests
	// ---------------------------------------

	@Test
	void testNotifyPromptsListChanged() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer.notifyPromptsListChanged()).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testAddPromptWithNullSpecification() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(false).build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.addPrompt((McpServerFeatures.SyncPromptSpecification) null))
			.isInstanceOf(McpError.class)
			.hasMessage("Prompt specification must not be null");
	}

	@Test
	void testAddPromptWithoutCapability() {
		var serverWithoutPrompts = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", "Test Prompt", List.of());
		McpServerFeatures.SyncPromptSpecification specification = new McpServerFeatures.SyncPromptSpecification(prompt,
				(exchange, req) -> new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content")))));

		assertThatThrownBy(() -> serverWithoutPrompts.addPrompt(specification)).isInstanceOf(McpError.class)
			.hasMessage("Server must be configured with prompt capabilities");
	}

	@Test
	void testRemovePromptWithoutCapability() {
		var serverWithoutPrompts = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatThrownBy(() -> serverWithoutPrompts.removePrompt(TEST_PROMPT_NAME)).isInstanceOf(McpError.class)
			.hasMessage("Server must be configured with prompt capabilities");
	}

	@Test
	void testRemovePrompt() {
		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", "Test Prompt", List.of());
		McpServerFeatures.SyncPromptSpecification specification = new McpServerFeatures.SyncPromptSpecification(prompt,
				(exchange, req) -> new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content")))));

		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.prompts(specification)
			.build();

		assertThatCode(() -> mcpSyncServer.removePrompt(TEST_PROMPT_NAME)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentPrompt() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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
	void testRootsChangeHandlers() {
		// Test with single consumer
		var rootsReceived = new McpSchema.Root[1];
		var consumerCalled = new boolean[1];

		var singleConsumerServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.rootsChangeHandlers(List.of((exchange, roots) -> {
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

		var multipleConsumersServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.rootsChangeHandlers(List.of((exchange, roots) -> {
				consumer1Called[0] = true;
				rootsContent[0] = roots;
			}, (exchange, roots) -> consumer2Called[0] = true))
			.build();

		assertThat(multipleConsumersServer).isNotNull();
		assertThatCode(() -> multipleConsumersServer.closeGracefully()).doesNotThrowAnyException();
		onClose();

		// Test error handling
		var errorHandlingServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.rootsChangeHandlers(List.of((exchange, roots) -> {
				throw new RuntimeException("Test error");
			}))
			.build();

		assertThat(errorHandlingServer).isNotNull();
		assertThatCode(() -> errorHandlingServer.closeGracefully()).doesNotThrowAnyException();
		onClose();

		// Test without consumers
		var noConsumersServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThat(noConsumersServer).isNotNull();
		assertThatCode(() -> noConsumersServer.closeGracefully()).doesNotThrowAnyException();
	}

}
