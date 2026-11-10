/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpHttpRequestTests {

	private static final URI ENDPOINT = URI.create("https://example.com/mcp");

	@Test
	void carriesTheMethodAndNameOfTheMcpRequest() {
		McpTransportRequest request = McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, 1, Map.of()), McpSchema.LATEST_PROTOCOL_VERSION)
			.name("create_issue")
			.build();
		HttpHeaders headers = HttpHeaders.of(Map.of("Authorization", List.of("Bearer token")), (name, value) -> true);

		McpHttpRequest http = McpHttpRequest.builder(ENDPOINT, request, "{}").headers(headers).build();

		assertThat(http.uri()).isEqualTo(ENDPOINT);
		assertThat(http.httpMethod()).isEqualTo("POST");
		assertThat(http.mcpMethod()).isEqualTo(McpSchema.METHOD_TOOLS_CALL);
		assertThat(http.mcpName()).isEqualTo("create_issue");
		assertThat(http.body()).isEqualTo("{}");
		assertThat(http.headers().firstValue("Authorization")).hasValue("Bearer token");
	}

	@Test
	void startsWithoutHeaders() {
		McpTransportRequest request = McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, 1, Map.of()), McpSchema.LATEST_PROTOCOL_VERSION)
			.build();

		McpHttpRequest http = McpHttpRequest.builder(ENDPOINT, request, "{}").build();

		assertThat(http.mcpName()).isNull();
		assertThat(http.headers().map()).isEmpty();
	}

	@Test
	void responseInfoCarriesStatusAndHeaders() {
		HttpHeaders headers = HttpHeaders.of(Map.of("WWW-Authenticate", List.of("Bearer error=\"invalid_token\"")),
				(name, value) -> true);

		McpHttpResponseInfo info = McpHttpResponseInfo.of(401, headers);

		assertThat(info.statusCode()).isEqualTo(401);
		assertThat(info.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
	}

}
