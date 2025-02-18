/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.modelcontextprotocol.spec.ClientMcpTransport;
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

	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private static final String ECHO_TEST_MESSAGE = "Hello MCP Spring AI!";

	abstract protected ClientMcpTransport createMcpTransport();

	protected void onStart() {
	}

	protected void onClose() {
	}

	@BeforeEach
	void setUp() {
		onStart();
		this.mcpTransport = createMcpTransport();

		assertThatCode(() -> {
			mcpAsyncClient = McpClient.async(mcpTransport)
				.requestTimeout(TIMEOUT)
				.capabilities(ClientCapabilities.builder().roots(true).build())
				.build();
			mcpAsyncClient.initialize().block(Duration.ofSeconds(10));
		}).doesNotThrowAnyException();
	}

	@AfterEach
	void tearDown() {
		if (mcpAsyncClient != null) {
			assertThatCode(() -> mcpAsyncClient.closeGracefully().block(Duration.ofSeconds(10)))
				.doesNotThrowAnyException();
		}
		onClose();
	}

	@Test
	void testConstructorWithInvalidArguments() {
		assertThatThrownBy(() -> McpClient.sync(null).build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport must not be null");

		assertThatThrownBy(() -> McpClient.sync(mcpTransport).requestTimeout(null).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Request timeout must not be null");
	}

	@Test
	void testListTools() {
		StepVerifier.create(mcpAsyncClient.listTools(null)).consumeNextWith(result -> {
			assertThat(result.tools()).isNotNull().isNotEmpty();

			Tool firstTool = result.tools().get(0);
			assertThat(firstTool.name()).isNotNull();
			assertThat(firstTool.description()).isNotNull();
		}).verifyComplete();
	}

	@Test
	void testPing() {
		assertThatCode(() -> mcpAsyncClient.ping().block()).doesNotThrowAnyException();
	}

	@Test
	void testCallTool() {
		CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", ECHO_TEST_MESSAGE));

		StepVerifier.create(mcpAsyncClient.callTool(callToolRequest)).consumeNextWith(callToolResult -> {
			assertThat(callToolResult).isNotNull().satisfies(result -> {
				assertThat(result.content()).isNotNull();
				assertThat(result.isError()).isNull();
			});
		}).verifyComplete();
	}

	@Test
	void testCallToolWithInvalidTool() {
		CallToolRequest invalidRequest = new CallToolRequest("nonexistent_tool", Map.of("message", ECHO_TEST_MESSAGE));

		assertThatThrownBy(() -> mcpAsyncClient.callTool(invalidRequest).block()).isInstanceOf(Exception.class);
	}

	@Test
	void testListResources() {
		StepVerifier.create(mcpAsyncClient.listResources(null)).consumeNextWith(resources -> {
			assertThat(resources).isNotNull().satisfies(result -> {
				assertThat(result.resources()).isNotNull();

				if (!result.resources().isEmpty()) {
					Resource firstResource = result.resources().get(0);
					assertThat(firstResource.uri()).isNotNull();
					assertThat(firstResource.name()).isNotNull();
				}
			});
		}).verifyComplete();
	}

	@Test
	void testMcpAsyncClientState() {
		assertThat(mcpAsyncClient).isNotNull();
	}

	@Test
	void testListPrompts() {
		StepVerifier.create(mcpAsyncClient.listPrompts(null)).consumeNextWith(prompts -> {
			assertThat(prompts).isNotNull().satisfies(result -> {
				assertThat(result.prompts()).isNotNull();

				if (!result.prompts().isEmpty()) {
					Prompt firstPrompt = result.prompts().get(0);
					assertThat(firstPrompt.name()).isNotNull();
					assertThat(firstPrompt.description()).isNotNull();
				}
			});
		}).verifyComplete();
	}

	@Test
	void testGetPrompt() {
		StepVerifier.create(mcpAsyncClient.getPrompt(new GetPromptRequest("simple_prompt", Map.of())))
			.consumeNextWith(prompt -> {
				assertThat(prompt).isNotNull().satisfies(result -> {
					assertThat(result.messages()).isNotEmpty();
					assertThat(result.messages()).hasSize(1);
				});
			})
			.verifyComplete();
	}

	@Test
	void testRootsListChanged() {
		assertThatCode(() -> mcpAsyncClient.rootsListChangedNotification().block()).doesNotThrowAnyException();
	}

	@Test
	void testInitializeWithRootsListProviders() {
		var transport = createMcpTransport();

		var client = McpClient.async(transport)
			.requestTimeout(TIMEOUT)
			.roots(new Root("file:///test/path", "test-root"))
			.build();

		assertThatCode(() -> client.initialize().block(Duration.ofSeconds(10))).doesNotThrowAnyException();

		assertThatCode(() -> client.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void testAddRoot() {
		Root newRoot = new Root("file:///new/test/path", "new-test-root");
		assertThatCode(() -> mcpAsyncClient.addRoot(newRoot).block()).doesNotThrowAnyException();
	}

	@Test
	void testAddRootWithNullValue() {
		assertThatThrownBy(() -> mcpAsyncClient.addRoot(null).block()).hasMessageContaining("Root must not be null");
	}

	@Test
	void testRemoveRoot() {
		Root root = new Root("file:///test/path/to/remove", "root-to-remove");
		assertThatCode(() -> {
			mcpAsyncClient.addRoot(root).block();
			mcpAsyncClient.removeRoot(root.uri()).block();
		}).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonExistentRoot() {
		assertThatThrownBy(() -> mcpAsyncClient.removeRoot("nonexistent-uri").block())
			.hasMessageContaining("Root with uri 'nonexistent-uri' not found");
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
	void testListResourceTemplates() {
		StepVerifier.create(mcpAsyncClient.listResourceTemplates()).consumeNextWith(result -> {
			assertThat(result).isNotNull();
			assertThat(result.resourceTemplates()).isNotNull();
		}).verifyComplete();
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
			.requestTimeout(TIMEOUT)
			.toolsChangeConsumer(tools -> Mono.fromRunnable(() -> toolsNotificationReceived.set(true)))
			.resourcesChangeConsumer(resources -> Mono.fromRunnable(() -> resourcesNotificationReceived.set(true)))
			.promptsChangeConsumer(prompts -> Mono.fromRunnable(() -> promptsNotificationReceived.set(true)))
			.build();

		assertThatCode(() -> {
			client.initialize().block();
			client.closeGracefully().block();
		}).doesNotThrowAnyException();
	}

	@Test
	void testInitializeWithSamplingCapability() {
		var transport = createMcpTransport();

		var capabilities = ClientCapabilities.builder().sampling().build();

		var client = McpClient.async(transport)
			.requestTimeout(TIMEOUT)
			.capabilities(capabilities)
			.sampling(request -> Mono.just(CreateMessageResult.builder().message("test").model("test-model").build()))
			.build();

		assertThatCode(() -> {
			client.initialize().block(Duration.ofSeconds(10));
			client.closeGracefully().block(Duration.ofSeconds(10));
		}).doesNotThrowAnyException();
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
			.requestTimeout(TIMEOUT)
			.capabilities(capabilities)
			.sampling(samplingHandler)
			.build();

		assertThatCode(() -> {
			var result = client.initialize().block(Duration.ofSeconds(10));
			assertThat(result).isNotNull();
			assertThat(result.capabilities()).isNotNull();
			client.closeGracefully().block(Duration.ofSeconds(10));
		}).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@Test
	void testLoggingLevels() {
		// Test all logging levels
		for (McpSchema.LoggingLevel level : McpSchema.LoggingLevel.values()) {
			StepVerifier.create(mcpAsyncClient.setLoggingLevel(level)).verifyComplete();
		}
	}

	@Test
	void testLoggingConsumer() {
		AtomicBoolean logReceived = new AtomicBoolean(false);
		var transport = createMcpTransport();

		var client = McpClient.async(transport)
			.requestTimeout(TIMEOUT)
			.loggingConsumer(notification -> Mono.fromRunnable(() -> logReceived.set(true)))
			.build();

		assertThatCode(() -> {
			client.initialize().block(Duration.ofSeconds(10));
			client.closeGracefully().block(Duration.ofSeconds(10));
		}).doesNotThrowAnyException();
	}

	@Test
	void testLoggingWithNullNotification() {
		assertThatThrownBy(() -> mcpAsyncClient.setLoggingLevel(null).block())
			.hasMessageContaining("Logging level must not be null");
	}

}
