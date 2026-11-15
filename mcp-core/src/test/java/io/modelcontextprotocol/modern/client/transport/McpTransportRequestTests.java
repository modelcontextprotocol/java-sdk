/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.util.HashMap;
import java.util.Map;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpTransportRequestTests {

	private static final String VERSION = McpSchema.LATEST_PROTOCOL_VERSION;

	private static JSONRPCRequest message(String method) {
		return new JSONRPCRequest(method, 1, Map.of());
	}

	@ParameterizedTest
	@ValueSource(
			strings = { McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_PROMPTS_GET, McpSchema.METHOD_RESOURCES_READ })
	void namedMethodRequiresName(String method) {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> McpTransportRequest.builder(message(method), VERSION).build());
		assertThat(McpTransportRequest.builder(message(method), VERSION).name("target").build().name())
			.isEqualTo("target");
	}

	@Test
	void otherMethodsMayGoWithoutName() {
		McpTransportRequest request = McpTransportRequest.builder(message(McpSchema.METHOD_TOOLS_LIST), VERSION)
			.build();

		assertThat(request.name()).isNull();
		assertThat(request.method()).isEqualTo(McpSchema.METHOD_TOOLS_LIST);
		assertThat(request.paramHeaders()).isEmpty();
	}

	@Test
	void otherMethodsMayCarryName() {
		McpTransportRequest request = McpTransportRequest.builder(message("example/resume"), VERSION)
			.name("task-1")
			.build();

		assertThat(request.name()).isEqualTo("task-1");
	}

	@Test
	void paramHeadersAreCopied() {
		Map<String, String> headers = new HashMap<>(Map.of("Region", "eu"));

		McpTransportRequest request = McpTransportRequest.builder(message(McpSchema.METHOD_TOOLS_CALL), VERSION)
			.name("forecast")
			.paramHeaders(headers)
			.build();
		headers.put("Region", "us");

		assertThat(request.paramHeaders()).containsExactly(Map.entry("Region", "eu"));
	}

	@Test
	void paramHeadersOnlyGoWithToolCalls() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> McpTransportRequest.builder(message(McpSchema.METHOD_PROMPTS_GET), VERSION)
				.name("greeting")
				.paramHeaders(Map.of("Region", "eu"))
				.build());
	}

	@Test
	void protocolVersionIsRequired() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> McpTransportRequest.builder(message(McpSchema.METHOD_TOOLS_LIST), " ").build());
	}

	@Test
	void transportDoesNotMirrorToolParamsUnlessItSaysSo() {
		McpClientTransport transport = request -> Flux.<JSONRPCMessage>empty();

		assertThat(transport.mirrorsToolParams()).isFalse();
	}

}
