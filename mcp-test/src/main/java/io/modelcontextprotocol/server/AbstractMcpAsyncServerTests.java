/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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

	abstract protected McpServerTransportProvider createMcpTransportProvider();

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
		assertThatThrownBy(() -> McpServer.async((McpServerTransportProvider) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport provider must not be null");

		assertThatThrownBy(
				() -> McpServer.async(createMcpTransportProvider()).serverInfo((McpSchema.Implementation) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Server info must not be null");
	}

	@Test
	void testGracefulShutdown() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider()).serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(mcpAsyncServer.closeGracefully()).verifyComplete();
	}

	@Test
	void testImmediateClose() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider()).serverInfo("test-server", "1.0.0").build();

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
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		StepVerifier.create(mcpAsyncServer.addTool(new McpServerFeatures.AsyncToolSpecification(newTool,
				(exchange, args) -> Mono.just(new CallToolResult(List.of(), false)))))
			.verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddDuplicateTool() {
		Tool duplicateTool = new McpSchema.Tool(TEST_TOOL_NAME, "Duplicate tool", emptyJsonSchema);

		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(duplicateTool, (exchange, args) -> Mono.just(new CallToolResult(List.of(), false)))
			.build();

		StepVerifier
			.create(mcpAsyncServer.addTool(new McpServerFeatures.AsyncToolSpecification(duplicateTool,
					(exchange, args) -> Mono.just(new CallToolResult(List.of(), false)))))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpError.class)
					.hasMessage("Tool with name '" + TEST_TOOL_NAME + "' already exists");
			});

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testPutTool() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		Tool toolV1 = new McpSchema.Tool(TEST_TOOL_NAME, "Tool with version 1.0.0", emptyJsonSchema);

		StepVerifier.create(mcpAsyncServer.putTool(new McpServerFeatures.AsyncToolSpecification(toolV1,
				(exchange, args) -> Mono.just(new CallToolResult(List.of(), false)))))
			.verifyComplete();

		Tool toolV2 = new McpSchema.Tool(TEST_TOOL_NAME, "Tool with version 2.0.0", emptyJsonSchema);

		StepVerifier.create(mcpAsyncServer.putTool(new McpServerFeatures.AsyncToolSpecification(toolV2,
				(exchange, args) -> Mono.just(new CallToolResult(List.of(), false)))))
			.verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveTool() {
		Tool too = new McpSchema.Tool(TEST_TOOL_NAME, "Duplicate tool", emptyJsonSchema);

		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(too, (exchange, args) -> Mono.just(new CallToolResult(List.of(), false)))
			.build();

		StepVerifier.create(mcpAsyncServer.removeTool(TEST_TOOL_NAME)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentTool() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
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

		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(too, (exchange, args) -> Mono.just(new CallToolResult(List.of(), false)))
			.build();

		StepVerifier.create(mcpAsyncServer.notifyToolsListChanged()).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resources Tests
	// ---------------------------------------

	@Test
	void testNotifyResourcesListChanged() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider()).serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(mcpAsyncServer.notifyResourcesListChanged()).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResource() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "Test resource description", "text/plain",
				null);
		McpServerFeatures.AsyncResourceSpecification specification = new McpServerFeatures.AsyncResourceSpecification(
				resource, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(mcpAsyncServer.addResource(specification)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithNullSpecification() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		StepVerifier.create(mcpAsyncServer.addResource((McpServerFeatures.AsyncResourceSpecification) null))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpError.class).hasMessage("Resource must not be null");
			});

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.build();

		Resource resource = new Resource(TEST_RESOURCE_URI, "Test Resource", "Test resource description", "text/plain",
				null);
		McpServerFeatures.AsyncResourceSpecification specification = new McpServerFeatures.AsyncResourceSpecification(
				resource, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(serverWithoutResources.addResource(specification)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Server must be configured with resource capabilities");
		});
	}

	@Test
	void testPutResource() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		Resource resourceV1 = new Resource(TEST_RESOURCE_URI, "Test Resource", "Resource with version 1.0.0",
				"text/plain", null);
		McpServerFeatures.AsyncResourceSpecification specificationV1 = new McpServerFeatures.AsyncResourceSpecification(
				resourceV1, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));
		StepVerifier.create(mcpAsyncServer.putResource(specificationV1)).verifyComplete();

		Resource resourceV2 = new Resource(TEST_RESOURCE_URI, "Test Resource", "Resource with version 2.0.0",
				"text/plain", null);
		McpServerFeatures.AsyncResourceSpecification specificationV2 = new McpServerFeatures.AsyncResourceSpecification(
				resourceV2, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));
		StepVerifier.create(mcpAsyncServer.putResource(specificationV2)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveResourceWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = McpServer.async(createMcpTransportProvider())
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
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider()).serverInfo("test-server", "1.0.0").build();

		StepVerifier.create(mcpAsyncServer.notifyPromptsListChanged()).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddPromptWithNullSpecification() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(false).build())
			.build();

		StepVerifier.create(mcpAsyncServer.addPrompt((McpServerFeatures.AsyncPromptSpecification) null))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpError.class).hasMessage("Prompt specification must not be null");
			});
	}

	@Test
	void testAddPromptWithoutCapability() {
		// Create a server without prompt capabilities
		McpAsyncServer serverWithoutPrompts = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.build();

		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", List.of());
		McpServerFeatures.AsyncPromptSpecification specification = new McpServerFeatures.AsyncPromptSpecification(
				prompt, (exchange, req) -> Mono.just(new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content"))))));

		StepVerifier.create(serverWithoutPrompts.addPrompt(specification)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Server must be configured with prompt capabilities");
		});
	}

	@Test
	void testPutPrompt() {
		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(false).build())
			.build();

		Prompt promptV1 = new Prompt(TEST_PROMPT_NAME, "Prompt with version 1.0.0", List.of());
		McpServerFeatures.AsyncPromptSpecification specificationV1 = new McpServerFeatures.AsyncPromptSpecification(
				promptV1, (exchange, req) -> Mono.just(new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content"))))));

		StepVerifier.create(mcpAsyncServer.putPrompt(specificationV1)).verifyComplete();

		Prompt promptV2 = new Prompt(TEST_PROMPT_NAME, "Prompt with version 2.0.0", List.of());
		McpServerFeatures.AsyncPromptSpecification specificationV2 = new McpServerFeatures.AsyncPromptSpecification(
				promptV2, (exchange, req) -> Mono.just(new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content"))))));

		StepVerifier.create(mcpAsyncServer.putPrompt(specificationV2)).verifyComplete();
	}

	@Test
	void testRemovePromptWithoutCapability() {
		// Create a server without prompt capabilities
		McpAsyncServer serverWithoutPrompts = McpServer.async(createMcpTransportProvider())
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
		McpServerFeatures.AsyncPromptSpecification specification = new McpServerFeatures.AsyncPromptSpecification(
				prompt, (exchange, req) -> Mono.just(new GetPromptResult("Test prompt description", List
					.of(new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content"))))));

		var mcpAsyncServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.prompts(specification)
			.build();

		StepVerifier.create(mcpAsyncServer.removePrompt(TEST_PROMPT_NAME_TO_REMOVE)).verifyComplete();

		assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentPrompt() {
		var mcpAsyncServer2 = McpServer.async(createMcpTransportProvider())
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
	void testRootsChangeHandlers() {
		// Test with single consumer
		var rootsReceived = new McpSchema.Root[1];
		var consumerCalled = new boolean[1];

		var singleConsumerServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
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

		var multipleConsumersServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
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
		var errorHandlingServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.rootsChangeHandlers(List.of((exchange, roots) -> {
				throw new RuntimeException("Test error");
			}))
			.build();

		assertThat(errorHandlingServer).isNotNull();
		assertThatCode(() -> errorHandlingServer.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
		onClose();

		// Test without consumers
		var noConsumersServer = McpServer.async(createMcpTransportProvider())
			.serverInfo("test-server", "1.0.0")
			.build();

		assertThat(noConsumersServer).isNotNull();
		assertThatCode(() -> noConsumersServer.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
	}

}
