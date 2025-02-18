/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ListResourceTemplatesResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.SubscribeRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.UnsubscribeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

	private McpSyncClient mcpSyncClient;

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String TEST_MESSAGE = "Hello MCP Spring AI!";

	protected ClientMcpTransport mcpTransport;

	abstract protected ClientMcpTransport createMcpTransport();

	abstract protected void onStart();

	abstract protected void onClose();

	@BeforeEach
	void setUp() {
		onStart();
		this.mcpTransport = createMcpTransport();

		assertThatCode(() -> {
			mcpSyncClient = McpClient.sync(mcpTransport)
				.requestTimeout(TIMEOUT)
				.capabilities(ClientCapabilities.builder().roots(true).build())
				.build();
			mcpSyncClient.initialize();
		}).doesNotThrowAnyException();
	}

	@AfterEach
	void tearDown() {
		if (mcpSyncClient != null) {
			assertThatCode(() -> mcpSyncClient.close()).doesNotThrowAnyException();
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
		ListToolsResult tools = mcpSyncClient.listTools(null);

		assertThat(tools).isNotNull().satisfies(result -> {
			assertThat(result.tools()).isNotNull().isNotEmpty();

			Tool firstTool = result.tools().get(0);
			assertThat(firstTool.name()).isNotNull();
			assertThat(firstTool.description()).isNotNull();
		});
	}

	@Test
	void testCallTools() {
		CallToolResult toolResult = mcpSyncClient.callTool(new CallToolRequest("add", Map.of("a", 3, "b", 4)));

		assertThat(toolResult).isNotNull().satisfies(result -> {

			assertThat(result.content()).hasSize(1);

			TextContent content = (TextContent) result.content().get(0);

			assertThat(content).isNotNull();
			assertThat(content.text()).isNotNull();
			assertThat(content.text()).contains("7");
		});
	}

	@Test
	void testPing() {
		assertThatCode(() -> mcpSyncClient.ping()).doesNotThrowAnyException();
	}

	@Test
	void testCallTool() {
		CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", TEST_MESSAGE));

		CallToolResult callToolResult = mcpSyncClient.callTool(callToolRequest);

		assertThat(callToolResult).isNotNull().satisfies(result -> {
			assertThat(result.content()).isNotNull();
			assertThat(result.isError()).isNull();
		});
	}

	@Test
	void testCallToolWithInvalidTool() {
		CallToolRequest invalidRequest = new CallToolRequest("nonexistent_tool", Map.of("message", TEST_MESSAGE));

		assertThatThrownBy(() -> mcpSyncClient.callTool(invalidRequest)).isInstanceOf(Exception.class);
	}

	@Test
	void testRootsListChanged() {
		assertThatCode(() -> mcpSyncClient.rootsListChangedNotification()).doesNotThrowAnyException();
	}

	@Test
	void testListResources() {
		ListResourcesResult resources = mcpSyncClient.listResources(null);

		assertThat(resources).isNotNull().satisfies(result -> {
			assertThat(result.resources()).isNotNull();

			if (!result.resources().isEmpty()) {
				Resource firstResource = result.resources().get(0);
				assertThat(firstResource.uri()).isNotNull();
				assertThat(firstResource.name()).isNotNull();
			}
		});
	}

	@Test
	void testClientSessionState() {
		assertThat(mcpSyncClient).isNotNull();
	}

	@Test
	void testInitializeWithRootsListProviders() {
		var transport = createMcpTransport();

		var client = McpClient.sync(transport)
			.requestTimeout(TIMEOUT)
			.roots(new Root("file:///test/path", "test-root"))
			.build();

		assertThatCode(() -> {
			client.initialize();
			client.close();
		}).doesNotThrowAnyException();
	}

	@Test
	void testAddRoot() {
		Root newRoot = new Root("file:///new/test/path", "new-test-root");
		assertThatCode(() -> mcpSyncClient.addRoot(newRoot)).doesNotThrowAnyException();
	}

	@Test
	void testAddRootWithNullValue() {
		assertThatThrownBy(() -> mcpSyncClient.addRoot(null)).hasMessageContaining("Root must not be null");
	}

	@Test
	void testRemoveRoot() {
		Root root = new Root("file:///test/path/to/remove", "root-to-remove");
		assertThatCode(() -> {
			mcpSyncClient.addRoot(root);
			mcpSyncClient.removeRoot(root.uri());
		}).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonExistentRoot() {
		assertThatThrownBy(() -> mcpSyncClient.removeRoot("nonexistent-uri"))
			.hasMessageContaining("Root with uri 'nonexistent-uri' not found");
	}

	@Test
	void testReadResource() {
		ListResourcesResult resources = mcpSyncClient.listResources(null);

		if (!resources.resources().isEmpty()) {
			Resource firstResource = resources.resources().get(0);
			ReadResourceResult result = mcpSyncClient.readResource(firstResource);

			assertThat(result).isNotNull();
			assertThat(result.contents()).isNotNull();
		}
	}

	@Test
	void testListResourceTemplates() {
		ListResourceTemplatesResult result = mcpSyncClient.listResourceTemplates(null);

		assertThat(result).isNotNull();
		assertThat(result.resourceTemplates()).isNotNull();
	}

	// @Test
	void testResourceSubscription() {
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
	}

	@Test
	void testNotificationHandlers() {
		AtomicBoolean toolsNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean resourcesNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean promptsNotificationReceived = new AtomicBoolean(false);

		var transport = createMcpTransport();
		var client = McpClient.sync(transport)
			.requestTimeout(TIMEOUT)
			.toolsChangeConsumer(tools -> toolsNotificationReceived.set(true))
			.resourcesChangeConsumer(resources -> resourcesNotificationReceived.set(true))
			.promptsChangeConsumer(prompts -> promptsNotificationReceived.set(true))
			.build();

		assertThatCode(() -> {
			client.initialize();
			client.close();
		}).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@Test
	void testLoggingLevels() {
		// Test all logging levels
		for (McpSchema.LoggingLevel level : McpSchema.LoggingLevel.values()) {
			assertThatCode(() -> mcpSyncClient.setLoggingLevel(level)).doesNotThrowAnyException();
		}
	}

	@Test
	void testLoggingConsumer() {
		AtomicBoolean logReceived = new AtomicBoolean(false);
		var transport = createMcpTransport();

		var client = McpClient.sync(transport)
			.requestTimeout(TIMEOUT)
			.loggingConsumer(notification -> logReceived.set(true))
			.build();

		assertThatCode(() -> {
			client.initialize();
			client.close();
		}).doesNotThrowAnyException();
	}

	@Test
	void testLoggingWithNullNotification() {
		assertThatThrownBy(() -> mcpSyncClient.setLoggingLevel(null))
			.hasMessageContaining("Logging level must not be null");
	}

}
