/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatCode;

class McpAsyncClientTests {

	public static final McpSchema.Implementation MOCK_SERVER_INFO = new McpSchema.Implementation("test-server",
			"1.0.0");

	public static final McpSchema.ServerCapabilities MOCK_SERVER_CAPABILITIES = McpSchema.ServerCapabilities.builder()
		.build();

	public static final McpSchema.InitializeResult MOCK_INIT_RESULT = new McpSchema.InitializeResult(
			ProtocolVersions.MCP_2024_11_05, MOCK_SERVER_CAPABILITIES, MOCK_SERVER_INFO, "Test instructions");

	private static final String CONTEXT_KEY = "context.key";

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Test
	void validateContextPassedToTransportConnect() {
		McpClientTransport transport = new McpClientTransport() {
			Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

			final AtomicReference<String> contextValue = new AtomicReference<>();

			@Override
			public Mono<Void> connect(
					Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
				return Mono.deferContextual(ctx -> {
					this.handler = handler;
					if (ctx.hasKey(CONTEXT_KEY)) {
						this.contextValue.set(ctx.get(CONTEXT_KEY));
					}
					return Mono.empty();
				});
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}

			@Override
			public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
				if (!"hello".equals(this.contextValue.get())) {
					return Mono.error(new RuntimeException("Context value not propagated via #connect method"));
				}
				// We're only interested in handling the init request to provide an init
				// response
				if (!(message instanceof McpSchema.JSONRPCRequest)) {
					return Mono.empty();
				}
				McpSchema.JSONRPCResponse initResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						((McpSchema.JSONRPCRequest) message).id(), MOCK_INIT_RESULT, null);
				return handler.apply(Mono.just(initResponse)).then();
			}

			@Override
			public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
				return OBJECT_MAPPER.convertValue(data, typeRef);
			}
		};

		assertThatCode(() -> {
			McpAsyncClient client = McpClient.async(transport).build();
			client.initialize().contextWrite(ctx -> ctx.put(CONTEXT_KEY, "hello")).block();
		}).doesNotThrowAnyException();
	}

}
