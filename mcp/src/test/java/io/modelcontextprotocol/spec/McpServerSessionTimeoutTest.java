package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that demonstrates the timeout issue in MCP SDK v0.10.x where the server receives
 * requests but never sends responses.
 */
public class McpServerSessionTimeoutTest {

	@Test
	@Timeout(10)
	public void testServerShouldRespondToToolsListRequest() throws Exception {
		// Track if response was sent
		AtomicBoolean responseSent = new AtomicBoolean(false);
		AtomicReference<JSONRPCMessage> capturedResponse = new AtomicReference<>();
		CountDownLatch responseLatch = new CountDownLatch(1);

		// Create a test transport that captures messages
		McpServerTransport testTransport = new TestTransport() {
			@Override
			public Mono<Void> sendMessage(JSONRPCMessage message) {
				System.out.println("Transport sendMessage called with: " + message);
				responseSent.set(true);
				capturedResponse.set(message);
				responseLatch.countDown();
				return Mono.empty();
			}
		};

		// Create a reference to hold the session factory
		final McpServerSession.Factory[] sessionFactoryHolder = new McpServerSession.Factory[1];

		// Create transport provider
		McpServerTransportProvider transportProvider = new McpServerTransportProvider() {
			@Override
			public void setSessionFactory(McpServerSession.Factory sessionFactory) {
				// Store the factory for later use
				sessionFactoryHolder[0] = sessionFactory;
			}

			@Override
			public Mono<Void> notifyClients(String method, Object params) {
				return Mono.empty();
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}

			@Override
			public void close() {
			}
		};

		// Create server with a simple tool
		McpAsyncServer server = McpServer.async(transportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		// Add a test tool
		McpSchema.Tool testTool = new McpSchema.Tool("test_tool", "A test tool",
				new JsonSchema("object", null, null, false, null, null));

		server
			.addTool(new io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification(testTool,
					(exchange, params) -> Mono
						.just(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("test result")), false))))
			.block();

		// Now that the server is built and handlers are registered, create a session
		assertNotNull(sessionFactoryHolder[0], "Session factory should have been set");
		McpServerSession session = sessionFactoryHolder[0].create(testTransport);

		// First, simulate the initialization flow
		// 1. Send initialize request
		JSONRPCRequest initRequest = new JSONRPCRequest("2.0", "initialize", "init-1",
				new McpSchema.InitializeRequest("2024-11-05", new McpSchema.ClientCapabilities(null, null, null),
						new McpSchema.Implementation("test-client", "1.0.0")));

		session.handle(initRequest).block();

		// 2. Send initialized notification to complete initialization
		JSONRPCNotification initNotification = new JSONRPCNotification("2.0", "notifications/initialized", null);
		session.handle(initNotification).block();

		// Now simulate receiving a tools/list request
		JSONRPCRequest toolsListRequest = new JSONRPCRequest("2.0", "tools/list", "test-1", null);

		System.out.println("Sending tools/list request to session");

		// Handle the request
		session.handle(toolsListRequest)
			.doOnSubscribe(s -> System.out.println("Subscribed to handle()"))
			.doOnNext(v -> System.out.println("handle() emitted: " + v))
			.doOnSuccess(v -> System.out.println("handle() completed"))
			.doOnError(e -> System.out.println("handle() error: " + e))
			.doOnTerminate(() -> System.out.println("handle() terminated"))
			.block(Duration.ofSeconds(2));

		// Wait for response with timeout
		boolean gotResponse = responseLatch.await(5, TimeUnit.SECONDS);

		// Assertions
		assertTrue(gotResponse, "Server should send a response within 5 seconds");
		assertTrue(responseSent.get(), "Server should have called sendMessage on transport");
		assertNotNull(capturedResponse.get(), "Response should not be null");

		// Verify it's a proper tools/list response
		if (capturedResponse.get() instanceof JSONRPCResponse response) {
			assertEquals("test-1", response.id(), "Response ID should match request ID");
			assertNull(response.error(), "Response should not have an error");
			assertNotNull(response.result(), "Response should have a result");
		}
		else {
			fail("Expected JSONRPCResponse but got: " + capturedResponse.get().getClass());
		}
	}

	/**
	 * Test transport base implementation
	 */
	private static abstract class TestTransport implements McpServerTransport {

		@Override
		public <T> T unmarshalFrom(Object data, com.fasterxml.jackson.core.type.TypeReference<T> typeRef) {
			// Simple implementation for testing
			return (T) data;
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public void close() {
		}

	}

}