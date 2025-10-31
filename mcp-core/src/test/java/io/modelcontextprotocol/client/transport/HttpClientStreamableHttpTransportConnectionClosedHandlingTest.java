/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author taobaorun
 */
public class HttpClientStreamableHttpTransportConnectionClosedHandlingTest {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String HOST = "http://localhost:" + PORT;

	private HttpServer server;

	private AtomicReference<Integer> serverResponseStatus = new AtomicReference<>(200);

	private AtomicReference<String> currentServerSessionId = new AtomicReference<>(null);

	private AtomicReference<String> lastReceivedSessionId = new AtomicReference<>(null);

	private McpClientTransport transport;

	private McpTransportContext context = McpTransportContext
		.create(Map.of("test-transport-context-key", "some-value"));

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(PORT), 0);

		// Configure the /mcp endpoint with dynamic response
		server.createContext("/mcp", httpExchange -> {
			if ("DELETE".equals(httpExchange.getRequestMethod())) {
				httpExchange.sendResponseHeaders(200, 0);
			}
			else if ("POST".equals(httpExchange.getRequestMethod())) {
				// Capture session ID from request if present
				String requestSessionId = httpExchange.getRequestHeaders().getFirst(HttpHeaders.MCP_SESSION_ID);
				lastReceivedSessionId.set(requestSessionId);

				int status = serverResponseStatus.get();

				// Set response headers
				httpExchange.getResponseHeaders().set("Content-Type", "application/json");

				// Add session ID to response if configured
				String responseSessionId = currentServerSessionId.get();
				if (responseSessionId != null) {
					httpExchange.getResponseHeaders().set(HttpHeaders.MCP_SESSION_ID, responseSessionId);
				}

				// Send response based on configured status
				if (status == 200) {
					String response = "{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":\"test-id\"}";
					httpExchange.sendResponseHeaders(200, response.length());
					httpExchange.getResponseBody().write(response.getBytes());
				}
				else {
					httpExchange.sendResponseHeaders(status, 0);
				}
			}
			httpExchange.close();
		});

		server.setExecutor(null);
		server.start();

		transport = HttpClientStreamableHttpTransport.builder(HOST).build();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void testTransportConnectionClosedHandler() throws URISyntaxException {
		AtomicReference<Boolean> closedHandlerCalled = new AtomicReference<>(false);
		var transport = HttpClientStreamableHttpTransport.builder(HOST)
			.connectionClosedHandler(v -> closedHandlerCalled.set(true))
			.build();

		withTransport(transport, (t) -> {
			// Send test message
			var initializeRequest = new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_06_18,
					McpSchema.ClientCapabilities.builder().roots(true).build(),
					new McpSchema.Implementation("Spring AI MCP Client", "0.3.1"));
			var testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE,
					"test-id", initializeRequest);

			StepVerifier
				.create(t.sendMessage(testMessage).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
				.verifyComplete();

			// close transport
			transport.closeGracefully().subscribe();
			assertTrue(closedHandlerCalled.get());

		});
	}

	void withTransport(HttpClientStreamableHttpTransport transport, Consumer<HttpClientStreamableHttpTransport> c) {
		try {
			c.accept(transport);
		}
		finally {
			StepVerifier.create(transport.closeGracefully()).verifyComplete();
		}
	}

}
