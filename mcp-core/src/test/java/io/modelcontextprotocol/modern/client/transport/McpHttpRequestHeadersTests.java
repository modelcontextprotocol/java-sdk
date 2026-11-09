/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpHttpRequestHeadersTests {

	@Test
	void requestStartsWithTheHeadersTheRevisionRequires() {
		McpHttpRequestHeaders headers = McpHttpRequestHeaders.forRequest(McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, 1, Map.of()), McpSchema.LATEST_PROTOCOL_VERSION)
			.name("weather")
			.paramHeaders(Map.of("Region", "eu"))
			.build());

		assertThat(headers.get("content-type")).containsExactly("application/json");
		assertThat(headers.get("Accept")).containsExactly("application/json, text/event-stream");
		assertThat(headers.get("Mcp-Method")).containsExactly("tools/call");
		assertThat(headers.get("mcp-protocol-version")).containsExactly(McpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(headers.get("Mcp-Name")).containsExactly("weather");
		assertThat(headers.get("Mcp-Param-Region")).containsExactly("eu");
	}

	@ParameterizedTest
	@ValueSource(strings = { "Content-Type", "accept", "Content-Length", "mcp-method", "Mcp-Name",
			"mcp-protocol-version", "Mcp-Param-Region", "MCP-PARAM-x" })
	void headersTheTransportSetsAreRejected(String name) {
		McpHttpRequestHeaders headers = headers();

		assertThatIllegalArgumentException().isThrownBy(() -> headers.set(name, "x"));
		assertThatIllegalArgumentException().isThrownBy(() -> headers.add(name, "x"));
		assertThatIllegalArgumentException().isThrownBy(() -> headers.remove(name));
	}

	@Test
	void namesIgnoreCase() {
		McpHttpRequestHeaders headers = headers().add("X-Trace", "a").add("x-trace", "b");

		assertThat(headers.get("X-TRACE")).containsExactly("a", "b");
		assertThat(headers.asHttpHeaders().map()).containsEntry("X-Trace", List.of("a", "b"));
	}

	@Test
	void setReplacesAndRemoveClears() {
		McpHttpRequestHeaders headers = headers().add("Authorization", "Bearer old").set("authorization", "Bearer new");

		assertThat(headers.get("Authorization")).isEqualTo(List.of("Bearer new"));
		assertThat(headers.remove("AUTHORIZATION").get("Authorization")).isEmpty();
	}

	@Test
	void forEachPassesEveryValue() {
		McpHttpRequestHeaders headers = headers().add("X-Trace", "a").add("X-Trace", "b");
		List<String> sent = new ArrayList<>();

		headers.forEach((name, value) -> sent.add(name + ": " + value));

		assertThat(sent).contains("X-Trace: a", "X-Trace: b", "Mcp-Method: tools/list").hasSize(6);
	}

	private static McpHttpRequestHeaders headers() {
		return McpHttpRequestHeaders.forRequest(McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, 1, Map.of()), McpSchema.LATEST_PROTOCOL_VERSION)
			.build());
	}

}
