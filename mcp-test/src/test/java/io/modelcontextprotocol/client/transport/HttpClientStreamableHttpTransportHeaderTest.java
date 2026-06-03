/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import reactor.test.StepVerifier;

class HttpClientStreamableHttpTransportHeaderTest {

	@Test
	void sendsMcpMethodHeaderForInitializeRequests() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		try {
			server.createContext("/mcp", exchange -> {
				try (exchange) {
					if (!"POST".equals(exchange.getRequestMethod())) {
						exchange.sendResponseHeaders(405, -1);
						return;
					}

					String methodHeader = exchange.getRequestHeaders().getFirst("Mcp-Method");
					byte[] requestBody = exchange.getRequestBody().readAllBytes();
					String body = new String(requestBody, StandardCharsets.UTF_8);

					if (!"initialize".equals(methodHeader) || !body.contains("\"method\":\"initialize\"")) {
						exchange.sendResponseHeaders(400, 0);
						return;
					}

					exchange.sendResponseHeaders(202, -1);
				}
			});
			server.start();

			int port = server.getAddress().getPort();
			var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
				.endpoint("/mcp")
				.build();
			try {
				var initializeRequest = McpSchema.InitializeRequest
					.builder(ProtocolVersions.MCP_2025_11_25,
							McpSchema.ClientCapabilities.builder().roots(true).build(),
							McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
					.build();
				var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id",
						initializeRequest);

				StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();
			}
			finally {
				StepVerifier.create(transport.closeGracefully()).verifyComplete();
			}
		}
		finally {
			server.stop(0);
		}
	}

}
