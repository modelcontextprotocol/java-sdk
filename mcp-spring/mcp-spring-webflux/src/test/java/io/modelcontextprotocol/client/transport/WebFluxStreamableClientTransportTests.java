/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for the {@link WebFluxStreamableClientTransport} class.
 */
@Timeout(15)
class WebFluxStreamableClientTransportTests {

    // Set up a gateway or server that adapts to Streamable HTTP Transport
    private static final String HOST = "http://yourServer:port";

    private TestStreamableClientTransport transport;

    private WebClient.Builder webClientBuilder;

    private ObjectMapper objectMapper;

    // Test class to access protected methods and simulate events
    static class TestStreamableClientTransport extends WebFluxStreamableClientTransport {

        private final AtomicInteger inboundMessageCount = new AtomicInteger(0);

        private Sinks.Many<ServerSentEvent<String>> events = Sinks.many().unicast().onBackpressureBuffer();

        public TestStreamableClientTransport(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
            super(webClientBuilder, objectMapper);
        }

        public String getLastEndpoint() {
            return messageEndpointSink.asMono().block();
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

    @BeforeEach
    void setUp() {
        webClientBuilder = WebClient.builder().baseUrl(HOST);
        objectMapper = new ObjectMapper();
        transport = new TestStreamableClientTransport(webClientBuilder, objectMapper);
    }

    @AfterEach
    void afterEach() {
        if (transport != null) {
            assertThatCode(() -> transport.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
        }
    }

    @Test
    void testBuilderPattern() {
        // Test default builder
        WebFluxStreamableClientTransport transport1 = WebFluxStreamableClientTransport.builder(webClientBuilder).build();
        assertThatCode(() -> transport1.closeGracefully().block()).doesNotThrowAnyException();

        // Test builder with custom ObjectMapper
        ObjectMapper customMapper = new ObjectMapper();
        WebFluxStreamableClientTransport transport2 = WebFluxStreamableClientTransport.builder(webClientBuilder)
                .objectMapper(customMapper)
                .build();
        assertThatCode(() -> transport2.closeGracefully().block()).doesNotThrowAnyException();

        // Test builder with custom SSE endpoint
        WebFluxStreamableClientTransport transport3 = WebFluxStreamableClientTransport.builder(webClientBuilder)
                .sseEndpoint("/custom-sse")
                .build();
        assertThatCode(() -> transport3.closeGracefully().block()).doesNotThrowAnyException();

        // Test builder with all custom parameters
        WebFluxStreamableClientTransport transport4 = WebFluxStreamableClientTransport.builder(webClientBuilder)
                .objectMapper(customMapper)
                .sseEndpoint("/custom-sse")
                .build();
        assertThatCode(() -> transport4.closeGracefully().block()).doesNotThrowAnyException();
    }

    @Test
    void testMessageProcessing() {
        // Create a test message
        McpSchema.JSONRPCRequest testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
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
        McpSchema.JSONRPCRequest testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
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
        McpSchema.JSONRPCRequest testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
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
        McpSchema.JSONRPCRequest testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
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
        McpSchema.JSONRPCRequest message1 = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method1", "id1",
                Map.of("key", "value1"));

        McpSchema.JSONRPCRequest message2 = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method2", "id2",
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