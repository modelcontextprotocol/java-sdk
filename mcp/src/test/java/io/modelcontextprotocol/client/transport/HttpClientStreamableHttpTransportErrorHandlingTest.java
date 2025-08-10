/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import reactor.test.StepVerifier;

/**
 * Tests for error handling changes in HttpClientStreamableHttpTransport. Specifically
 * tests the distinction between session-related errors and general transport errors for
 * 404 and 400 status codes.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
public class HttpClientStreamableHttpTransportErrorHandlingTest {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String HOST = "http://localhost:" + PORT;

	private HttpServer server;

	private AtomicReference<Integer> responseStatus = new AtomicReference<>(200);

	private AtomicReference<String> sessionId = new AtomicReference<>(null);

	private AtomicReference<String> lastReceivedSessionId = new AtomicReference<>(null);

	private McpClientTransport transport;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(PORT), 0);

		// Configure the /mcp endpoint with dynamic response
		server.createContext("/mcp", exchange -> {
			// Capture session ID from request if present
			String requestSessionId = exchange.getRequestHeaders().getFirst(HttpHeaders.MCP_SESSION_ID);
			lastReceivedSessionId.set(requestSessionId);

			int status = responseStatus.get();

			// Set response headers
			exchange.getResponseHeaders().set("Content-Type", "application/json");

			// Add session ID to response if configured
			String responseSessionId = sessionId.get();
			if (responseSessionId != null) {
				exchange.getResponseHeaders().set(HttpHeaders.MCP_SESSION_ID, responseSessionId);
			}

			// Send response based on configured status
			if (status == 200) {
				String response = "{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":\"test-id\"}";
				exchange.sendResponseHeaders(200, response.length());
				exchange.getResponseBody().write(response.getBytes());
			}
			else {
				exchange.sendResponseHeaders(status, 0);
			}
			exchange.close();
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

	/**
	 * Test that 404 response WITHOUT session ID throws McpTransportException (not
	 * SessionNotFoundException)
	 */
	@Test
	void test404WithoutSessionId() {
		responseStatus.set(404);
		sessionId.set(null); // No session ID in response

		var testMessage = createTestMessage();

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMatches(throwable -> throwable instanceof McpTransportException
					&& throwable.getMessage().contains("Not Found") && throwable.getMessage().contains("404")
					&& !(throwable instanceof McpTransportSessionNotFoundException))
			.verify();

		StepVerifier.create(transport.closeGracefully()).verifyComplete();
	}

	/**
	 * Test that 404 response WITH session ID throws McpTransportSessionNotFoundException
	 */
	@Test
	void test404WithSessionId() {
		// First establish a session
		responseStatus.set(200);
		sessionId.set("test-session-123");

		// Set up exception handler to verify session invalidation
		@SuppressWarnings("unchecked")
		Consumer<Throwable> exceptionHandler = mock(Consumer.class);
		transport.setExceptionHandler(exceptionHandler);

		// Connect with handler
		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

		// Send initial message to establish session
		var testMessage = createTestMessage();
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// The session should now be established, next request will include session ID
		// Now return 404 for next request
		responseStatus.set(404);

		// Send another message - should get SessionNotFoundException
		StepVerifier.create(transport.sendMessage(testMessage))
			.expectError(McpTransportSessionNotFoundException.class)
			.verify();

		// Verify exception handler was called with SessionNotFoundException
		verify(exceptionHandler).accept(any(McpTransportSessionNotFoundException.class));

		StepVerifier.create(transport.closeGracefully()).verifyComplete();
	}

	/**
	 * Test that 400 response WITHOUT session ID throws McpTransportException (not
	 * SessionNotFoundException)
	 */
	@Test
	void test400WithoutSessionId() {
		responseStatus.set(400);
		sessionId.set(null); // No session ID

		var testMessage = createTestMessage();

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMatches(throwable -> throwable instanceof McpTransportException
					&& throwable.getMessage().contains("Bad Request") && throwable.getMessage().contains("400")
					&& !(throwable instanceof McpTransportSessionNotFoundException))
			.verify();

		StepVerifier.create(transport.closeGracefully()).verifyComplete();
	}

	/**
	 * Test that 400 response WITH session ID throws McpTransportSessionNotFoundException
	 * This handles the case mentioned in the code comment about some implementations
	 * returning 400 for unknown session IDs.
	 */
	@Test
	void test400WithSessionId() {
		// First establish a session
		responseStatus.set(200);
		sessionId.set("test-session-456");

		// Set up exception handler
		@SuppressWarnings("unchecked")
		Consumer<Throwable> exceptionHandler = mock(Consumer.class);
		transport.setExceptionHandler(exceptionHandler);

		// Connect with handler
		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

		// Send initial message to establish session
		var testMessage = createTestMessage();
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// The session should now be established, next request will include session ID
		// Now return 400 for next request (simulating unknown session ID)
		responseStatus.set(400);

		// Send another message - should get SessionNotFoundException
		StepVerifier.create(transport.sendMessage(testMessage))
			.expectError(McpTransportSessionNotFoundException.class)
			.verify();

		// Verify exception handler was called
		verify(exceptionHandler).accept(any(McpTransportSessionNotFoundException.class));

		StepVerifier.create(transport.closeGracefully()).verifyComplete();
	}

	/**
	 * Test session recovery after SessionNotFoundException Verifies that a new session
	 * can be established after the old one is invalidated
	 */
	@Test
	void testSessionRecoveryAfter404() {
		// First establish a session
		responseStatus.set(200);
		sessionId.set("session-1");

		// Send initial message to establish session
		var testMessage = createTestMessage();
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// The session should now be established
		// Simulate session loss - return 404
		responseStatus.set(404);

		// This should fail with SessionNotFoundException
		StepVerifier.create(transport.sendMessage(testMessage))
			.expectError(McpTransportSessionNotFoundException.class)
			.verify();

		// Now server is back with new session
		responseStatus.set(200);
		sessionId.set("session-2");
		lastReceivedSessionId.set(null); // Reset to verify new session

		// Should be able to establish new session
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Verify no session ID was sent (since old session was invalidated)
		assertThat(lastReceivedSessionId.get()).isNull();

		// Next request should use the new session ID
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Session ID should now be sent with requests
		assertThat(lastReceivedSessionId.get()).isEqualTo("session-2");

		StepVerifier.create(transport.closeGracefully()).verifyComplete();
	}

	/**
	 * Test that reconnect (GET request) also properly handles 404/400 errors
	 */
	@Test
	void testReconnectErrorHandling() {

		// Set up SSE endpoint for GET requests
		server.createContext("/mcp-sse", exchange -> {
			String method = exchange.getRequestMethod();
			String requestSessionId = exchange.getRequestHeaders().getFirst(HttpHeaders.MCP_SESSION_ID);

			if ("GET".equals(method)) {
				int status = responseStatus.get();

				if (status == 404 && requestSessionId != null) {
					// 404 with session ID - should trigger SessionNotFoundException
					exchange.sendResponseHeaders(404, 0);
				}
				else if (status == 404) {
					// 404 without session ID - should trigger McpTransportException
					exchange.sendResponseHeaders(404, 0);
				}
				else {
					// Normal SSE response
					exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
					exchange.sendResponseHeaders(200, 0);
					// Send a test SSE event
					String sseData = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"test\",\"params\":{}}\n\n";
					exchange.getResponseBody().write(sseData.getBytes());
				}
			}
			else {
				// POST request handling
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				String responseSessionId = sessionId.get();
				if (responseSessionId != null) {
					exchange.getResponseHeaders().set(HttpHeaders.MCP_SESSION_ID, responseSessionId);
				}
				String response = "{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":\"test-id\"}";
				exchange.sendResponseHeaders(200, response.length());
				exchange.getResponseBody().write(response.getBytes());
			}
			exchange.close();
		});

		// Test with session ID - should get SessionNotFoundException
		responseStatus.set(200);
		sessionId.set("sse-session-1");

		var transport = HttpClientStreamableHttpTransport.builder(HOST)
			.endpoint("/mcp-sse")
			.openConnectionOnStartup(true) // This will trigger GET request on connect
			.build();

		// First connect successfully
		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

		// Send message to establish session
		var testMessage = createTestMessage();
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Now simulate server returning 404 on reconnect
		responseStatus.set(404);

		// This should trigger reconnect which will fail
		// The error should be handled internally and passed to exception handler

		StepVerifier.create(transport.closeGracefully()).verifyComplete();
	}

	private McpSchema.JSONRPCRequest createTestMessage() {
		var initializeRequest = new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_03_26,
				McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("Test Client", "1.0.0"));
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE, "test-id",
				initializeRequest);
	}

}
