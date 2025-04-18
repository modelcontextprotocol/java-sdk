/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import org.springframework.http.codec.ServerSentEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for the {@link HttpClientSseClientTransportProvider.HttpClientSseClientTransport}
 * class.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class HttpClientSseClientTransportTests {

	static String host = "http://localhost:3001";

	@SuppressWarnings("resource")
	GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v1")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private TestHttpClientSseClientTransport transport;

	// Test class to access protected methods
	static class TestHttpClientSseClientTransport implements McpClientTransport {

		McpClientTransport delegate;

		private final AtomicInteger inboundMessageCount = new AtomicInteger(0);

		private Sinks.Many<ServerSentEvent<String>> events = Sinks.many().unicast().onBackpressureBuffer();

		public TestHttpClientSseClientTransport(McpClientTransport transport) {
			this.delegate = transport;
		}

		public int getInboundMessageCount() {
			return inboundMessageCount.get();
		}

		public void simulateEndpointEvent(String jsonMessage) {
			events.tryEmitNext(ServerSentEvent.<String>builder().event("endpoint").data(jsonMessage).build());
			inboundMessageCount.incrementAndGet();
		}

		public void simulateMessageEvent(String jsonMessage) {
			events.tryEmitNext(ServerSentEvent.<String>builder().event("message").data(jsonMessage).build());
			inboundMessageCount.incrementAndGet();
		}

		@Override
		public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
			return delegate.connect(handler);
		}

		@Override
		public Mono<Void> connect() {
			return delegate.connect();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return delegate.closeGracefully();
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return delegate.sendMessage(message);
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return delegate.unmarshalFrom(data, typeRef);
		}

	}

	void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@BeforeEach
	void setUp() {
		startContainer();
		HttpClientSseClientTransportProvider transportProvider = HttpClientSseClientTransportProvider.builder(host)
			.build();
		transportProvider.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		transport = new TestHttpClientSseClientTransport(transportProvider.getSession().getTransport());
		transport.connect().block();
	}

	@AfterEach
	void afterEach() {
		if (transport != null) {
			assertThatCode(() -> transport.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
		}
		cleanup();
	}

	void cleanup() {
		container.stop();
	}

	@Test
	void testMessageProcessing() {
		// Create a test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Simulate receiving the message
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "test-method",
				    "id": "test-id",
				    "params": {"key": "value"}
				}
				""");

		// Subscribe to messages and verify
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testResponseMessageProcessing() {
		// Simulate receiving a response message
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "id": "test-id",
				    "result": {"status": "success"}
				}
				""");

		// Create and send a request message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testErrorMessageProcessing() {
		// Simulate receiving an error message
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "id": "test-id",
				    "error": {
				        "code": -32600,
				        "message": "Invalid Request"
				    }
				}
				""");

		// Create and send a request message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testNotificationMessageProcessing() {
		// Simulate receiving a notification message (no id)
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "update",
				    "params": {"status": "processing"}
				}
				""");

		// Verify the notification was processed
		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testGracefulShutdown() {
		// Test graceful shutdown
		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		// Create a test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Verify message is not processed after shutdown
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Message count should remain 0 after shutdown
		assertThat(transport.getInboundMessageCount()).isZero();
	}

	@Test
	void testRetryBehavior() {
		// Create a client that simulates connection failures
		HttpClientSseClientTransportProvider failingTransportProvider = HttpClientSseClientTransportProvider
			.builder("http://non-existent-host")
			.build();
		failingTransportProvider.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		failingTransportProvider.getSession();
		// Verify that the transport attempts to reconnect
		StepVerifier.create(Mono.delay(Duration.ofSeconds(2))).expectNextCount(1).verifyComplete();

		// Clean up
		failingTransportProvider.closeGracefully().block();
	}

	@Test
	void testMultipleMessageProcessing() {
		// Simulate receiving multiple messages in sequence
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "method1",
				    "id": "id1",
				    "params": {"key": "value1"}
				}
				""");

		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "method2",
				    "id": "id2",
				    "params": {"key": "value2"}
				}
				""");

		// Create and send corresponding messages
		JSONRPCRequest message1 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method1", "id1",
				Map.of("key", "value1"));

		JSONRPCRequest message2 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method2", "id2",
				Map.of("key", "value2"));

		// Verify both messages are processed
		StepVerifier.create(transport.sendMessage(message1).then(transport.sendMessage(message2))).verifyComplete();

		// Verify message count
		assertThat(transport.getInboundMessageCount()).isEqualTo(2);
	}

	@Test
	void testMessageOrderPreservation() {
		// Simulate receiving messages in a specific order
		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "first",
				    "id": "1",
				    "params": {"sequence": 1}
				}
				""");

		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "second",
				    "id": "2",
				    "params": {"sequence": 2}
				}
				""");

		transport.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "third",
				    "id": "3",
				    "params": {"sequence": 3}
				}
				""");

		// Verify message count and order
		assertThat(transport.getInboundMessageCount()).isEqualTo(3);
	}

	@Test
	void testCustomizeClient() {
		// Create an atomic boolean to verify the customizer was called
		AtomicBoolean customizerCalled = new AtomicBoolean(false);

		// Create a transport with the customizer
		HttpClientSseClientTransportProvider customizedTransport = HttpClientSseClientTransportProvider.builder(host)
			.customizeClient(builder -> {
				builder.version(HttpClient.Version.HTTP_2);
				customizerCalled.set(true);
			})
			.build();
		customizedTransport.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		customizedTransport.getSession();

		// Verify the customizer was called
		assertThat(customizerCalled.get()).isTrue();

		// Clean up
		customizedTransport.closeGracefully().block();
	}

	@Test
	void testCustomizeRequest() {
		// Create an atomic boolean to verify the customizer was called
		AtomicBoolean customizerCalled = new AtomicBoolean(false);

		// Create a reference to store the custom header value
		AtomicReference<String> headerName = new AtomicReference<>();
		AtomicReference<String> headerValue = new AtomicReference<>();

		// Create a transport with the customizer
		HttpClientSseClientTransportProvider customizedTransport = HttpClientSseClientTransportProvider.builder(host)
			// Create a request customizer that adds a custom header
			.customizeRequest(builder -> {
				builder.header("X-Custom-Header", "test-value");
				customizerCalled.set(true);

				// Create a new request to verify the header was set
				HttpRequest request = builder.uri(URI.create("http://example.com")).build();
				headerName.set("X-Custom-Header");
				headerValue.set(request.headers().firstValue("X-Custom-Header").orElse(null));
			})
			.build();
		customizedTransport.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		customizedTransport.getSession();

		// Verify the customizer was called
		assertThat(customizerCalled.get()).isTrue();

		// Verify the header was set correctly
		assertThat(headerName.get()).isEqualTo("X-Custom-Header");
		assertThat(headerValue.get()).isEqualTo("test-value");

		// Clean up
		customizedTransport.closeGracefully().block();
	}

	@Test
	void testChainedCustomizations() {
		// Create atomic booleans to verify both customizers were called
		AtomicBoolean clientCustomizerCalled = new AtomicBoolean(false);
		AtomicBoolean requestCustomizerCalled = new AtomicBoolean(false);

		// Create a transport with both customizers chained
		HttpClientSseClientTransportProvider customizedTransport = HttpClientSseClientTransportProvider.builder(host)
			.customizeClient(builder -> {
				builder.connectTimeout(Duration.ofSeconds(30));
				clientCustomizerCalled.set(true);
			})
			.customizeRequest(builder -> {
				builder.header("X-Api-Key", "test-api-key");
				requestCustomizerCalled.set(true);
			})
			.build();
		customizedTransport.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		customizedTransport.getSession();

		// Verify both customizers were called
		assertThat(clientCustomizerCalled.get()).isTrue();
		assertThat(requestCustomizerCalled.get()).isTrue();

		// Clean up
		customizedTransport.closeGracefully().block();
	}

}
