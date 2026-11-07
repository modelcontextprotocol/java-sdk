/*
 * Copyright 2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class CallToolResultContentValidationTests {

	private static final McpSchema.CallToolRequest REQUEST = McpSchema.CallToolRequest.builder("probe").build();

	private HttpServer server;

	private HttpClientStreamableHttpTransport transport;

	private volatile String toolResult = "{\"isError\":false}";

	private volatile boolean outputSchema;

	private volatile boolean rpcError;

	private final AtomicInteger toolCalls = new AtomicInteger();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/mcp", this::respond);
		server.start();
		transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + server.getAddress().getPort())
			.openConnectionOnStartup(false)
			.resumableStreams(false)
			.build();
	}

	@AfterEach
	void stopServer() {
		transport.closeGracefully().block(Duration.ofSeconds(2));
		server.stop(0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"isError\":false}", "{\"content\":null,\"isError\":false}", "{\"isError\":true}",
			"{\"content\":{}}", "{\"content\":\"text\"}", "[]" })
	void asyncClientRejectsMissingOrNullContent(String result) {
		toolResult = result;
		var client = McpClient.async(transport).validateCallToolResultContent(true).build();
		try {
			StepVerifier.create(client.initialize().then(client.callTool(REQUEST)))
				.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("CallToolResult.content"))
				.verify(Duration.ofSeconds(3));
			assertThat(toolCalls).hasValue(1);
			toolResult = "{\"content\":[],\"isError\":false}";
			StepVerifier.create(client.callTool(REQUEST))
				.assertNext(value -> assertThat(value.content()).isEmpty())
				.verifyComplete();
			assertThat(toolCalls).hasValue(2);
		}
		finally {
			client.close();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"isError\":false}", "{\"content\":null,\"isError\":false}", "{\"isError\":true}",
			"{\"content\":{}}", "{\"content\":\"text\"}", "[]" })
	void syncClientRejectsMissingOrNullContent(String result) {
		toolResult = result;
		var client = McpClient.sync(transport).validateCallToolResultContent(true).build();
		try {
			client.initialize();
			assertThatThrownBy(() -> client.callTool(REQUEST)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("CallToolResult.content");
			assertThat(toolCalls).hasValue(1);
		}
		finally {
			client.close();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"isError\":false}", "{\"content\":null,\"isError\":false}" })
	void defaultClientStillAcceptsMissingOrNullContent(String result) {
		toolResult = result;
		var client = McpClient.async(transport).build();
		try {
			StepVerifier.create(client.initialize().then(client.callTool(REQUEST))).assertNext(value -> {
				assertThat(value.content()).isEmpty();
				assertThat(value.isError()).isFalse();
			}).verifyComplete();
		}
		finally {
			client.close();
		}
	}

	@ParameterizedTest
	@ValueSource(
			strings = { "{\"content\":[]}", "{\"content\":[],\"isError\":false}", "{\"content\":[],\"isError\":true}",
					"{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}",
					"{\"content\":[],\"structuredContent\":{\"answer\":42},\"futureField\":true}" })
	void strictAsyncClientPreservesValidResults(String result) throws IOException {
		toolResult = result;
		var expected = McpJsonDefaults.getMapper().readValue(result, McpSchema.CallToolResult.class);
		var client = McpClient.async(transport).validateCallToolResultContent(true).build();
		try {
			StepVerifier.create(client.initialize().then(client.listTools()).then(client.callTool(REQUEST)))
				.assertNext(value -> assertThat(value).isEqualTo(expected))
				.verifyComplete();
		}
		finally {
			client.close();
		}
	}

	@Test
	void strictSyncClientAcceptsExplicitEmptyContent() {
		toolResult = "{\"content\":[],\"isError\":false}";
		var client = McpClient.sync(transport).validateCallToolResultContent(true).build();
		try {
			client.initialize();
			assertThat(client.callTool(REQUEST).content()).isEmpty();
		}
		finally {
			client.close();
		}
	}

	private void respond(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				return;
			}
			var request = McpJsonDefaults.getMapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class);
			if (!request.containsKey("id")) {
				exchange.sendResponseHeaders(202, -1);
				return;
			}
			String result = switch ((String) request.get("method")) {
				case "initialize" -> """
						{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},
						"serverInfo":{"name":"test","version":"1"}}
						""";
				case "tools/list" -> outputSchema
						? """
								{"tools":[{"name":"probe","inputSchema":{"type":"object"},
								"outputSchema":{"type":"object","properties":{"answer":{"type":"integer"}},"required":["answer"]}}]}
								"""
						: """
								{"tools":[{"name":"probe","inputSchema":{"type":"object"}}]}
								""";
				case "tools/call" -> {
					toolCalls.incrementAndGet();
					yield toolResult;
				}
				default -> "{}";
			};
			String id = McpJsonDefaults.getMapper().writeValueAsString(request.get("id"));
			String payload = rpcError && "tools/call".equals(request.get("method"))
					? "\"error\":{\"code\":-32602,\"message\":\"bad arguments\"}" : "\"result\":" + result;
			byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + "," + payload + "}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"isError\":false}", "{\"content\":null,\"isError\":false}" })
	void syncClientRemainsLenientWhenValidationIsDisabled(String result) {
		toolResult = result;
		var client = McpClient.sync(transport).validateCallToolResultContent(false).build();
		try {
			client.initialize();
			assertThat(client.callTool(REQUEST).content()).isEmpty();
		}
		finally {
			client.close();
		}
	}

	@Test
	void strictClientPreservesJsonRpcErrors() {
		rpcError = true;
		var client = McpClient.async(transport).validateCallToolResultContent(true).build();
		try {
			StepVerifier.create(client.initialize().then(client.callTool(REQUEST))).verifyError(McpError.class);
			assertThat(toolCalls).hasValue(1);
		}
		finally {
			client.close();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void strictContentValidationStillAppliesOutputSchema(boolean validOutput) {
		outputSchema = true;
		toolResult = validOutput ? "{\"content\":[],\"structuredContent\":{\"answer\":42}}"
				: "{\"content\":[],\"structuredContent\":{\"answer\":\"wrong type\"}}";
		var client = McpClient.async(transport)
			.validateCallToolResultContent(true)
			.enableCallToolSchemaCaching(true)
			.build();
		try {
			var verifier = StepVerifier
				.create(client.initialize().then(client.listTools()).then(client.callTool(REQUEST)));
			if (validOutput) {
				verifier.assertNext(result -> assertThat(result.structuredContent()).isEqualTo(Map.of("answer", 42)))
					.verifyComplete();
			}
			else {
				verifier
					.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("Tool call result validation failed"))
					.verify();
			}
		}
		finally {
			client.close();
		}
	}

}
