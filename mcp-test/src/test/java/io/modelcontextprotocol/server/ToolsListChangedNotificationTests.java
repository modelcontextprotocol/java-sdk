/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ToolsListChangedNotificationTests {

	private MockMcpServerTransport transport;

	private MockMcpServerTransportProvider transportProvider;

	private McpAsyncServer mcpAsyncServer;

	@BeforeEach
	void setUp() {
		transport = new MockMcpServerTransport();
		transportProvider = new MockMcpServerTransportProvider(transport);
	}

	@AfterEach
	void tearDown() {
		if (mcpAsyncServer != null) {
			assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10)))
				.doesNotThrowAnyException();
		}
	}

	@Test
	void removeNonexistentToolDoesNotNotifyListChanged() {
		mcpAsyncServer = McpServer.async(transportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		transport.clearSentMessages();

		StepVerifier.create(mcpAsyncServer.removeTool("missing-tool")).verifyComplete();

		assertThat(toolListChangedNotificationCount()).isZero();
	}

	@Test
	void removeExistingToolNotifiesListChanged() {
		Tool tool = Tool.builder()
			.name("tool1")
			.description("tool1 description")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		mcpAsyncServer = McpServer.async(transportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.toolCall(tool,
					(exchange, request) -> Mono
						.just(CallToolResult.builder().content(List.of()).isError(false).build()))
			.build();

		transport.clearSentMessages();

		StepVerifier.create(mcpAsyncServer.removeTool("tool1")).verifyComplete();

		assertThat(toolListChangedNotificationCount()).isEqualTo(1);
	}

	private long toolListChangedNotificationCount() {
		return transport.getAllSentMessages()
			.stream()
			.filter(McpSchema.JSONRPCNotification.class::isInstance)
			.map(McpSchema.JSONRPCNotification.class::cast)
			.filter(notification -> McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED.equals(notification.method()))
			.count();
	}

}
