/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;

import io.modelcontextprotocol.experimental.tasks.CreateTaskOptions;
import io.modelcontextprotocol.experimental.tasks.InMemoryTaskStore;
import io.modelcontextprotocol.experimental.tasks.TaskStore;
import io.modelcontextprotocol.experimental.tasks.TaskTestUtils;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;
import io.modelcontextprotocol.spec.McpSchema.TaskSupportMode;
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

	private static final String TEST_TASK_TOOL_NAME = "task-tool";

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

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testImmediateClose() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpSyncServer::close).doesNotThrowAnyException();
	}

	@Test
	void testGetAsyncServer() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThat(mcpSyncServer.getAsyncServer()).isNotNull();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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
				(exchange, args) -> CallToolResult.builder().content(List.of()).isError(false).build())))
			.doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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
			.callHandler((exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build())
			.build())).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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
			.tool(duplicateTool, (exchange, args) -> CallToolResult.builder().content(List.of()).isError(false).build())
			.build();

		assertThatCode(() -> mcpSyncServer.addTool(new McpServerFeatures.SyncToolSpecification(duplicateTool,
				(exchange, args) -> CallToolResult.builder().content(List.of()).isError(false).build())))
			.doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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
			.toolCall(duplicateTool,
					(exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build())
			.build();

		assertThatCode(() -> mcpSyncServer.addTool(McpServerFeatures.SyncToolSpecification.builder()
			.tool(duplicateTool)
			.callHandler((exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build())
			.build())).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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
			.toolCall(duplicateTool,
					(exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build())
			.toolCall(duplicateTool,
					(exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build()) // Duplicate!
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
					.callHandler(
							(exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build())
					.build(),
				McpServerFeatures.SyncToolSpecification.builder()
					.tool(duplicateTool)
					.callHandler(
							(exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build())
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
				.callHandler((exchange, request) -> CallToolResult.builder().content(List.of()).isError(false).build())
				.build(),
					McpServerFeatures.SyncToolSpecification.builder()
						.tool(duplicateTool)
						.callHandler((exchange,
								request) -> CallToolResult.builder().content(List.of()).isError(false).build())
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
			.toolCall(tool, (exchange, args) -> CallToolResult.builder().content(List.of()).isError(false).build())
			.build();

		assertThatCode(() -> mcpSyncServer.removeTool(TEST_TOOL_NAME)).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentTool() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		assertThatCode(() -> mcpSyncServer.removeTool("nonexistent-tool")).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testNotifyToolsListChanged() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpSyncServer::notifyToolsListChanged).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resources Tests
	// ---------------------------------------

	@Test
	void testNotifyResourcesListChanged() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpSyncServer::notifyResourcesListChanged).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testNotifyResourcesUpdated() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer
			.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(TEST_RESOURCE_URI)))
			.doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddResource() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = Resource.builder()
			.uri(TEST_RESOURCE_URI)
			.name("Test Resource")
			.mimeType("text/plain")
			.description("Test resource description")
			.build();
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(List.of()));

		assertThatCode(() -> mcpSyncServer.addResource(specification)).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithNullSpecification() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.addResource((McpServerFeatures.SyncResourceSpecification) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Resource must not be null");

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithoutCapability() {
		var serverWithoutResources = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		Resource resource = Resource.builder()
			.uri(TEST_RESOURCE_URI)
			.name("Test Resource")
			.mimeType("text/plain")
			.description("Test resource description")
			.build();
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

		Resource resource = Resource.builder()
			.uri(TEST_RESOURCE_URI)
			.name("Test Resource")
			.mimeType("text/plain")
			.description("Test resource description")
			.build();
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(List.of()));

		mcpSyncServer.addResource(specification);
		List<McpSchema.Resource> resources = mcpSyncServer.listResources();

		assertThat(resources).hasSize(1);
		assertThat(resources.get(0).uri()).isEqualTo(TEST_RESOURCE_URI);

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testRemoveResource() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = Resource.builder()
			.uri(TEST_RESOURCE_URI)
			.name("Test Resource")
			.mimeType("text/plain")
			.description("Test resource description")
			.build();
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(List.of()));

		mcpSyncServer.addResource(specification);
		assertThatCode(() -> mcpSyncServer.removeResource(TEST_RESOURCE_URI)).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentResource() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		// Removing a non-existent resource should complete successfully (no error)
		// as per the new implementation that just logs a warning
		assertThatCode(() -> mcpSyncServer.removeResource("nonexistent://resource")).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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

		assertThatCode(() -> mcpSyncServer.removeResourceTemplate("nonexistent://template/{id}"))
			.doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Prompts Tests
	// ---------------------------------------

	@Test
	void testNotifyPromptsListChanged() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpSyncServer::notifyPromptsListChanged).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddPromptWithNullSpecification() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(false).build())
			.build();

		assertThatThrownBy(() -> mcpSyncServer.addPrompt((McpServerFeatures.SyncPromptSpecification) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Prompt specification must not be null");
	}

	@Test
	void testAddPromptWithoutCapability() {
		var serverWithoutPrompts = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", "Test Prompt", List.of());
		McpServerFeatures.SyncPromptSpecification specification = new McpServerFeatures.SyncPromptSpecification(prompt,
				(exchange, req) -> new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content")))));

		assertThatThrownBy(() -> serverWithoutPrompts.addPrompt(specification))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Server must be configured with prompt capabilities");
	}

	@Test
	void testRemovePromptWithoutCapability() {
		var serverWithoutPrompts = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatThrownBy(() -> serverWithoutPrompts.removePrompt(TEST_PROMPT_NAME))
			.isInstanceOf(IllegalStateException.class)
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

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentPrompt() {
		var mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.build();

		assertThatCode(() -> mcpSyncServer.removePrompt("nonexistent://template/{id}")).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
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
		assertThatCode(singleConsumerServer::closeGracefully).doesNotThrowAnyException();
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
		assertThatCode(multipleConsumersServer::closeGracefully).doesNotThrowAnyException();
		onClose();

		// Test error handling
		var errorHandlingServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.rootsChangeHandlers(List.of((exchange, roots) -> {
				throw new RuntimeException("Test error");
			}))
			.build();

		assertThat(errorHandlingServer).isNotNull();
		assertThatCode(errorHandlingServer::closeGracefully).doesNotThrowAnyException();
		onClose();

		// Test without consumers
		var noConsumersServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThat(noConsumersServer).isNotNull();
		assertThatCode(noConsumersServer::closeGracefully).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Tasks Tests
	// ---------------------------------------

	/** Creates a server with task capabilities and the given task store. */
	protected McpSyncServer createTaskServer(TaskStore<McpSchema.ServerTaskPayloadResult> taskStore) {
		return prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(TaskTestUtils.DEFAULT_TASK_CAPABILITIES)
			.taskStore(taskStore)
			.build();
	}

	@Test
	void testServerWithTaskStore() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		assertThat(server.getAsyncServer().getTaskStore()).isSameAs(taskStore);
		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreCreateAndGet() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a task (blocking)
		var task = taskStore.createTask(
				CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).requestedTtl(60000L).build())
			.block();

		assertThat(task).isNotNull();
		assertThat(task.taskId()).isNotNull().isNotEmpty();
		assertThat(task.status()).isEqualTo(TaskStatus.WORKING);

		// Get the task (blocking)
		var storeResult = taskStore.getTask(task.taskId(), null).block();
		var retrievedTask = storeResult.task();

		assertThat(retrievedTask).isNotNull();
		assertThat(retrievedTask.taskId()).isEqualTo(task.taskId());
		assertThat(retrievedTask.status()).isEqualTo(TaskStatus.WORKING);

		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreUpdateStatus() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a task
		var task = taskStore.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build())
			.block();
		assertThat(task).isNotNull();

		// Update status
		taskStore.updateTaskStatus(task.taskId(), null, TaskStatus.WORKING, "Processing...").block();

		// Verify status updated
		var updatedTask = taskStore.getTask(task.taskId(), null).block().task();
		assertThat(updatedTask).isNotNull();
		assertThat(updatedTask.status()).isEqualTo(TaskStatus.WORKING);
		assertThat(updatedTask.statusMessage()).isEqualTo("Processing...");

		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreStoreResult() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a task
		var task = taskStore.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build())
			.block();
		assertThat(task).isNotNull();

		// Store result
		CallToolResult result = CallToolResult.builder()
			.content(List.of(new McpSchema.TextContent("Done!")))
			.isError(false)
			.build();

		taskStore.storeTaskResult(task.taskId(), null, TaskStatus.COMPLETED, result).block();

		// Verify task is completed
		var completedTask = taskStore.getTask(task.taskId(), null).block().task();
		assertThat(completedTask).isNotNull();
		assertThat(completedTask.status()).isEqualTo(TaskStatus.COMPLETED);

		// Verify result can be retrieved
		var retrievedResult = taskStore.getTaskResult(task.taskId(), null).block();
		assertThat(retrievedResult).isNotNull();
		assertThat(retrievedResult).isInstanceOf(CallToolResult.class);

		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreListTasks() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a few tasks
		taskStore.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build()).block();
		taskStore.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build()).block();

		// List tasks
		var listResult = taskStore.listTasks(null, null).block();
		assertThat(listResult).isNotNull();
		assertThat(listResult.tasks()).isNotNull();
		assertThat(listResult.tasks()).hasSizeGreaterThanOrEqualTo(2);

		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreRequestCancellation() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a task
		var task = taskStore.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build())
			.block();
		assertThat(task).isNotNull();

		// Request cancellation
		var cancelledTask = taskStore.requestCancellation(task.taskId(), null).block();
		assertThat(cancelledTask).isNotNull();
		assertThat(cancelledTask.taskId()).isEqualTo(task.taskId());

		// Verify cancellation was requested
		var isCancelled = taskStore.isCancellationRequested(task.taskId(), null).block();
		assertThat(isCancelled).isTrue();

		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testToolWithTaskSupportRequired() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		Tool taskTool = TaskTestUtils.createTaskTool(TEST_TASK_TOOL_NAME, "Task-based tool", TaskSupportMode.REQUIRED);

		var server = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(TaskTestUtils.DEFAULT_TASK_CAPABILITIES)
			.taskStore(taskStore)
			.tool(taskTool,
					(exchange, args) -> CallToolResult.builder()
						.content(List.of(new McpSchema.TextContent("Task completed!")))
						.isError(false)
						.build())
			.build();

		assertThat(server.getAsyncServer().getTaskStore()).isSameAs(taskStore);
		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testToolWithTaskSupportOptional() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		Tool taskTool = TaskTestUtils.createTaskTool(TEST_TASK_TOOL_NAME, "Optional task tool",
				TaskSupportMode.OPTIONAL);

		var server = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(TaskTestUtils.DEFAULT_TASK_CAPABILITIES)
			.taskStore(taskStore)
			.tool(taskTool, (exchange, args) -> CallToolResult.builder().content(List.of()).isError(false).build())
			.build();

		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testTerminalStateCannotTransition() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create and complete a task
		var task = taskStore.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build())
			.block();
		assertThat(task).isNotNull();

		// Complete the task
		CallToolResult result = CallToolResult.builder().content(List.of()).isError(false).build();
		taskStore.storeTaskResult(task.taskId(), null, TaskStatus.COMPLETED, result).block();

		// Trying to update status should fail or be ignored (implementation-dependent)
		// The InMemoryTaskStore silently ignores invalid transitions
		taskStore.updateTaskStatus(task.taskId(), null, TaskStatus.WORKING, "Should not work").block();

		// Status should still be COMPLETED
		var finalTask = taskStore.getTask(task.taskId(), null).block().task();
		assertThat(finalTask).isNotNull();
		assertThat(finalTask.status()).isEqualTo(TaskStatus.COMPLETED);

		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

	/**
	 * Example: Using sync tool specification for external API pattern.
	 *
	 * <p>
	 * This test demonstrates the sync equivalent of the external API pattern shown in
	 * integration tests. The key differences from the async version are:
	 * <ol>
	 * <li>Use {@code TaskAwareSyncToolSpecification} instead of async variant</li>
	 * <li>Handlers return values directly instead of {@code Mono}</li>
	 * <li>Task store calls use {@code .block()} for synchronous execution</li>
	 * </ol>
	 *
	 * <p>
	 * This example shows how to create a task-aware sync tool that wraps an external
	 * async API, demonstrating that the pattern works the same way regardless of whether
	 * you're using the sync or async server API.
	 */
	@Test
	void testSyncExternalApiPatternExample() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();

		// For this example, we simulate an external API call and manually create a
		// task
		// This demonstrates the sync tool pattern equivalent to the async
		// testExternalAsyncApiPattern

		var server = createTaskServer(taskStore);

		// Step 1: Create a task (simulating what a sync createTask handler would do)
		var task = taskStore
			.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("external-job"))
				.taskId("external-job-123")
				.requestedTtl(60000L)
				.build())
			.block();

		assertThat(task).isNotNull();
		assertThat(task.taskId()).isEqualTo("external-job-123");
		assertThat(task.status()).isEqualTo(TaskStatus.WORKING);

		// Step 2: Simulate external API completing the job and storing result
		// storeTaskResult atomically sets the terminal status AND stores the result
		CallToolResult result = CallToolResult.builder()
			.content(List.of(new McpSchema.TextContent("Processed: test-data")))
			.isError(false)
			.build();
		taskStore.storeTaskResult(task.taskId(), null, TaskStatus.COMPLETED, result).block();

		// Verify final state
		var finalTask = taskStore.getTask(task.taskId(), null).block().task();
		assertThat(finalTask).isNotNull();
		assertThat(finalTask.status()).isEqualTo(TaskStatus.COMPLETED);

		var finalResult = taskStore.getTaskResult(task.taskId(), null).block();
		assertThat(finalResult).isNotNull();

		assertThatCode(server::closeGracefully).doesNotThrowAnyException();
	}

}
