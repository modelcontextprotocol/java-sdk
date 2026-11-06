/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that an {@code application/json} response whose body is not valid JSON fails
 * the {@link HttpClientStreamableHttpTransport#sendMessage} mono with the parsing error
 * instead of completing it successfully.
 *
 * <p>
 * Completing the delivery sink before deserialization used to swallow the parse failure:
 * the {@code McpClientSession} then never received the error, kept the pending response
 * entry and the caller only saw a {@code TimeoutException} once the request timeout
 * elapsed.
 *
 * @see <a href="https://github.com/modelcontextprotocol/java-sdk/issues/1147">#1147</a>
 */
public class HttpClientStreamableHttpTransportInvalidJsonResponseTest {

	static int PORT = TomcatTestUtil.findAvailablePort();

	static String host = "http://localhost:" + PORT;

	static HttpServer server;

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(PORT), 0);

		// 200 OK with an invalid JSON body for the /mcp endpoint
		server.createContext("/mcp", exchange -> {
			byte[] body = "{broken".getBytes();
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});

		server.setExecutor(null);
		server.start();
	}

	@AfterAll
	static void stopServer() {
		server.stop(1);
	}

	@Test
	@Timeout(10)
	void testInvalidJsonResponseFailsWithParseError() {
		var transport = HttpClientStreamableHttpTransport.builder(host).build();

		var initializeRequest = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2025_03_26, McpSchema.ClientCapabilities.builder().roots(true).build(),
					McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
			.build();
		var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage)).expectErrorSatisfies(error -> {
			// The parse failure must surface as the delivery error, not a timeout
			assertThat(error).isInstanceOf(McpTransportException.class);
		}).verify(Duration.ofSeconds(5));
	}

}
