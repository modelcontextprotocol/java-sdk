/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class McpAsyncClientTests {

	public static final McpSchema.Implementation MOCK_SERVER_INFO = McpSchema.Implementation
		.builder("test-server", "1.0.0")
		.build();

	public static final McpSchema.ServerCapabilities MOCK_SERVER_CAPABILITIES = McpSchema.ServerCapabilities.builder()
		.tools(true)
		.build();

	public static final McpSchema.InitializeResult MOCK_INIT_RESULT = McpSchema.InitializeResult
		.builder(ProtocolVersions.MCP_2024_11_05, MOCK_SERVER_CAPABILITIES, MOCK_SERVER_INFO)
		.instructions("Test instructions")
		.build();

	private static final String CONTEXT_KEY = "context.key";

	private McpClientTransport createMockTransportForToolValidation(boolean hasOutputSchema, boolean invalidOutput) {

		// Create tool with or without output schema
		Map<String, Object> inputSchemaMap = Map.of("type", "object", "properties",
				Map.of("expression", Map.of("type", "string")), "required", List.of("expression"));

		McpSchema.Tool.Builder toolBuilder = McpSchema.Tool.builder("calculator", inputSchemaMap)
			.description("Performs mathematical calculations");

		if (hasOutputSchema) {
			Map<String, Object> outputSchema = Map.of("type", "object", "properties",
					Map.of("result", Map.of("type", "number"), "operation", Map.of("type", "string")), "required",
					List.of("result", "operation"));
			toolBuilder.outputSchema(outputSchema);
		}

		McpSchema.Tool calculatorTool = toolBuilder.build();
		McpSchema.ListToolsResult mockToolsResult = McpSchema.ListToolsResult.builder(List.of(calculatorTool)).build();

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
					response = McpSchema.JSONRPCResponse.result(request.id(), MOCK_INIT_RESULT);
				}
				else if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
					response = McpSchema.JSONRPCResponse.result(request.id(), mockToolsResult);
				}
				else if (McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
					response = McpSchema.JSONRPCResponse.result(request.id(), mockCallToolResult);
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
				McpSchema.JSONRPCResponse initResponse = McpSchema.JSONRPCResponse
					.result(((McpSchema.JSONRPCRequest) message).id(), MOCK_INIT_RESULT);
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

		StepVerifier
			.create(client.callTool(
					McpSchema.CallToolRequest.builder("calculator").arguments(Map.of("expression", "2 + 3")).build()))
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

		StepVerifier
			.create(client.callTool(
					McpSchema.CallToolRequest.builder("calculator").arguments(Map.of("expression", "2 + 3")).build()))
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

		StepVerifier
			.create(client.callTool(
					McpSchema.CallToolRequest.builder("calculator").arguments(Map.of("expression", "2 + 3")).build()))
			.expectErrorMatches(ex -> ex instanceof IllegalArgumentException
					&& ex.getMessage().contains("Tool call result validation failed"))
			.verify();

		StepVerifier.create(client.closeGracefully()).verifyComplete();
	}

	@Test
	void testListToolsWithCursorAndMeta() {
		var transport = new TestMcpClientTransport();
		McpAsyncClient client = McpClient.async(transport).build();

		Map<String, Object> meta = Map.of("customKey", "customValue");
		McpSchema.ListToolsResult result = client.listTools("cursor-1", meta).block();
		assertThat(result).isNotNull();
		assertThat(result.tools()).hasSize(1);
		assertThat(transport.getCapturedRequest()).isNotNull();
		assertThat(transport.getCapturedRequest().cursor()).isEqualTo("cursor-1");
		assertThat(transport.getCapturedRequest().meta()).containsEntry("customKey", "customValue");
	}

	@Test
	void testListResourcesWithCursorAndMeta() {
		var transport = new TestMcpClientTransport();
		McpAsyncClient client = McpClient.async(transport).build();

		Map<String, Object> meta = Map.of("customKey", "customValue");
		McpSchema.ListResourcesResult result = client.listResources("cursor-1", meta).block();
		assertThat(result).isNotNull();
		assertThat(result.resources()).hasSize(1);
		assertThat(transport.getCapturedRequest()).isNotNull();
		assertThat(transport.getCapturedRequest().cursor()).isEqualTo("cursor-1");
		assertThat(transport.getCapturedRequest().meta()).containsEntry("customKey", "customValue");
	}

	@Test
	void testListResourceTemplatesWithCursorAndMeta() {
		var transport = new TestMcpClientTransport();
		McpAsyncClient client = McpClient.async(transport).build();

		Map<String, Object> meta = Map.of("customKey", "customValue");
		McpSchema.ListResourceTemplatesResult result = client.listResourceTemplates("cursor-1", meta).block();
		assertThat(result).isNotNull();
		assertThat(result.resourceTemplates()).hasSize(1);
		assertThat(transport.getCapturedRequest()).isNotNull();
		assertThat(transport.getCapturedRequest().cursor()).isEqualTo("cursor-1");
		assertThat(transport.getCapturedRequest().meta()).containsEntry("customKey", "customValue");
	}

	@Test
	void testListPromptsWithCursorAndMeta() {
		var transport = new TestMcpClientTransport();
		McpAsyncClient client = McpClient.async(transport).build();

		Map<String, Object> meta = Map.of("customKey", "customValue");
		McpSchema.ListPromptsResult result = client.listPrompts("cursor-1", meta).block();
		assertThat(result).isNotNull();
		assertThat(result.prompts()).hasSize(1);
		assertThat(transport.getCapturedRequest()).isNotNull();
		assertThat(transport.getCapturedRequest().cursor()).isEqualTo("cursor-1");
		assertThat(transport.getCapturedRequest().meta()).containsEntry("customKey", "customValue");

	}

	static class TestMcpClientTransport implements McpClientTransport {

		private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

		private McpSchema.PaginatedRequest capturedRequest = null;

		@Override
		public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
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
				McpSchema.ServerCapabilities caps = McpSchema.ServerCapabilities.builder()
					.prompts(false)
					.resources(false, false)
					.tools(false)
					.build();

				McpSchema.InitializeResult initResult = McpSchema.InitializeResult
					.builder(ProtocolVersions.MCP_2024_11_05, caps, MOCK_SERVER_INFO)
					.build();

				response = McpSchema.JSONRPCResponse.result(request.id(), initResult);
			}
			else if (McpSchema.METHOD_PROMPT_LIST.equals(request.method())) {
				capturedRequest = JSON_MAPPER.convertValue(request.params(), McpSchema.PaginatedRequest.class);

				McpSchema.Prompt mockPrompt = McpSchema.Prompt.builder("test-prompt")
					.description("A test prompt")
					.arguments(List.of())
					.build();
				McpSchema.ListPromptsResult mockPromptResult = McpSchema.ListPromptsResult.builder(List.of(mockPrompt))
					.build();
				response = McpSchema.JSONRPCResponse.result(request.id(), mockPromptResult);
			}
			else if (McpSchema.METHOD_RESOURCES_TEMPLATES_LIST.equals(request.method())) {
				capturedRequest = JSON_MAPPER.convertValue(request.params(), McpSchema.PaginatedRequest.class);

				McpSchema.ResourceTemplate mockTemplate = McpSchema.ResourceTemplate
					.builder("file:///{name}", "template")
					.build();
				McpSchema.ListResourceTemplatesResult mockResourceTemplateResult = McpSchema.ListResourceTemplatesResult
					.builder(List.of(mockTemplate))
					.build();
				response = McpSchema.JSONRPCResponse.result(request.id(), mockResourceTemplateResult);
			}
			else if (McpSchema.METHOD_RESOURCES_LIST.equals(request.method())) {
				capturedRequest = JSON_MAPPER.convertValue(request.params(), McpSchema.PaginatedRequest.class);

				McpSchema.Resource mockResource = McpSchema.Resource.builder("file:///test.txt", "test.txt").build();
				McpSchema.ListResourcesResult mockResourceResult = McpSchema.ListResourcesResult
					.builder(List.of(mockResource))
					.build();

				response = McpSchema.JSONRPCResponse.result(request.id(), mockResourceResult);
			}
			else if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
				capturedRequest = JSON_MAPPER.convertValue(request.params(), McpSchema.PaginatedRequest.class);

				McpSchema.Tool addTool = McpSchema.Tool.builder("add").description("calculate add").build();
				McpSchema.ListToolsResult mockToolsResult = McpSchema.ListToolsResult.builder(List.of(addTool)).build();
				response = McpSchema.JSONRPCResponse.result(request.id(), mockToolsResult);
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

		public McpSchema.PaginatedRequest getCapturedRequest() {
			return capturedRequest;
		}

	}

}
