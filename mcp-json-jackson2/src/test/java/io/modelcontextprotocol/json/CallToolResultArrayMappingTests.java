/*
 * Copyright 2026 the original author or authors.
 */

package io.modelcontextprotocol.json;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallToolResultArrayMappingTests {

	private final McpJsonMapper mapper = new JacksonMcpJsonMapper(
			JsonMapper.builder().enable(DeserializationFeature.USE_JAVA_ARRAY_FOR_JSON_ARRAY).build());

	static Stream<Arguments> results() {
		return Stream.of(false, true)
			.flatMap(sync -> Stream.of(Arguments.of(sync, "{\"content\":[],\"isError\":false}", true),
					Arguments.of(sync, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}", true),
					Arguments.of(sync, "{\"content\":[],\"structuredContent\":{\"answer\":42}}", true),
					Arguments.of(sync, "{\"isError\":false}", false), Arguments.of(sync, "{\"content\":null}", false),
					Arguments.of(sync, "{\"content\":\"invalid\"}", false)));
	}

	@ParameterizedTest
	@MethodSource("results")
	void validatesContentWhenUntypedArraysAreJavaArrays(boolean sync, String json, boolean valid) throws IOException {
		Object rawResult = mapper.readValue(json, Object.class);
		if (valid) {
			assertThat(((Map<?, ?>) rawResult).get("content")).isInstanceOf(Object[].class);
		}
		var transport = new ResultTransport(rawResult);
		var request = McpSchema.CallToolRequest.builder("probe").build();
		if (sync) {
			var client = McpClient.sync(transport).validateCallToolResultContent(true).build();
			try {
				client.initialize();
				if (valid) {
					assertThat(client.callTool(request))
						.isEqualTo(mapper.readValue(json, McpSchema.CallToolResult.class));
				}
				else {
					assertThatThrownBy(() -> client.callTool(request)).isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("CallToolResult.content");
				}
			}
			finally {
				client.close();
			}
		}
		else {
			var client = McpClient.async(transport).validateCallToolResultContent(true).build();
			try {
				client.initialize().block(Duration.ofSeconds(3));
				if (valid) {
					assertThat(client.callTool(request).block(Duration.ofSeconds(3)))
						.isEqualTo(mapper.readValue(json, McpSchema.CallToolResult.class));
				}
				else {
					assertThatThrownBy(() -> client.callTool(request).block(Duration.ofSeconds(3)))
						.isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("CallToolResult.content");
				}
			}
			finally {
				client.closeGracefully().block(Duration.ofSeconds(3));
			}
		}
	}

	private class ResultTransport implements McpClientTransport {

		private final Object result;

		private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

		ResultTransport(Object result) {
			this.result = result;
		}

		@Override
		public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
			this.handler = handler;
			return Mono.empty();
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			if (!(message instanceof McpSchema.JSONRPCRequest request)) {
				return Mono.empty();
			}
			Object response = McpSchema.METHOD_INITIALIZE.equals(request.method())
					? Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of("tools", Map.of()), "serverInfo",
							Map.of("name", "test", "version", "1"))
					: this.result;
			return handler.apply(Mono.just(McpSchema.JSONRPCResponse.result(request.id(), response))).then();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return mapper.convertValue(data, typeRef);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

	}

}
