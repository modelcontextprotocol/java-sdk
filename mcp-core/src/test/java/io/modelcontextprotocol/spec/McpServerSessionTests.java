/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link McpServerSession}.
 */
class McpServerSessionTests {

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

	@Test
	void handleRequestBeforeInitializationReturnsInvalidRequest() {
		var transport = new TestMcpServerTransport();
		var session = newSession(transport);

		StepVerifier
			.create(session.handle(new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, "test-id"))
				.timeout(Duration.ofMillis(200)))
			.verifyComplete();

		assertThat(transport.sentMessages()).hasSize(1);
		assertThat(transport.sentMessages().get(0)).isInstanceOf(McpSchema.JSONRPCResponse.class);
		var response = (McpSchema.JSONRPCResponse) transport.sentMessages().get(0);
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_REQUEST);
		assertThat(response.error().message()).contains("not initialized");
	}

	private static McpServerSession newSession(TestMcpServerTransport transport) {
		return new McpServerSession("test-session", REQUEST_TIMEOUT, transport,
				initializeRequest -> Mono.just(
						McpSchema.InitializeResult
							.builder("2025-11-25", McpSchema.ServerCapabilities.builder().build(),
									McpSchema.Implementation.builder("test-server", "1.0.0").build())
							.build()),
				Map.of(McpSchema.METHOD_TOOLS_LIST,
						(exchange, params) -> Mono.just(McpSchema.ListToolsResult.builder(List.of()).build())),
				Map.of());
	}

	private static final class TestMcpServerTransport implements McpServerTransport {

		private final List<McpSchema.JSONRPCMessage> sentMessages = new ArrayList<>();

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			this.sentMessages.add(message);
			return Mono.empty();
		}

		List<McpSchema.JSONRPCMessage> sentMessages() {
			return this.sentMessages;
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return McpJsonDefaults.getMapper().convertValue(data, typeRef);
		}

	}

}
