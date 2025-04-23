/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.schema.McpSchema;
import io.modelcontextprotocol.schema.McpSchema.CallToolRequest;
import io.modelcontextprotocol.schema.McpSchema.CallToolResult;
import io.modelcontextprotocol.schema.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.schema.McpSchema.ListResourceTemplatesResult;
import io.modelcontextprotocol.schema.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.schema.McpSchema.ListToolsResult;
import io.modelcontextprotocol.schema.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.schema.McpSchema.Resource;
import io.modelcontextprotocol.schema.McpSchema.Root;
import io.modelcontextprotocol.schema.McpSchema.SubscribeRequest;
import io.modelcontextprotocol.schema.McpSchema.TextContent;
import io.modelcontextprotocol.schema.McpSchema.Tool;
import io.modelcontextprotocol.schema.McpSchema.UnsubscribeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for MCP Client Session functionality.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
// KEEP IN SYNC with the class in mcp-test module
public abstract class AbstractMcpSyncClientTests {

	private static final String TEST_MESSAGE = "Hello MCP Reactor AI!";

	abstract protected McpClientTransport createMcpTransport();

	protected void onStart() {
	}

	protected void onClose() {
	}

	protected Duration getRequestTimeout() {
		return Duration.ofSeconds(14);
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(2);
	}

	McpSyncClient client(McpClientTransport transport) {
		return client(transport, Function.identity());
	}

	McpSyncClient client(McpClientTransport transport, Function<McpClient.SyncSpec, McpClient.SyncSpec> customizer) {
		AtomicReference<McpSyncClient> client = new AtomicReference<>();

		assertThatCode(() -> {
			McpClient.SyncSpec builder = McpClient.sync(transport)
				.requestTimeout(getRequestTimeout())
				.initializationTimeout(getInitializationTimeout())
				.capabilities(ClientCapabilities.builder().roots(true).build());
			builder = customizer.apply(builder);
			client.set(builder.build());
		}).doesNotThrowAnyException();

		return client.get();
	}

	void withClient(McpClientTransport transport, Consumer<McpSyncClient> c) {
		withClient(transport, Function.identity(), c);
	}

	void withClient(McpClientTransport transport, Function<McpClient.SyncSpec, McpClient.SyncSpec> customizer,
			Consumer<McpSyncClient> c) {
		var client = client(transport, customizer);
		try {
			c.accept(client);
		}
		finally {
			assertThat(client.closeGracefully()).isTrue();
		}
	}

	@BeforeEach
	void setUp() {
		onStart();

	}

	@AfterEach
	void tearDown() {
		onClose();
	}

	static final Object DUMMY_RETURN_VALUE = new Object();

	<T> void verifyNotificationTimesOut(Consumer<McpSyncClient> operation, String action) {
		verifyCallTimesOut(client -> {
			operation.accept(client);
			return DUMMY_RETURN_VALUE;
		}, action);
	}

	<T> void verifyCallTimesOut(Function<McpSyncClient, T> blockingOperation, String action) {
		withClient(createMcpTransport(), mcpSyncClient -> {
			// This scheduler is not replaced by virtual time scheduler
			Scheduler customScheduler = Schedulers.newBoundedElastic(1, 1, "actualBoundedElastic");

			StepVerifier.withVirtualTime(() -> Mono.fromSupplier(() -> blockingOperation.apply(mcpSyncClient))
				// Offload the blocking call to the real scheduler
				.subscribeOn(customScheduler))
				.expectSubscription()
				// This works without actually waiting but executes all the
				// tasks pending execution on the VirtualTimeScheduler.
				// It is possible to execute the blocking code from the operation
				// because it is blocked on a dedicated Scheduler and the main
				// flow is not blocked and uses the VirtualTimeScheduler.
				.thenAwait(getInitializationTimeout())
				.consumeErrorWith(e -> assertThat(e).isInstanceOf(McpError.class)
					.hasMessage("Client must be initialized before " + action))
				.verify();

			customScheduler.dispose();
		});
	}

	@Test
	void testConstructorWithInvalidArguments() {
		assertThatThrownBy(() -> McpClient.sync(null).build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport must not be null");

		assertThatThrownBy(() -> McpClient.sync(createMcpTransport()).requestTimeout(null).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Request timeout must not be null");
	}

	@Test
	void testListToolsWithoutInitialization() {
		verifyCallTimesOut(client -> client.listTools(null), "listing tools");
	}

	@Test
	void testListTools() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListToolsResult tools = mcpSyncClient.listTools(null);

			assertThat(tools).isNotNull().satisfies(result -> {
				assertThat(result.tools()).isNotNull().isNotEmpty();

				Tool firstTool = result.tools().get(0);
				assertThat(firstTool.name()).isNotNull();
				assertThat(firstTool.description()).isNotNull();
			});
		});
	}

	@Test
	void testCallToolsWithoutInitialization() {
		verifyCallTimesOut(client -> client.callTool(new CallToolRequest("add", Map.of("a", 3, "b", 4))),
				"calling tools");
	}

	@Test
	void testCallTools() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			CallToolResult toolResult = mcpSyncClient.callTool(new CallToolRequest("add", Map.of("a", 3, "b", 4)));

			assertThat(toolResult).isNotNull().satisfies(result -> {

				assertThat(result.content()).hasSize(1);

				TextContent content = (TextContent) result.content().get(0);

				assertThat(content).isNotNull();
				assertThat(content.text()).isNotNull();
				assertThat(content.text()).contains("7");
			});
		});
	}

	@Test
	void testPingWithoutInitialization() {
		verifyCallTimesOut(client -> client.ping(), "pinging the server");
	}

	@Test
	void testPing() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			assertThatCode(() -> mcpSyncClient.ping()).doesNotThrowAnyException();
		});
	}

	@Test
	void testCallToolWithoutInitialization() {
		CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", TEST_MESSAGE));
		verifyCallTimesOut(client -> client.callTool(callToolRequest), "calling tools");
	}

	@Test
	void testCallTool() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", TEST_MESSAGE));

			CallToolResult callToolResult = mcpSyncClient.callTool(callToolRequest);

			assertThat(callToolResult).isNotNull().satisfies(result -> {
				assertThat(result.content()).isNotNull();
				assertThat(result.isError()).isNull();
			});
		});
	}

	@Test
	void testCallToolWithInvalidTool() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			CallToolRequest invalidRequest = new CallToolRequest("nonexistent_tool", Map.of("message", TEST_MESSAGE));

			assertThatThrownBy(() -> mcpSyncClient.callTool(invalidRequest)).isInstanceOf(Exception.class);
		});
	}

	@Test
	void testRootsListChangedWithoutInitialization() {
		verifyNotificationTimesOut(client -> client.rootsListChangedNotification(),
				"sending roots list changed notification");
	}

	@Test
	void testRootsListChanged() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			assertThatCode(() -> mcpSyncClient.rootsListChangedNotification()).doesNotThrowAnyException();
		});
	}

	@Test
	void testListResourcesWithoutInitialization() {
		verifyCallTimesOut(client -> client.listResources(null), "listing resources");
	}

	@Test
	void testListResources() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListResourcesResult resources = mcpSyncClient.listResources(null);

			assertThat(resources).isNotNull().satisfies(result -> {
				assertThat(result.resources()).isNotNull();

				if (!result.resources().isEmpty()) {
					Resource firstResource = result.resources().get(0);
					assertThat(firstResource.uri()).isNotNull();
					assertThat(firstResource.name()).isNotNull();
				}
			});
		});
	}

	@Test
	void testClientSessionState() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			assertThat(mcpSyncClient).isNotNull();
		});
	}

	@Test
	void testInitializeWithRootsListProviders() {
		withClient(createMcpTransport(), builder -> builder.roots(new Root("file:///test/path", "test-root")),
				mcpSyncClient -> {

					assertThatCode(() -> {
						mcpSyncClient.initialize();
						mcpSyncClient.close();
					}).doesNotThrowAnyException();
				});
	}

	@Test
	void testAddRoot() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			Root newRoot = new Root("file:///new/test/path", "new-test-root");
			assertThatCode(() -> mcpSyncClient.addRoot(newRoot)).doesNotThrowAnyException();
		});
	}

	@Test
	void testAddRootWithNullValue() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			assertThatThrownBy(() -> mcpSyncClient.addRoot(null)).hasMessageContaining("Root must not be null");
		});
	}

	@Test
	void testRemoveRoot() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			Root root = new Root("file:///test/path/to/remove", "root-to-remove");
			assertThatCode(() -> {
				mcpSyncClient.addRoot(root);
				mcpSyncClient.removeRoot(root.uri());
			}).doesNotThrowAnyException();
		});
	}

	@Test
	void testRemoveNonExistentRoot() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			assertThatThrownBy(() -> mcpSyncClient.removeRoot("nonexistent-uri"))
				.hasMessageContaining("Root with uri 'nonexistent-uri' not found");
		});
	}

	@Test
	void testReadResourceWithoutInitialization() {
		Resource resource = new Resource("test://uri", "Test Resource", null, null, null);
		verifyCallTimesOut(client -> client.readResource(resource), "reading resources");
	}

	@Test
	void testReadResource() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListResourcesResult resources = mcpSyncClient.listResources(null);

			if (!resources.resources().isEmpty()) {
				Resource firstResource = resources.resources().get(0);
				ReadResourceResult result = mcpSyncClient.readResource(firstResource);

				assertThat(result).isNotNull();
				assertThat(result.contents()).isNotNull();
			}
		});
	}

	@Test
	void testListResourceTemplatesWithoutInitialization() {
		verifyCallTimesOut(client -> client.listResourceTemplates(null), "listing resource templates");
	}

	@Test
	void testListResourceTemplates() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListResourceTemplatesResult result = mcpSyncClient.listResourceTemplates(null);

			assertThat(result).isNotNull();
			assertThat(result.resourceTemplates()).isNotNull();
		});
	}

	// @Test
	void testResourceSubscription() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			ListResourcesResult resources = mcpSyncClient.listResources(null);

			if (!resources.resources().isEmpty()) {
				Resource firstResource = resources.resources().get(0);

				// Test subscribe
				assertThatCode(() -> mcpSyncClient.subscribeResource(new SubscribeRequest(firstResource.uri())))
					.doesNotThrowAnyException();

				// Test unsubscribe
				assertThatCode(() -> mcpSyncClient.unsubscribeResource(new UnsubscribeRequest(firstResource.uri())))
					.doesNotThrowAnyException();
			}
		});
	}

	@Test
	void testNotificationHandlers() {
		AtomicBoolean toolsNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean resourcesNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean promptsNotificationReceived = new AtomicBoolean(false);

		withClient(createMcpTransport(),
				builder -> builder.toolsChangeConsumer(tools -> toolsNotificationReceived.set(true))
					.resourcesChangeConsumer(resources -> resourcesNotificationReceived.set(true))
					.promptsChangeConsumer(prompts -> promptsNotificationReceived.set(true)),
				client -> {

					assertThatCode(() -> {
						client.initialize();
						client.close();
					}).doesNotThrowAnyException();
				});
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@Test
	void testLoggingLevelsWithoutInitialization() {
		verifyNotificationTimesOut(client -> client.setLoggingLevel(McpSchema.LoggingLevel.DEBUG),
				"setting logging level");
	}

	@Test
	void testLoggingLevels() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			// Test all logging levels
			for (McpSchema.LoggingLevel level : McpSchema.LoggingLevel.values()) {
				assertThatCode(() -> mcpSyncClient.setLoggingLevel(level)).doesNotThrowAnyException();
			}
		});
	}

	@Test
	void testLoggingConsumer() {
		AtomicBoolean logReceived = new AtomicBoolean(false);
		withClient(createMcpTransport(), builder -> builder.requestTimeout(getRequestTimeout())
			.loggingConsumer(notification -> logReceived.set(true)), client -> {
				assertThatCode(() -> {
					client.initialize();
					client.close();
				}).doesNotThrowAnyException();
			});
	}

	@Test
	void testLoggingWithNullNotification() {
		withClient(createMcpTransport(), mcpSyncClient -> assertThatThrownBy(() -> mcpSyncClient.setLoggingLevel(null))
			.hasMessageContaining("Logging level must not be null"));
	}

}
