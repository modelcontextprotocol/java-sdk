/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class McpAsyncClientTests {

	public static final McpSchema.Implementation MOCK_SERVER_INFO = new McpSchema.Implementation("test-server",
			"1.0.0");

	public static final McpSchema.ServerCapabilities MOCK_SERVER_CAPABILITIES = McpSchema.ServerCapabilities.builder()
		.tools(true)
		.build();

	public static final McpSchema.InitializeResult MOCK_INIT_RESULT = new McpSchema.InitializeResult(
			ProtocolVersions.MCP_2024_11_05, MOCK_SERVER_CAPABILITIES, MOCK_SERVER_INFO, "Test instructions");

	private static final String CONTEXT_KEY = "context.key";

	private McpClientTransport createMockTransportForToolValidation(boolean hasOutputSchema, boolean invalidOutput) {

		// Create tool with or without output schema
		Map<String, Object> inputSchemaMap = Map.of("type", "object", "properties",
				Map.of("expression", Map.of("type", "string")), "required", List.of("expression"));

		McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema("object", inputSchemaMap, null, null, null, null);
		McpSchema.Tool.Builder toolBuilder = McpSchema.Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.inputSchema(inputSchema);

		if (hasOutputSchema) {
			Map<String, Object> outputSchema = Map.of("type", "object", "properties",
					Map.of("result", Map.of("type", "number"), "operation", Map.of("type", "string")), "required",
					List.of("result", "operation"));
			toolBuilder.outputSchema(outputSchema);
		}

		McpSchema.Tool calculatorTool = toolBuilder.build();
		McpSchema.ListToolsResult mockToolsResult = new McpSchema.ListToolsResult(List.of(calculatorTool), null);

		// Create call tool result - valid or invalid based on parameter
		Map<String, Object> structuredContent = invalidOutput ? Map.of("result", "5", "operation", "add")
				: Map.of("result", 5, "operation", "add");

		McpSchema.CallToolResult mockCallToolResult = McpSchema.CallToolResult.builder()
			.addTextContent("Calculation result")
			.structuredContent(structuredContent)
			.build();

		return new McpClientTransport() {
			Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

			@Override
			public Mono<Void> connect(
					Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
				this.handler = handler;
				return Mono.empty();
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}

			@Override
			public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
				if (!(message instanceof McpSchema.JSONRPCRequest request)) {
					return Mono.empty();
				}

				McpSchema.JSONRPCResponse response;
				if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
					response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), MOCK_INIT_RESULT,
							null);
				}
				else if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
					response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), mockToolsResult,
							null);
				}
				else if (McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
					response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
							mockCallToolResult, null);
				}
				else {
					return Mono.empty();
				}

				return handler.apply(Mono.just(response)).then();
			}

			@Override
			public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
				return JSON_MAPPER.convertValue(data, new TypeRef<>() {
					@Override
					public java.lang.reflect.Type getType() {
						return typeRef.getType();
					}
				});
			}
		};
	}

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
			public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
				return JSON_MAPPER.convertValue(data, new TypeRef<>() {
					@Override
					public java.lang.reflect.Type getType() {
						return typeRef.getType();
					}
				});
			}
		};

		assertThatCode(() -> {
			McpAsyncClient client = McpClient.async(transport).build();
			client.initialize().contextWrite(ctx -> ctx.put(CONTEXT_KEY, "hello")).block();
		}).doesNotThrowAnyException();
	}

	@Test
	void testCallToolWithOutputSchemaValidationSuccess() {
		McpClientTransport transport = createMockTransportForToolValidation(true, false);

		McpAsyncClient client = McpClient.async(transport).enableCallToolSchemaCaching(true).build();

		StepVerifier.create(client.initialize()).expectNextMatches(Objects::nonNull).verifyComplete();

		StepVerifier.create(client.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3"))))
			.expectNextMatches(response -> {
				assertThat(response).isNotNull();
				assertThat(response.isError()).isFalse();
				assertThat(response.structuredContent()).isInstanceOf(Map.class);
				assertThat((Map<?, ?>) response.structuredContent()).hasSize(2);
				assertThat(response.content()).hasSize(1);
				return true;
			})
			.verifyComplete();

		StepVerifier.create(client.closeGracefully()).verifyComplete();
	}

	@Test
	void testCallToolWithNoOutputSchemaSuccess() {
		McpClientTransport transport = createMockTransportForToolValidation(false, false);

		McpAsyncClient client = McpClient.async(transport).enableCallToolSchemaCaching(true).build();

		StepVerifier.create(client.initialize()).expectNextMatches(Objects::nonNull).verifyComplete();

		StepVerifier.create(client.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3"))))
			.expectNextMatches(response -> {
				assertThat(response).isNotNull();
				assertThat(response.isError()).isFalse();
				assertThat(response.structuredContent()).isInstanceOf(Map.class);
				assertThat((Map<?, ?>) response.structuredContent()).hasSize(2);
				assertThat(response.content()).hasSize(1);
				return true;
			})
			.verifyComplete();

		StepVerifier.create(client.closeGracefully()).verifyComplete();
	}

	@Test
	void testCallToolWithOutputSchemaValidationFailure() {
		McpClientTransport transport = createMockTransportForToolValidation(true, true);

		McpAsyncClient client = McpClient.async(transport).enableCallToolSchemaCaching(true).build();

		StepVerifier.create(client.initialize()).expectNextMatches(Objects::nonNull).verifyComplete();

		StepVerifier.create(client.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3"))))
			.expectErrorMatches(ex -> ex instanceof IllegalArgumentException
					&& ex.getMessage().contains("Tool call result validation failed"))
			.verify();

		StepVerifier.create(client.closeGracefully()).verifyComplete();
	}

	@Test
	void testListToolsWithEmptyCursor() {
		McpSchema.Tool addTool = McpSchema.Tool.builder().name("add").description("calculate add").build();
		McpSchema.Tool subtractTool = McpSchema.Tool.builder()
			.name("subtract")
			.description("calculate subtract")
			.build();
		McpSchema.ListToolsResult mockToolsResult = new McpSchema.ListToolsResult(List.of(addTool, subtractTool), "");

		McpClientTransport transport = new McpClientTransport() {
			Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

			@Override
			public Mono<Void> connect(
					Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
				return Mono.deferContextual(ctx -> {
					this.handler = handler;
					return Mono.empty();
				});
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}

			@Override
			public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
				if (!(message instanceof McpSchema.JSONRPCRequest request)) {
					return Mono.empty();
				}

				McpSchema.JSONRPCResponse response;
				if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
					response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), MOCK_INIT_RESULT,
							null);
				}
				else if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
					response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), mockToolsResult,
							null);
				}
				else {
					return Mono.empty();
				}

				return handler.apply(Mono.just(response)).then();
			}

			@Override
			public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
				return JSON_MAPPER.convertValue(data, new TypeRef<>() {
					@Override
					public java.lang.reflect.Type getType() {
						return typeRef.getType();
					}
				});
			}
		};

		McpAsyncClient client = McpClient.async(transport).enableCallToolSchemaCaching(true).build();

		Mono<McpSchema.ListToolsResult> mono = client.listTools();
		McpSchema.ListToolsResult toolsResult = mono.block();
		assertThat(toolsResult).isNotNull();

		Set<String> names = toolsResult.tools().stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
		assertThat(names).containsExactlyInAnyOrder("subtract", "add");
	}

}
