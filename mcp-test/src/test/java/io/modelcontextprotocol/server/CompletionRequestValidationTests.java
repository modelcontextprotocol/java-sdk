/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code completion/complete} request without its spec-required {@code ref} or
 * {@code argument} is answered with Invalid params.
 */
class CompletionRequestValidationTests {

	static Map<String, Object>[] incompleteParams() {
		@SuppressWarnings("unchecked")
		Map<String, Object>[] params = new Map[] { Map.of("argument", Map.of("name", "a", "value", "b")),
				Map.of("ref", Map.of("type", "ref/prompt", "name", "p")) };
		return params;
	}

	@ParameterizedTest
	@MethodSource("incompleteParams")
	void incompleteRequestIsInvalidParams(Map<String, Object> params) {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = McpServer.async(transportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().completions().build())
			.build();
		transportProvider.simulateIncomingMessage(new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "init",
				McpSchema.InitializeRequest
					.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().build(),
							McpSchema.Implementation.builder("test-client", "1.0.0").build())
					.build()));
		transportProvider
			.simulateIncomingMessage(new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED));

		transportProvider.simulateIncomingMessage(
				new McpSchema.JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, "complete", params));

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) transport.getLastSentMessage();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		server.closeGracefully().block(Duration.ofSeconds(10));
	}

	@ParameterizedTest
	@MethodSource("incompleteParams")
	void incompleteStatelessRequestIsInvalidParams(Map<String, Object> params) {
		AtomicReference<McpStatelessServerHandler> handler = new AtomicReference<>();
		McpStatelessServerTransport transport = new McpStatelessServerTransport() {
			@Override
			public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
				handler.set(mcpHandler);
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}
		};
		McpStatelessAsyncServer server = McpServer.async(transport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().completions().build())
			.build();

		McpSchema.JSONRPCResponse response = handler.get()
			.handleRequest(McpTransportContext.EMPTY,
					new McpSchema.JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, "complete", params))
			.block(Duration.ofSeconds(10));

		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		server.closeGracefully().block(Duration.ofSeconds(10));
	}

}
