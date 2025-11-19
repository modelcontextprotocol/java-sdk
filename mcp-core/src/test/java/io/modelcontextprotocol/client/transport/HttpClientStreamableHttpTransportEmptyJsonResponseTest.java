/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import reactor.test.StepVerifier;

/**
 * Handles emplty application/json response with 200 OK status code.
 *
 * @author codezkk
 */
public class HttpClientStreamableHttpTransportEmptyJsonResponseTest {

	static int PORT = TomcatTestUtil.findAvailablePort();

	static String host = "http://localhost:" + PORT;

	static HttpServer server;

	@BeforeAll
	static void startContainer() throws IOException {

		server = HttpServer.create(new InetSocketAddress(PORT), 0);

		// Empty, 200 OK response for the /mcp endpoint
		server.createContext("/mcp", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});

		server.setExecutor(null);
		server.start();
	}

	@AfterAll
	static void stopContainer() {
		server.stop(1);
	}

	/**
	 * Regardless of the response (even if the response is null and the content-type is
	 * present), notify should handle it correctly.
	 */
	@Test
	@Timeout(3)
	void testNotificationInitialized() throws URISyntaxException {

		var uri = new URI(host + "/mcp");
		var mockRequestCustomizer = mock(McpSyncHttpClientRequestCustomizer.class);
		var transport = HttpClientStreamableHttpTransport.builder(host)
			.httpRequestCustomizer(mockRequestCustomizer)
			.build();

		var initializeRequest = new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_03_26,
				McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("MCP Client", "0.3.1"));
		var testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE,
				"test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Verify the customizer was called
		verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("POST"), eq(uri), eq(
				"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"MCP Client\",\"version\":\"0.3.1\"}}}"),
				any());

	}

}
