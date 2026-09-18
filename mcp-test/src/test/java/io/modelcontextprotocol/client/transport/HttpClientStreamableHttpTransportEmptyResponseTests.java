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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
 * Handles 200 OK responses that carry no usable body, either as an empty application/json
 * document or as a text/event-stream containing nothing but a stream primer.
 *
 * @author codezkk
 */
public class HttpClientStreamableHttpTransportEmptyResponseTests {

	static int PORT = TomcatTestUtil.findAvailablePort();

	static String host = "http://localhost:" + PORT;

	static HttpServer server;

	/**
	 * An SSE event with an {@code event:} field but no data, which some servers send to
	 * open the response stream before any JSON-RPC payload is available. Note the
	 * valueless {@code data:} field: per the SSE spec this is identical to {@code data: }
	 * with a trailing space.
	 * @see <a href=
	 * "https://github.com/modelcontextprotocol/modelcontextprotocol/pull/1699">SEP-1699</a>
	 */
	private static final byte[] SSE_PRIMER = """
			event: message
			data:

			""".getBytes(StandardCharsets.UTF_8);

	@BeforeAll
	static void startContainer() throws IOException {

		server = HttpServer.create(new InetSocketAddress(PORT), 0);

		// Empty, 200 OK response for the /mcp endpoint
		server.createContext("/mcp", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});

		// 200 OK text/event-stream carrying only a primer, for POSTs. The
		// server-initiated GET stream is refused so that the transport falls back to
		// request-response mode and the POST is the only thing under test.
		server.createContext("/mcp-sse-primer", exchange -> {
			try (exchange) {
				if (!"POST".equals(exchange.getRequestMethod())) {
					exchange.sendResponseHeaders(405, -1);
					return;
				}
				exchange.getRequestBody().readAllBytes();
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
				exchange.sendResponseHeaders(200, SSE_PRIMER.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(SSE_PRIMER);
				}
			}
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

		var initializeRequest = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2025_03_26, McpSchema.ClientCapabilities.builder().roots(true).build(),
					McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
			.build();
		var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Verify the customizer was called
		verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("POST"), eq(uri), eq(
				"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"MCP Client\",\"version\":\"0.3.1\"}}}"),
				any());

	}

	/**
	 * A POST answered with {@code 200 text/event-stream} whose body holds only a stream
	 * primer must still complete, because the primer tells the client the stream is live
	 * and the message has been accepted. The primer's {@code data:} field carries no
	 * value, so this only holds as long as such a field still produces an event: a parser
	 * that drops it leaves no event to fire the transport's first-message callback, and
	 * {@code sendMessage} then never completes at all.
	 */
	@Test
	@Timeout(5)
	void testNotificationAnsweredWithSsePrimerOnly() {

		var transport = HttpClientStreamableHttpTransport.builder(host).endpoint("/mcp-sse-primer").build();

		var testMessage = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, "notifications/initialized",
				Map.of());

		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

	}

}
