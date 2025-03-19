/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.SubscribeRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.UnsubscribeRequest;
import io.modelcontextprotocol.spec.McpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for the {@link McpAsyncClient} that can be used with different
 * {@link McpTransport} implementations.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public abstract class AbstractMcpAsyncClientTests {

	private McpAsyncClient mcpAsyncClient;

	protected ClientMcpTransport mcpTransport;

	private static final String ECHO_TEST_MESSAGE = "Hello MCP Spring AI!";

	abstract protected ClientMcpTransport createMcpTransport();

	protected void onStart() {
	}

	protected void onClose() {
	}

	protected Duration getRequestTimeout() {
		return Duration.ofSeconds(10);
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(2);
	}

	@BeforeEach
	void setUp() {
		onStart();
		this.mcpTransport = createMcpTransport();

		assertThatCode(() -> {
			mcpAsyncClient = McpClient.async(mcpTransport)
				.requestTimeout(getRequestTimeout())
				.initializationTimeout(getInitializationTimeout())
				.capabilities(ClientCapabilities.builder().roots(true).build())
				.build();
		}).doesNotThrowAnyException();
	}

	@AfterEach
	void tearDown() {
		if (mcpAsyncClient != null) {
			StepVerifier.create(mcpAsyncClient.closeGracefully()).verifyComplete();
		}
		onClose();
	}

	@Test
	void testConstructorWithInvalidArguments() {
		assertThatThrownBy(() -> McpClient.async(null).build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport must not be null");

		assertThatThrownBy(() -> McpClient.async(mcpTransport).requestTimeout(null).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Request timeout must not be null");
	}

	@Test
	void testListToolsWithoutInitialization() {
		StepVerifier.create(mcpAsyncClient.listTools(null)).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Client must be initialized before listing tools");
		}).verify();
	}

	@Test
	void testListTools() {
		StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listTools(null)))
			.consumeNextWith(result -> {
				assertThat(result.tools()).isNotNull().isNotEmpty();

				Tool firstTool = result.tools().get(0);
				assertThat(firstTool.name()).isNotNull();
				assertThat(firstTool.description()).isNotNull();
			})
			.verifyComplete();
	}

	@Test
	void testPingWithoutInitialization() {
		StepVerifier.create(mcpAsyncClient.ping())
			.expectErrorMatches(error -> error instanceof McpError
					&& error.getMessage().equals("Client must be initialized before pinging the server"))
			.verify();
	}

	@Test
	void testPing() {
		StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.ping())).verifyComplete();
	}

	@Test
	void testCallToolWithoutInitialization() {
		CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", ECHO_TEST_MESSAGE));

		StepVerifier.create(mcpAsyncClient.callTool(callToolRequest))
			.expectErrorMatches(error -> error instanceof McpError
					&& error.getMessage().equals("Client must be initialized before calling tools"))
			.verify();
	}

	@Test
	void testCallTool() {
		CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", ECHO_TEST_MESSAGE));

		StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.callTool(callToolRequest)))
			.consumeNextWith(callToolResult -> {
				assertThat(callToolResult).isNotNull();
				assertThat(callToolResult.content()).isNotNull();
				assertThat(callToolResult.isError()).isNull();
			})
			.verifyComplete();
	}

	@Test
	void testCallToolWithInvalidTool() {
		CallToolRequest invalidRequest = new CallToolRequest("nonexistent_tool", Map.of("message", ECHO_TEST_MESSAGE));

		StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.callTool(invalidRequest)))
			.expectError(Exception.class)
			.verify();
	}

	@Test
	void testListResourcesWithoutInitialization() {
		StepVerifier.create(mcpAsyncClient.listResources(null))
			.expectErrorMatches(error -> error instanceof McpError
					&& error.getMessage().equals("Client must be initialized before listing resources"))
			.verify();
	}

	@Test
	void testListResources() {
		StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listResources(null)))
			.consumeNextWith(resources -> {
				assertThat(resources).isNotNull().satisfies(result -> {
					assertThat(result.resources()).isNotNull();

					if (!result.resources().isEmpty()) {
						Resource firstResource = result.resources().get(0);
						assertThat(firstResource.uri()).isNotNull();
						assertThat(firstResource.name()).isNotNull();
					}
				});
			})
			.verifyComplete();
	}

	@Test
	void testMcpAsyncClientState() {
		assertThat(mcpAsyncClient).isNotNull();
	}

	@Test
	void testListPromptsWithoutInitialization() {
		StepVerifier.create(mcpAsyncClient.listPrompts(null))
			.expectErrorMatches(error -> error instanceof McpError
					&& error.getMessage().equals("Client must be initialized before listing prompts"))
			.verify();
	}

	@Test
	void testListPrompts() {
		StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listPrompts(null)))
			.consumeNextWith(prompts -> {
				assertThat(prompts).isNotNull().satisfies(result -> {
					assertThat(result.prompts()).isNotNull();

					if (!result.prompts().isEmpty()) {
						Prompt firstPrompt = result.prompts().get(0);
						assertThat(firstPrompt.name()).isNotNull();
						assertThat(firstPrompt.description()).isNotNull();
					}
				});
			})
			.verifyComplete();
	}

	@Test
	void testGetPromptWithoutInitialization() {
		GetPromptRequest request = new GetPromptRequest("simple_prompt", Map.of());

		StepVerifier.create(mcpAsyncClient.getPrompt(request))
			.expectErrorMatches(error -> error instanceof McpError
					&& error.getMessage().equals("Client must be initialized before getting prompts"))
			.verify();
	}

	@Test
	void testGetPrompt() {
		GetPromptRequest request = new GetPromptRequest("simple_prompt", Map.of());

		StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.getPrompt(request)))
			.consumeNextWith(prompt -> {
				assertThat(prompt).isNotNull().satisfies(result -> {
					assertThat(result.messages()).isNotEmpty();
					assertThat(result.messages()).hasSize(1);
				});
			})
			.verifyComplete();
	}

	@Test
	void testRootsListChangedWithoutInitialization() {
		StepVerifier.create(mcpAsyncClient.rootsListChangedNotification())
			.expectErrorMatches(error -> error instanceof McpError && error.getMessage()
				.equals("Client must be initialized before sending roots list changed notification"))
			.verify();
	}

	@Test
	void testRootsListChanged() {
		StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.rootsListChangedNotification()))
			.verifyComplete();
	}

	@Test
	void testInitializeWithRootsListProviders() {
		var transport = createMcpTransport();

		var client = McpClient.async(transport)
			.requestTimeout(getRequestTimeout())
			.roots(new Root("file:///test/path", "test-root"))
			.build();

		StepVerifier.create(client.initialize().then(client.closeGracefully())).verifyComplete();
	}

	@Test
	void testAddRoot() {
		Root newRoot = new Root("file:///new/test/path", "new-test-root");

		StepVerifier.create(mcpAsyncClient.addRoot(newRoot)).verifyComplete();
	}

	@Test
	void testAddRootWithNullValue() {
		StepVerifier.create(mcpAsyncClient.addRoot(null))
			.expectErrorMatches(error -> error.getMessage().contains("Root must not be null"))
			.verify();
	}

	@Test
	void testRemoveRoot() {
		Root root = new Root("file:///test/path/to/remove", "root-to-remove");

		StepVerifier.create(mcpAsyncClient.addRoot(root).then(mcpAsyncClient.removeRoot(root.uri()))).verifyComplete();
	}

	@Test
	void testRemoveNonExistentRoot() {
		StepVerifier.create(mcpAsyncClient.removeRoot("nonexistent-uri"))
			.expectErrorMatches(error -> error.getMessage().contains("Root with uri 'nonexistent-uri' not found"))
			.verify();
	}

	@Test
	@Disabled
	void testReadResource() {
		StepVerifier.create(mcpAsyncClient.listResources()).consumeNextWith(resources -> {
			if (!resources.resources().isEmpty()) {
				Resource firstResource = resources.resources().get(0);
				StepVerifier.create(mcpAsyncClient.readResource(firstResource)).consumeNextWith(result -> {
					assertThat(result).isNotNull();
					assertThat(result.contents()).isNotNull();
				}).verifyComplete();
			}
		}).verifyComplete();
	}

	@Test
	void testListResourceTemplatesWithoutInitialization() {
		StepVerifier.create(mcpAsyncClient.listResourceTemplates())
			.expectErrorMatches(error -> error instanceof McpError
					&& error.getMessage().equals("Client must be initialized before listing resource templates"))
			.verify();
	}

	@Test
	void testListResourceTemplates() {
		StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listResourceTemplates()))
			.consumeNextWith(result -> {
				assertThat(result).isNotNull();
				assertThat(result.resourceTemplates()).isNotNull();
			})
			.verifyComplete();
	}

	// @Test
	void testResourceSubscription() {
		StepVerifier.create(mcpAsyncClient.listResources()).consumeNextWith(resources -> {
			if (!resources.resources().isEmpty()) {
				Resource firstResource = resources.resources().get(0);

				// Test subscribe
				StepVerifier.create(mcpAsyncClient.subscribeResource(new SubscribeRequest(firstResource.uri())))
					.verifyComplete();

				// Test unsubscribe
				StepVerifier.create(mcpAsyncClient.unsubscribeResource(new UnsubscribeRequest(firstResource.uri())))
					.verifyComplete();
			}
		}).verifyComplete();
	}

	@Test
	void testNotificationHandlers() {
		AtomicBoolean toolsNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean resourcesNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean promptsNotificationReceived = new AtomicBoolean(false);

		var transport = createMcpTransport();
		var client = McpClient.async(transport)
			.requestTimeout(getRequestTimeout())
			.toolsChangeConsumer(tools -> Mono.fromRunnable(() -> toolsNotificationReceived.set(true)))
			.resourcesChangeConsumer(resources -> Mono.fromRunnable(() -> resourcesNotificationReceived.set(true)))
			.promptsChangeConsumer(prompts -> Mono.fromRunnable(() -> promptsNotificationReceived.set(true)))
			.build();

		StepVerifier.create(client.initialize().then(client.closeGracefully())).verifyComplete();
	}

	@Test
	void testInitializeWithSamplingCapability() {
		var transport = createMcpTransport();

		var capabilities = ClientCapabilities.builder().sampling().build();

		var client = McpClient.async(transport)
			.requestTimeout(getRequestTimeout())
			.capabilities(capabilities)
			.sampling(request -> Mono.just(CreateMessageResult.builder().message("test").model("test-model").build()))
			.build();

		StepVerifier.create(client.initialize().then(client.closeGracefully())).verifyComplete();
	}

	@Test
	void testInitializeWithAllCapabilities() {
		var transport = createMcpTransport();

		var capabilities = ClientCapabilities.builder()
			.experimental(Map.of("feature", "test"))
			.roots(true)
			.sampling()
			.build();

		Function<CreateMessageRequest, Mono<CreateMessageResult>> samplingHandler = request -> Mono
			.just(CreateMessageResult.builder().message("test").model("test-model").build());
		var client = McpClient.async(transport)
			.requestTimeout(getRequestTimeout())
			.capabilities(capabilities)
			.sampling(samplingHandler)
			.build();

		StepVerifier.create(client.initialize()).consumeNextWith(result -> {
			assertThat(result).isNotNull();
			assertThat(result.capabilities()).isNotNull();
		}).verifyComplete();

		StepVerifier.create(client.closeGracefully()).verifyComplete();
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@Test
	void testLoggingLevelsWithoutInitialization() {
		StepVerifier.create(mcpAsyncClient.setLoggingLevel(McpSchema.LoggingLevel.DEBUG))
			.expectErrorMatches(error -> error instanceof McpError
					&& error.getMessage().equals("Client must be initialized before setting logging level"))
			.verify();
	}

	@Test
	void testLoggingLevels() {
		Mono<Void> testAllLevels = mcpAsyncClient.initialize().then(Mono.defer(() -> {
			Mono<Void> chain = Mono.empty();
			for (McpSchema.LoggingLevel level : McpSchema.LoggingLevel.values()) {
				chain = chain.then(mcpAsyncClient.setLoggingLevel(level));
			}
			return chain;
		}));

		StepVerifier.create(testAllLevels).verifyComplete();
	}

	@Test
	void testLoggingConsumer() {
		AtomicBoolean logReceived = new AtomicBoolean(false);
		var transport = createMcpTransport();

		var client = McpClient.async(transport)
			.requestTimeout(getRequestTimeout())
			.loggingConsumer(notification -> Mono.fromRunnable(() -> logReceived.set(true)))
			.build();

		StepVerifier.create(client.initialize().then(client.closeGracefully())).verifyComplete();
	}

	@Test
	void testLoggingWithNullNotification() {
		StepVerifier.create(mcpAsyncClient.setLoggingLevel(null))
			.expectErrorMatches(error -> error.getMessage().contains("Logging level must not be null"))
			.verify();
	}

}
