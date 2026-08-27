/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link HttpClientStreamableHttpTransport} emits the SEP-2243
 * {@code Mcp-Method} and {@code Mcp-Name} headers on Streamable HTTP requests.
 */
class Sep2243ClientRequestHeaderTests {

	@Test
	void emitsMcpMethodAndNameForToolCallRequest() throws IOException {
		var seenMethods = new java.util.concurrent.CopyOnWriteArrayList<String>();
		var seenNames = new java.util.concurrent.CopyOnWriteArrayList<String>();
		var server = HttpServer.create(new InetSocketAddress(0), 0);

		try {
			server.createContext("/mcp", exchange -> {
				seenMethods.add(exchange.getRequestHeaders().getFirst(HttpHeaders.MCP_METHOD));
				seenNames.add(exchange.getRequestHeaders().getFirst(HttpHeaders.MCP_NAME));
				exchange.getRequestBody().readAllBytes();
				exchange.sendResponseHeaders(202, -1);
				exchange.close();
			});
			server.start();

			var transport = HttpClientStreamableHttpTransport
				.builder("http://localhost:" + server.getAddress().getPort())
				.endpoint("/mcp")
				.build();

			try {
				var request = new McpSchema.CallToolRequest("test-tool", Map.of(), null);
				var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, "test-id", request);
				StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();
			}
			finally {
				StepVerifier.create(transport.closeGracefully()).verifyComplete();
			}

			assertThat(seenMethods).contains(McpSchema.METHOD_TOOLS_CALL);
			assertThat(seenNames).contains("test-tool");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void emitsMcpMethodForNotification() throws IOException {
		var seenMethodHeaders = new java.util.concurrent.CopyOnWriteArrayList<String>();
		var server = HttpServer.create(new InetSocketAddress(0), 0);

		try {
			server.createContext("/mcp", exchange -> {
				seenMethodHeaders.add(exchange.getRequestHeaders().getFirst(HttpHeaders.MCP_METHOD));
				exchange.getRequestBody().readAllBytes();
				exchange.sendResponseHeaders(202, -1);
				exchange.close();
			});
			server.start();

			var transport = HttpClientStreamableHttpTransport
				.builder("http://localhost:" + server.getAddress().getPort())
				.endpoint("/mcp")
				.build();

			try {
				var notification = new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED);
				StepVerifier.create(transport.sendMessage(notification)).verifyComplete();
			}
			finally {
				StepVerifier.create(transport.closeGracefully()).verifyComplete();
			}

			assertThat(seenMethodHeaders).contains(McpSchema.METHOD_NOTIFICATION_INITIALIZED);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void omitsMcpProtocolVersionHeaderOnInitializeRequest() throws IOException {
		var seenProtocolVersions = new java.util.concurrent.CopyOnWriteArrayList<String>();
		var server = HttpServer.create(new InetSocketAddress(0), 0);

		try {
			server.createContext("/mcp", exchange -> {
				seenProtocolVersions.add(exchange.getRequestHeaders().getFirst(HttpHeaders.PROTOCOL_VERSION));
				exchange.getRequestBody().readAllBytes();
				exchange.sendResponseHeaders(202, -1);
				exchange.close();
			});
			server.start();

			var transport = HttpClientStreamableHttpTransport
				.builder("http://localhost:" + server.getAddress().getPort())
				.endpoint("/mcp")
				.supportedProtocolVersions(java.util.List.of(ProtocolVersions.MCP_2025_11_25, "2263-03-18"))
				.build();

			try {
				// The initialize request carries the client's latest supported version in
				// its body for negotiation; sending an MCP-Protocol-Version header would
				// make strict servers reject it before negotiation happens.
				var initRequest = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id",
						Map.of("protocolVersion", "2263-03-18"));
				StepVerifier.create(transport.sendMessage(initRequest)).verifyComplete();
			}
			finally {
				StepVerifier.create(transport.closeGracefully()).verifyComplete();
			}

			assertThat(seenProtocolVersions).containsNull();
		}
		finally {
			server.stop(0);
		}
	}

}
