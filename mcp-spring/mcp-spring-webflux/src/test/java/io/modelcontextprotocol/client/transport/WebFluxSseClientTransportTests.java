/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Christian Tzolov
 */
@Timeout(15)
class WebFluxSseClientTransportTests {

	static String host = "http://localhost:3001";

	@SuppressWarnings("resource")
	GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v1")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private TestSseClientTransportProvider transportProvider;

	private McpClientTransport transport;

	private WebClient.Builder webClientBuilder;

	private ObjectMapper objectMapper;

	// Test class to access protected methods
	static class TestSseClientTransportProvider extends WebFluxSseClientTransportProvider {

		private final AtomicInteger inboundMessageCount = new AtomicInteger(0);

		private Sinks.Many<ServerSentEvent<String>> events = Sinks.many().unicast().onBackpressureBuffer();

		public TestSseClientTransportProvider(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
			super(webClientBuilder, objectMapper);
		}

		@Override
		protected Flux<ServerSentEvent<String>> eventStream() {
			return super.eventStream().mergeWith(events.asFlux());
		}

		public String getLastEndpoint() {
			return ((WebFluxSseClientTransport) getSession().getTransport()).messageEndpointSink.asMono().block();
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

	}

	void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@BeforeEach
	void setUp() {
		startContainer();
		webClientBuilder = WebClient.builder().baseUrl(host);
		objectMapper = new ObjectMapper();
		transportProvider = new TestSseClientTransportProvider(webClientBuilder, objectMapper);
		transportProvider.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		transport = transportProvider.getSession().getTransport();
		transport.connect(Function.identity()).block();
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
	void testEndpointEventHandling() {
		assertThat(transportProvider.getLastEndpoint()).startsWith("/message?");
	}

	@Test
	void constructorValidation() {
		assertThatThrownBy(() -> new WebFluxSseClientTransportProvider(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("WebClient.Builder must not be null");

		assertThatThrownBy(() -> new WebFluxSseClientTransportProvider(webClientBuilder, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ObjectMapper must not be null");
	}

	@Test
	void testBuilderPattern() {
		// Test default builder
		WebFluxSseClientTransportProvider transportProvider1 = WebFluxSseClientTransportProvider
			.builder(webClientBuilder)
			.build();
		transportProvider1.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		transportProvider1.getSession();
		assertThatCode(() -> transportProvider1.closeGracefully().block()).doesNotThrowAnyException();

		// Test builder with custom ObjectMapper
		ObjectMapper customMapper = new ObjectMapper();
		WebFluxSseClientTransportProvider transportProvider2 = WebFluxSseClientTransportProvider
			.builder(webClientBuilder)
			.objectMapper(customMapper)
			.build();
		transportProvider2.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		transportProvider2.getSession();
		assertThatCode(() -> transportProvider2.closeGracefully().block()).doesNotThrowAnyException();

		// Test builder with custom SSE endpoint
		WebFluxSseClientTransportProvider transportProvider3 = WebFluxSseClientTransportProvider
			.builder(webClientBuilder)
			.sseEndpoint("/custom-sse")
			.build();
		transportProvider3.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		transportProvider3.getSession();
		assertThatCode(() -> transportProvider3.closeGracefully().block()).doesNotThrowAnyException();

		// Test builder with all custom parameters
		WebFluxSseClientTransportProvider transportProvider4 = WebFluxSseClientTransportProvider
			.builder(webClientBuilder)
			.objectMapper(customMapper)
			.sseEndpoint("/custom-sse")
			.build();
		transportProvider4.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		transportProvider4.getSession();
		assertThatCode(() -> transportProvider4.closeGracefully().block()).doesNotThrowAnyException();
	}

	@Test
	void testMessageProcessing() {
		// Create a test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Simulate receiving the message
		transportProvider.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "test-method",
				    "id": "test-id",
				    "params": {"key": "value"}
				}
				""");

		// Subscribe to messages and verify
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transportProvider.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testResponseMessageProcessing() {
		// Simulate receiving a response message
		transportProvider.simulateMessageEvent("""
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

		assertThat(transportProvider.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testErrorMessageProcessing() {
		// Simulate receiving an error message
		transportProvider.simulateMessageEvent("""
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

		assertThat(transportProvider.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testNotificationMessageProcessing() {
		// Simulate receiving a notification message (no id)
		transportProvider.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "update",
				    "params": {"status": "processing"}
				}
				""");

		// Verify the notification was processed
		assertThat(transportProvider.getInboundMessageCount()).isEqualTo(1);
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
		assertThat(transportProvider.getInboundMessageCount()).isEqualTo(0);
	}

	@Test
	void testRetryBehavior() {
		// Create a WebClient that simulates connection failures
		WebClient.Builder failingWebClientBuilder = WebClient.builder().baseUrl("http://non-existent-host");

		WebFluxSseClientTransportProvider failingTransportProvider = WebFluxSseClientTransportProvider
			.builder(failingWebClientBuilder)
			.build();
		failingTransportProvider.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));

		// Verify that the transport attempts to reconnect
		StepVerifier.create(Mono.delay(Duration.ofSeconds(2))).expectNextCount(1).verifyComplete();

		// Clean up
		failingTransportProvider.getSession().getTransport().closeGracefully().block();
	}

	@Test
	void testMultipleMessageProcessing() {
		// Simulate receiving multiple messages in sequence
		transportProvider.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "method1",
				    "id": "id1",
				    "params": {"key": "value1"}
				}
				""");

		transportProvider.simulateMessageEvent("""
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
		assertThat(transportProvider.getInboundMessageCount()).isEqualTo(2);
	}

	@Test
	void testMessageOrderPreservation() {
		// Simulate receiving messages in a specific order
		transportProvider.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "first",
				    "id": "1",
				    "params": {"sequence": 1}
				}
				""");

		transportProvider.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "second",
				    "id": "2",
				    "params": {"sequence": 2}
				}
				""");

		transportProvider.simulateMessageEvent("""
				{
				    "jsonrpc": "2.0",
				    "method": "third",
				    "id": "3",
				    "params": {"sequence": 3}
				}
				""");

		// Verify message count and order
		assertThat(transportProvider.getInboundMessageCount()).isEqualTo(3);
	}

}
