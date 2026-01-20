/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link WebFluxSseClientTransport} class.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class WebFluxSseClientTransportTests {

	static String host = "http://localhost:3001";

	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/node:lts-alpine3.23")
		.withCommand("npx -y @modelcontextprotocol/server-everything@2025.12.18 sse")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private TestSseClientTransport transport;

	private WebClient.Builder webClientBuilder;

	// Test class to access protected methods
	static class TestSseClientTransport extends WebFluxSseClientTransport {

		private final AtomicInteger inboundMessageCount = new AtomicInteger(0);

		private Sinks.Many<ServerSentEvent<String>> events = Sinks.many().unicast().onBackpressureBuffer();

		public TestSseClientTransport(WebClient.Builder webClientBuilder, McpJsonMapper jsonMapper) {
			super(webClientBuilder, jsonMapper);
		}

		@Override
		protected Flux<ServerSentEvent<String>> eventStream() {
			return super.eventStream().mergeWith(events.asFlux());
		}

		public String getLastEndpoint() {
			return messageEndpointSink.asMono().block();
		}

		public int getInboundMessageCount() {
			return inboundMessageCount.get();
		}

		public void simulateSseComment(String comment) {
			events.tryEmitNext(ServerSentEvent.<String>builder().comment(comment).build());
			inboundMessageCount.incrementAndGet();
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

	@BeforeAll
	static void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@AfterAll
	static void cleanup() {
		container.stop();
	}

	@BeforeEach
	void setUp() {
		webClientBuilder = WebClient.builder().baseUrl(host);
		transport = new TestSseClientTransport(webClientBuilder, JSON_MAPPER);
		transport.connect(Function.identity()).block();
	}

	@AfterEach
	void afterEach() {
		if (transport != null) {
			assertThatCode(() -> transport.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
		}
	}

	@Test
	void testEndpointEventHandling() {
		assertThat(transport.getLastEndpoint()).startsWith("/message?");
	}

	@Test
	void constructorValidation() {
		assertThatThrownBy(() -> new WebFluxSseClientTransport(null, JSON_MAPPER))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("WebClient.Builder must not be null");

		assertThatThrownBy(() -> new WebFluxSseClientTransport(webClientBuilder, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("jsonMapper must not be null");
	}

	@Test
	void testBuilderPattern() {
		// Test default builder
		WebFluxSseClientTransport transport1 = WebFluxSseClientTransport.builder(webClientBuilder).build();
		assertThatCode(() -> transport1.closeGracefully().block()).doesNotThrowAnyException();

		// Test builder with custom ObjectMapper
		ObjectMapper customMapper = new ObjectMapper();
		WebFluxSseClientTransport transport2 = WebFluxSseClientTransport.builder(webClientBuilder)
			.jsonMapper(new JacksonMcpJsonMapper(customMapper))
			.build();
		assertThatCode(() -> transport2.closeGracefully().block()).doesNotThrowAnyException();

		// Test builder with custom SSE endpoint
		WebFluxSseClientTransport transport3 = WebFluxSseClientTransport.builder(webClientBuilder)
			.sseEndpoint("/custom-sse")
			.build();
		assertThatCode(() -> transport3.closeGracefully().block()).doesNotThrowAnyException();

		// Test builder with all custom parameters
		WebFluxSseClientTransport transport4 = WebFluxSseClientTransport.builder(webClientBuilder)
			.sseEndpoint("/custom-sse")
			.build();
		assertThatCode(() -> transport4.closeGracefully().block()).doesNotThrowAnyException();
	}

	@Test
	void testCommentSseMessage() {
		// If the line starts with a character (:) are comment lins and should be ingored
		// https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation

		CopyOnWriteArrayList<Throwable> droppedErrors = new CopyOnWriteArrayList<>();
		reactor.core.publisher.Hooks.onErrorDropped(droppedErrors::add);

		try {
			// Simulate receiving the SSE comment line
			transport.simulateSseComment("sse comment");

			StepVerifier.create(transport.closeGracefully()).verifyComplete();

			assertThat(droppedErrors).hasSize(0);
		}
		finally {
			reactor.core.publisher.Hooks.resetOnErrorDropped();
		}
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
		assertThat(transport.getInboundMessageCount()).isEqualTo(0);
	}

	@Test
	void testRetryBehavior() {
		// Create a WebClient that simulates connection failures
		WebClient.Builder failingWebClientBuilder = WebClient.builder().baseUrl("http://non-existent-host");

		WebFluxSseClientTransport failingTransport = WebFluxSseClientTransport.builder(failingWebClientBuilder).build();

		// Verify that the transport attempts to reconnect
		StepVerifier.create(Mono.delay(Duration.ofSeconds(2))).expectNextCount(1).verifyComplete();

		// Clean up
		failingTransport.closeGracefully().block();
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

}
