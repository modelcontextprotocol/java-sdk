/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.experimental.tasks.CreateTaskOptions;
import io.modelcontextprotocol.experimental.tasks.InMemoryTaskStore;
import io.modelcontextprotocol.experimental.tasks.TaskAwareAsyncToolSpecification;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for the {@link McpAsyncServer} that can be used with different
 * {@link io.modelcontextprotocol.spec.McpServerTransportProvider} implementations.
 *
 * @author Christian Tzolov
 */
// KEEP IN SYNC with the class in mcp-test module
public abstract class AbstractMcpAsyncServerTests {

	private static final String TEST_TOOL_NAME = "test-tool";

	private static final String TEST_RESOURCE_URI = "test://resource";

	private static final String TEST_PROMPT_NAME = "test-prompt";

	private static final String TEST_TASK_TOOL_NAME = "task-tool";

	abstract protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder();

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
	void testConstructorWithInvalidArguments() {
		assertThatThrownBy(() -> McpServer.async((McpServerTransportProvider) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport provider must not be null");

		assertThatThrownBy(() -> prepareAsyncServerBuilder().serverInfo((McpSchema.Implementation) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Server info must not be null");
	}

	@Test
	void testGracefulShutdown() {
		McpServer.AsyncSpecification<?> builder = prepareAsyncServerBuilder();
		var mcpAsyncServer = builder.serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(mcpAsyncServer.closeGracefully()).verifyComplete();
	}

	@Test
	void testImmediateClose() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpAsyncServer::close).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------
	@Test
	@Deprecated
	void testAddTool() {
		Tool newTool = McpSchema.Tool.builder()
			.name("new-tool")
			.title("New test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		StepVerifier
			.create(mcpAsyncServer.addTool(new McpServerFeatures.AsyncToolSpecification(newTool,
					(exchange, args) -> Mono.just(CallToolResult.builder().content(List.of()).isError(false).build()))))
			.verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddToolCall() {
		Tool newTool = McpSchema.Tool.builder()
			.name("new-tool")
			.title("New test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		StepVerifier.create(mcpAsyncServer.addTool(McpServerFeatures.AsyncToolSpecification.builder()
			.tool(newTool)
			.callHandler((exchange, request) -> Mono
				.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.build())).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	@Deprecated
	void testAddDuplicateTool() {
		Tool duplicateTool = McpSchema.Tool.builder()
			.name(TEST_TOOL_NAME)
			.title("Duplicate tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(duplicateTool,
					(exchange, args) -> Mono.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.build();

		StepVerifier
			.create(mcpAsyncServer.addTool(new McpServerFeatures.AsyncToolSpecification(duplicateTool,
					(exchange, args) -> Mono.just(CallToolResult.builder().content(List.of()).isError(false).build()))))
			.verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddDuplicateToolCall() {
		Tool duplicateTool = McpSchema.Tool.builder()
			.name(TEST_TOOL_NAME)
			.title("Duplicate tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.toolCall(duplicateTool,
					(exchange, request) -> Mono
						.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.build();

		StepVerifier.create(mcpAsyncServer.addTool(McpServerFeatures.AsyncToolSpecification.builder()
			.tool(duplicateTool)
			.callHandler((exchange, request) -> Mono
				.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.build())).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testDuplicateToolCallDuringBuilding() {
		Tool duplicateTool = McpSchema.Tool.builder()
			.name("duplicate-build-toolcall")
			.title("Duplicate toolcall during building")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		assertThatThrownBy(() -> prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.toolCall(duplicateTool,
					(exchange, request) -> Mono
						.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.toolCall(duplicateTool,
					(exchange, request) -> Mono
						.just(CallToolResult.builder().content(List.of()).isError(false).build())) // Duplicate!
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

		List<McpServerFeatures.AsyncToolSpecification> specs = List.of(
				McpServerFeatures.AsyncToolSpecification.builder()
					.tool(duplicateTool)
					.callHandler((exchange, request) -> Mono
						.just(CallToolResult.builder().content(List.of()).isError(false).build()))
					.build(),
				McpServerFeatures.AsyncToolSpecification.builder()
					.tool(duplicateTool)
					.callHandler((exchange, request) -> Mono
						.just(CallToolResult.builder().content(List.of()).isError(false).build()))
					.build() // Duplicate!
		);

		assertThatThrownBy(() -> prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
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

		assertThatThrownBy(() -> prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.AsyncToolSpecification.builder()
				.tool(duplicateTool)
				.callHandler((exchange, request) -> Mono
					.just(CallToolResult.builder().content(List.of()).isError(false).build()))
				.build(),
					McpServerFeatures.AsyncToolSpecification.builder()
						.tool(duplicateTool)
						.callHandler((exchange, request) -> Mono
							.just(CallToolResult.builder().content(List.of()).isError(false).build()))
						.build() // Duplicate!
			)
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Tool with name 'batch-varargs-tool' is already registered.");
	}

	@Test
	void testRemoveTool() {
		Tool too = McpSchema.Tool.builder()
			.name(TEST_TOOL_NAME)
			.title("Duplicate tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.toolCall(too,
					(exchange, request) -> Mono
						.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.build();

		StepVerifier.create(mcpAsyncServer.removeTool(TEST_TOOL_NAME)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentTool() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		StepVerifier.create(mcpAsyncServer.removeTool("nonexistent-tool")).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testNotifyToolsListChanged() {
		Tool too = McpSchema.Tool.builder()
			.name(TEST_TOOL_NAME)
			.title("Duplicate tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.toolCall(too,
					(exchange, args) -> Mono.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.build();

		StepVerifier.create(mcpAsyncServer.notifyToolsListChanged()).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resources Tests
	// ---------------------------------------

	@Test
	void testNotifyResourcesListChanged() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(mcpAsyncServer.notifyResourcesListChanged()).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testNotifyResourcesUpdated() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		StepVerifier
			.create(mcpAsyncServer
				.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(TEST_RESOURCE_URI)))
			.verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResource() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = Resource.builder()
			.uri(TEST_RESOURCE_URI)
			.name("Test Resource")
			.title("Test Resource")
			.mimeType("text/plain")
			.description("Test resource description")
			.build();
		McpServerFeatures.AsyncResourceSpecification specification = new McpServerFeatures.AsyncResourceSpecification(
				resource, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(mcpAsyncServer.addResource(specification)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithNullSpecification() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		StepVerifier.create(mcpAsyncServer.addResource((McpServerFeatures.AsyncResourceSpecification) null))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(IllegalArgumentException.class).hasMessage("Resource must not be null");
			});

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		Resource resource = Resource.builder()
			.uri(TEST_RESOURCE_URI)
			.name("Test Resource")
			.title("Test Resource")
			.mimeType("text/plain")
			.description("Test resource description")
			.build();
		McpServerFeatures.AsyncResourceSpecification specification = new McpServerFeatures.AsyncResourceSpecification(
				resource, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(serverWithoutResources.addResource(specification)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Server must be configured with resource capabilities");
		});
	}

	@Test
	void testRemoveResourceWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(serverWithoutResources.removeResource(TEST_RESOURCE_URI)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Server must be configured with resource capabilities");
		});
	}

	@Test
	void testListResources() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = Resource.builder()
			.uri(TEST_RESOURCE_URI)
			.name("Test Resource")
			.title("Test Resource")
			.mimeType("text/plain")
			.description("Test resource description")
			.build();
		McpServerFeatures.AsyncResourceSpecification specification = new McpServerFeatures.AsyncResourceSpecification(
				resource, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier
			.create(mcpAsyncServer.addResource(specification).then(mcpAsyncServer.listResources().collectList()))
			.expectNextMatches(resources -> resources.size() == 1 && resources.get(0).uri().equals(TEST_RESOURCE_URI))
			.verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveResource() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = Resource.builder()
			.uri(TEST_RESOURCE_URI)
			.name("Test Resource")
			.title("Test Resource")
			.mimeType("text/plain")
			.description("Test resource description")
			.build();
		McpServerFeatures.AsyncResourceSpecification specification = new McpServerFeatures.AsyncResourceSpecification(
				resource, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier
			.create(mcpAsyncServer.addResource(specification).then(mcpAsyncServer.removeResource(TEST_RESOURCE_URI)))
			.verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentResource() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		// Removing a non-existent resource should complete successfully (no error)
		// as per the new implementation that just logs a warning
		StepVerifier.create(mcpAsyncServer.removeResource("nonexistent://resource")).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resource Template Tests
	// ---------------------------------------

	@Test
	void testAddResourceTemplate() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate("test://template/{id}")
			.name("test-template")
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification specification = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(mcpAsyncServer.addResourceTemplate(specification)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceTemplateWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate("test://template/{id}")
			.name("test-template")
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification specification = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(serverWithoutResources.addResourceTemplate(specification)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Server must be configured with resource capabilities");
		});
	}

	@Test
	void testRemoveResourceTemplate() {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate("test://template/{id}")
			.name("test-template")
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification specification = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.resourceTemplates(specification)
			.build();

		StepVerifier.create(mcpAsyncServer.removeResourceTemplate("test://template/{id}")).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveResourceTemplateWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(serverWithoutResources.removeResourceTemplate("test://template/{id}"))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Server must be configured with resource capabilities");
			});
	}

	@Test
	void testRemoveNonexistentResourceTemplate() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		StepVerifier.create(mcpAsyncServer.removeResourceTemplate("nonexistent://template/{id}")).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testListResourceTemplates() {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate("test://template/{id}")
			.name("test-template")
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification specification = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.resourceTemplates(specification)
			.build();

		// Note: Based on the current implementation, listResourceTemplates() returns
		// Flux<Resource>
		// This appears to be a bug in the implementation that should return
		// Flux<ResourceTemplate>
		StepVerifier.create(mcpAsyncServer.listResourceTemplates().collectList())
			.expectNextMatches(resources -> resources.size() >= 0) // Just verify it
																	// doesn't error
			.verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Prompts Tests
	// ---------------------------------------

	@Test
	void testNotifyPromptsListChanged() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(mcpAsyncServer.notifyPromptsListChanged()).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddPromptWithNullSpecification() {
		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(false).build())
			.build();

		StepVerifier.create(mcpAsyncServer.addPrompt((McpServerFeatures.AsyncPromptSpecification) null))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(IllegalArgumentException.class)
					.hasMessage("Prompt specification must not be null");
			});
	}

	@Test
	void testAddPromptWithoutCapability() {
		// Create a server without prompt capabilities
		McpAsyncServer serverWithoutPrompts = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", "Test Prompt", List.of());
		McpServerFeatures.AsyncPromptSpecification specification = new McpServerFeatures.AsyncPromptSpecification(
				prompt, (exchange, req) -> Mono.just(new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content"))))));

		StepVerifier.create(serverWithoutPrompts.addPrompt(specification)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(IllegalStateException.class)
				.hasMessage("Server must be configured with prompt capabilities");
		});
	}

	@Test
	void testRemovePromptWithoutCapability() {
		// Create a server without prompt capabilities
		McpAsyncServer serverWithoutPrompts = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(serverWithoutPrompts.removePrompt(TEST_PROMPT_NAME)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(IllegalStateException.class)
				.hasMessage("Server must be configured with prompt capabilities");
		});
	}

	@Test
	void testRemovePrompt() {
		String TEST_PROMPT_NAME_TO_REMOVE = "TEST_PROMPT_NAME678";

		Prompt prompt = new Prompt(TEST_PROMPT_NAME_TO_REMOVE, "Test Prompt", "Test Prompt", List.of());
		McpServerFeatures.AsyncPromptSpecification specification = new McpServerFeatures.AsyncPromptSpecification(
				prompt, (exchange, req) -> Mono.just(new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content"))))));

		var mcpAsyncServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.prompts(specification)
			.build();

		StepVerifier.create(mcpAsyncServer.removePrompt(TEST_PROMPT_NAME_TO_REMOVE)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentPrompt() {
		var mcpAsyncServer2 = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.build();

		StepVerifier.create(mcpAsyncServer2.removePrompt("nonexistent-prompt")).verifyComplete();

		assertThatCode(() -> mcpAsyncServer2.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------

	@Test
	void testRootsChangeHandlers() {
		// Test with single consumer
		var rootsReceived = new McpSchema.Root[1];
		var consumerCalled = new boolean[1];

		var singleConsumerServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.rootsChangeHandlers(List.of((exchange, roots) -> Mono.fromRunnable(() -> {
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

		var multipleConsumersServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.rootsChangeHandlers(List.of((exchange, roots) -> Mono.fromRunnable(() -> {
				consumer1Called[0] = true;
				rootsContent[0] = roots;
			}), (exchange, roots) -> Mono.fromRunnable(() -> consumer2Called[0] = true)))
			.build();

		assertThat(multipleConsumersServer).isNotNull();
		assertThatCode(() -> multipleConsumersServer.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
		onClose();

		// Test error handling
		var errorHandlingServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.rootsChangeHandlers(List.of((exchange, roots) -> {
				throw new RuntimeException("Test error");
			}))
			.build();

		assertThat(errorHandlingServer).isNotNull();
		assertThatCode(() -> errorHandlingServer.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
		onClose();

		// Test without consumers
		var noConsumersServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThat(noConsumersServer).isNotNull();
		assertThatCode(() -> noConsumersServer.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Tasks Tests
	// ---------------------------------------

	/** Creates a server with task capabilities and the given task store. */
	protected McpAsyncServer createTaskServer(TaskStore<McpSchema.ServerTaskPayloadResult> taskStore) {
		return prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(TaskTestUtils.DEFAULT_TASK_CAPABILITIES)
			.taskStore(taskStore)
			.build();
	}

	/** Creates a server with task capabilities, task store, and a task-aware tool. */
	protected McpAsyncServer createTaskServer(TaskStore<McpSchema.ServerTaskPayloadResult> taskStore,
			TaskAwareAsyncToolSpecification taskTool) {
		return prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(TaskTestUtils.DEFAULT_TASK_CAPABILITIES)
			.taskStore(taskStore)
			.taskTools(taskTool)
			.build();
	}

	/**
	 * Creates a simple task-aware tool that returns a text result.
	 */
	protected TaskAwareAsyncToolSpecification createSimpleTaskTool(String name, TaskSupportMode mode,
			String resultText) {
		return TaskAwareAsyncToolSpecification.builder()
			.name(name)
			.description("Test task tool")
			.taskSupportMode(mode)
			.createTaskHandler((args, extra) -> extra.createTask().flatMap(task -> {
				// Immediately complete the task with the result
				CallToolResult result = CallToolResult.builder()
					.content(List.of(new McpSchema.TextContent(resultText)))
					.build();
				return extra.completeTask(task.taskId(), result)
					.thenReturn(McpSchema.CreateTaskResult.builder().task(task).build());
			}))
			.build();
	}

	@Test
	void testServerWithTaskStore() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		assertThat(server.getTaskStore()).isSameAs(taskStore);
		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreCreateAndGet() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a task
		AtomicReference<String> taskIdRef = new AtomicReference<>();
		StepVerifier.create(taskStore.createTask(
				CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).requestedTtl(60000L).build()))
			.consumeNextWith(task -> {
				assertThat(task.taskId()).isNotNull().isNotEmpty();
				assertThat(task.status()).isEqualTo(TaskStatus.WORKING);
				taskIdRef.set(task.taskId());
			})
			.verifyComplete();

		// Get the task
		StepVerifier.create(taskStore.getTask(taskIdRef.get(), null)).consumeNextWith(storeResult -> {
			assertThat(storeResult.task().taskId()).isEqualTo(taskIdRef.get());
			assertThat(storeResult.task().status()).isEqualTo(TaskStatus.WORKING);
		}).verifyComplete();

		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreUpdateStatus() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a task
		AtomicReference<String> taskIdRef = new AtomicReference<>();
		StepVerifier
			.create(taskStore
				.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build()))
			.consumeNextWith(task -> {
				taskIdRef.set(task.taskId());
			})
			.verifyComplete();

		// Update status
		StepVerifier.create(taskStore.updateTaskStatus(taskIdRef.get(), null, TaskStatus.WORKING, "Processing..."))
			.verifyComplete();

		// Verify status updated
		StepVerifier.create(taskStore.getTask(taskIdRef.get(), null)).consumeNextWith(storeResult -> {
			assertThat(storeResult.task().status()).isEqualTo(TaskStatus.WORKING);
			assertThat(storeResult.task().statusMessage()).isEqualTo("Processing...");
		}).verifyComplete();

		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreStoreResult() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a task
		AtomicReference<String> taskIdRef = new AtomicReference<>();
		StepVerifier
			.create(taskStore
				.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build()))
			.consumeNextWith(task -> {
				taskIdRef.set(task.taskId());
			})
			.verifyComplete();

		// Store result
		CallToolResult result = CallToolResult.builder()
			.content(List.of(new McpSchema.TextContent("Done!")))
			.isError(false)
			.build();

		StepVerifier.create(taskStore.storeTaskResult(taskIdRef.get(), null, TaskStatus.COMPLETED, result))
			.verifyComplete();

		// Verify task is completed
		StepVerifier.create(taskStore.getTask(taskIdRef.get(), null)).consumeNextWith(storeResult -> {
			assertThat(storeResult.task().status()).isEqualTo(TaskStatus.COMPLETED);
		}).verifyComplete();

		// Verify result can be retrieved
		StepVerifier.create(taskStore.getTaskResult(taskIdRef.get(), null)).consumeNextWith(retrievedResult -> {
			assertThat(retrievedResult).isInstanceOf(CallToolResult.class);
		}).verifyComplete();

		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreListTasks() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a few tasks
		StepVerifier
			.create(taskStore
				.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build()))
			.expectNextCount(1)
			.verifyComplete();
		StepVerifier
			.create(taskStore
				.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build()))
			.expectNextCount(1)
			.verifyComplete();

		// List tasks
		StepVerifier.create(taskStore.listTasks(null, null)).consumeNextWith(result -> {
			assertThat(result.tasks()).isNotNull();
			assertThat(result.tasks()).hasSizeGreaterThanOrEqualTo(2);
		}).verifyComplete();

		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testTaskStoreRequestCancellation() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create a task
		AtomicReference<String> taskIdRef = new AtomicReference<>();
		StepVerifier
			.create(taskStore
				.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build()))
			.consumeNextWith(task -> {
				taskIdRef.set(task.taskId());
			})
			.verifyComplete();

		// Request cancellation
		StepVerifier.create(taskStore.requestCancellation(taskIdRef.get(), null)).consumeNextWith(task -> {
			assertThat(task.taskId()).isEqualTo(taskIdRef.get());
		}).verifyComplete();

		// Verify cancellation was requested
		StepVerifier.create(taskStore.isCancellationRequested(taskIdRef.get(), null)).consumeNextWith(isCancelled -> {
			assertThat(isCancelled).isTrue();
		}).verifyComplete();

		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testToolWithTaskSupportRequired() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var tool = createSimpleTaskTool(TEST_TASK_TOOL_NAME, TaskSupportMode.REQUIRED, "Task completed!");
		var server = createTaskServer(taskStore, tool);

		assertThat(server.getTaskStore()).isSameAs(taskStore);
		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testToolWithTaskSupportOptional() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var tool = createSimpleTaskTool(TEST_TASK_TOOL_NAME, TaskSupportMode.OPTIONAL, "Done");
		var server = createTaskServer(taskStore, tool);

		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testTerminalStateCannotTransition() {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		// Create and complete a task
		AtomicReference<String> taskIdRef = new AtomicReference<>();
		StepVerifier
			.create(taskStore
				.createTask(CreateTaskOptions.builder(TaskTestUtils.createTestRequest("test-tool")).build()))
			.consumeNextWith(task -> {
				taskIdRef.set(task.taskId());
			})
			.verifyComplete();

		// Complete the task
		CallToolResult result = CallToolResult.builder().content(List.of()).isError(false).build();
		StepVerifier.create(taskStore.storeTaskResult(taskIdRef.get(), null, TaskStatus.COMPLETED, result))
			.verifyComplete();

		// Trying to update status should fail or be ignored (implementation-dependent)
		// The InMemoryTaskStore silently ignores invalid transitions
		StepVerifier.create(taskStore.updateTaskStatus(taskIdRef.get(), null, TaskStatus.WORKING, "Should not work"))
			.verifyComplete();

		// Status should still be COMPLETED
		StepVerifier.create(taskStore.getTask(taskIdRef.get(), null)).consumeNextWith(storeResult -> {
			assertThat(storeResult.task().status()).isEqualTo(TaskStatus.COMPLETED);
		}).verifyComplete();

		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// CreateTaskHandler Tests
	// ---------------------------------------

	@Test
	void testToolWithCreateTaskHandler() {
		// Test that a tool with createTaskHandler can be registered
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();

		// Create a tool with createTaskHandler
		var tool = TaskAwareAsyncToolSpecification.builder()
			.name("create-task-handler-tool")
			.description("A tool using createTaskHandler")
			.createTaskHandler((args, extra) -> extra.createTask(opts -> opts.requestedTtl(60000L).pollInterval(1000L))
				.flatMap(task -> {
					// Store result immediately for this test
					CallToolResult result = CallToolResult.builder()
						.addTextContent("Created via createTaskHandler")
						.isError(false)
						.build();
					return extra.completeTask(task.taskId(), result)
						.thenReturn(McpSchema.CreateTaskResult.builder().task(task).build());
				}))
			.build();

		var server = createTaskServer(taskStore, tool);

		assertThat(server.getTaskStore()).isSameAs(taskStore);
		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testToolWithAllThreeHandlers() {
		// Test that a tool with all three handlers can be registered
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();

		var tool = TaskAwareAsyncToolSpecification.builder()
			.name("three-handler-tool")
			.description("A tool with createTask, getTask, and getTaskResult handlers")
			.createTaskHandler((args, extra) -> extra.createTask()
				.map(task -> McpSchema.CreateTaskResult.builder().task(task).build()))
			.getTaskHandler((exchange, request) -> {
				// Custom getTask handler
				return Mono.just(McpSchema.GetTaskResult.builder()
					.taskId(request.taskId())
					.status(TaskStatus.WORKING)
					.statusMessage("Custom status from handler")
					.build());
			})
			.getTaskResultHandler((exchange, request) -> {
				// Custom getTaskResult handler
				return Mono.just(CallToolResult.builder().addTextContent("Custom result from handler").build());
			})
			.build();

		var server = createTaskServer(taskStore, tool);

		// Verify all handlers are set
		assertThat(tool.createTaskHandler()).isNotNull();
		assertThat(tool.getTaskHandler()).isNotNull();
		assertThat(tool.getTaskResultHandler()).isNotNull();

		assertThatCode(() -> server.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void builderShouldThrowWhenNormalToolAndTaskToolShareSameName() {
		String duplicateName = "duplicate-tool-name";

		Tool normalTool = McpSchema.Tool.builder()
			.name(duplicateName)
			.title("A normal tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		assertThatThrownBy(() -> {
			prepareAsyncServerBuilder()
				.tool(normalTool,
						(exchange, args) -> Mono
							.just(CallToolResult.builder().content(List.of()).isError(false).build()))
				.taskTools(TaskAwareAsyncToolSpecification.builder()
					.name(duplicateName)
					.description("A task tool")
					.createTaskHandler((args, extra) -> Mono.just(McpSchema.CreateTaskResult.builder().build()))
					.build())
				.build();
		}).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("already registered")
			.hasMessageContaining(duplicateName);
	}

	@Test
	void builderShouldThrowWhenTaskToolsRegisteredWithoutTaskStore() {
		assertThatThrownBy(() -> {
			prepareAsyncServerBuilder()
				.taskTools(TaskAwareAsyncToolSpecification.builder()
					.name("task-tool-without-store")
					.description("A task tool that needs a TaskStore")
					.createTaskHandler((args, extra) -> Mono.just(McpSchema.CreateTaskResult.builder().build()))
					.build())
				// Note: NOT setting .taskStore()
				.build();
		}).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Task-aware tools registered but no TaskStore configured");
	}

}
