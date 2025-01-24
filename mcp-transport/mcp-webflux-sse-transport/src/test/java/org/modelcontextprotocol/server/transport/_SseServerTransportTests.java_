package org.springframework.ai.mcp.server.transport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.ai.mcp.spec.McpSchema;
import org.springframework.ai.mcp.spec.McpSchema.JSONRPCRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link SseServerTransport} class.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class SseServerTransportTests {

	private static final Logger logger = LoggerFactory.getLogger(SseServerTransportTests.class);

	private ObjectMapper objectMapper;

	private String messageEndpoint;

	private SseServerTransport transport;

	private WebTestClient webTestClient;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		messageEndpoint = "/message";
		transport = new SseServerTransport(objectMapper, messageEndpoint);
		webTestClient = WebTestClient.bindToRouterFunction(transport.getRouterFunction()).build();
	}

	@Test
	void constructorValidation() {
		assertThatThrownBy(() -> new SseServerTransport(null, "/message")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ObjectMapper must not be null");

		assertThatThrownBy(() -> new SseServerTransport(new ObjectMapper(), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Message endpoint must not be null");
	}

	@Test
	void testSseConnectionEstablishment() {
		List<ServerSentEvent<String>> events = new ArrayList<>();

		webTestClient.get()
			.uri("/sse")
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
			.returnResult(String.class)
			.getResponseBody()
			.map(data -> ServerSentEvent.<String>builder().data(data).build())
			.take(1) // Take only the initial endpoint event
			.subscribe(events::add);

		// Wait a bit for the event to be received
		StepVerifier.create(Mono.delay(Duration.ofMillis(500))).expectNextCount(1).verifyComplete();

		assertThat(events).hasSize(1);
		assertThat(events.get(0).data()).isEqualTo(messageEndpoint);
	}

	@Test
	void testMessageHandling() {
		AtomicReference<McpSchema.JSONRPCMessage> receivedMessage = new AtomicReference<>();

		// Set up message handler
		transport.connect(message -> {
			message.doOnNext(receivedMessage::set).subscribe();
			return Mono.empty();
		}).block();

		// Create a test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));

		// Send message to endpoint
		webTestClient.post()
			.uri(messageEndpoint)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(testMessage)
			.exchange()
			.expectStatus()
			.isOk();

		// Verify the message was received and processed
		assertThat(receivedMessage.get()).isNotNull();
		McpSchema.JSONRPCRequest receivedRequest = (McpSchema.JSONRPCRequest) receivedMessage.get();
		assertThat(receivedRequest.id()).isEqualTo(testMessage.id());
		assertThat(receivedRequest.method()).isEqualTo(testMessage.method());
	}

	@Test
	@Disabled("Flaky test")
	void testBroadcastMessage() {
		// Create test clients
		int clientCount = 3;
		CountDownLatch connectLatch = new CountDownLatch(clientCount);
		CountDownLatch messageLatch = new CountDownLatch(clientCount);

		List<List<ServerSentEvent<String>>> allReceivedEvents = new ArrayList<>();
		List<Disposable> subscriptions = new ArrayList<>();

		// Connect clients
		for (int i = 0; i < clientCount; i++) {
			List<ServerSentEvent<String>> clientEvents = new ArrayList<>();
			allReceivedEvents.add(clientEvents);

			Flux<ServerSentEvent<String>> eventStream = webTestClient.get()
				.uri("/sse")
				.accept(MediaType.TEXT_EVENT_STREAM)
				.exchange()
				.expectStatus()
				.isOk()
				.returnResult(String.class)
				.getResponseBody()
				.map(data -> ServerSentEvent.<String>builder().data(data).build());

			Disposable subscription = eventStream.doOnNext(event -> {
				clientEvents.add(event);
				if (event.event() != null && event.event().equals(SseServerTransport.ENDPOINT_EVENT_TYPE)) {
					connectLatch.countDown();
				}
				else if (event.event() != null && event.event().equals(SseServerTransport.MESSAGE_EVENT_TYPE)) {
					messageLatch.countDown();
				}
			}).subscribe();

			subscriptions.add(subscription);
		}

		// Wait for all clients to connect
		try {
			assertThat(connectLatch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		// Verify initial connections
		for (List<ServerSentEvent<String>> events : allReceivedEvents) {
			assertThat(events).hasSize(1);
			assertThat(events.get(0).data()).isEqualTo(messageEndpoint);
		}

		// Give clients time to fully establish their subscriptions
		logger.debug("Waiting for subscriptions to stabilize...");
		try {
			Thread.sleep(1000);
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		logger.debug("Sending broadcast message to {} clients", clientCount);

		// Send broadcast message
		JSONRPCRequest broadcastMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "broadcast", "broadcast-id",
				Map.of("message", "Hello all!"));

		// Send the message
		transport.sendMessage(broadcastMessage).block(Duration.ofSeconds(5));

		// Wait for all clients to receive the broadcast
		try {
			assertThat(messageLatch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		// Verify each client received both messages
		for (List<ServerSentEvent<String>> events : allReceivedEvents) {
			assertThat(events).hasSize(2);
			assertThat(events.get(0).data()).isEqualTo(messageEndpoint);
			assertThat(events.get(1).data()).contains("broadcast-id");
		}

		// Cleanup
		subscriptions.forEach(Disposable::dispose);
	}

	@Test
	void testGracefulShutdown() {
		// Connect a client
		Flux<ServerSentEvent<String>> eventStream = webTestClient.get()
			.uri("/sse")
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus()
			.isOk()
			.returnResult(String.class)
			.getResponseBody()
			.map(data -> ServerSentEvent.<String>builder().data(data).build());

		List<ServerSentEvent<String>> receivedEvents = new ArrayList<>();
		eventStream.subscribe(receivedEvents::add);

		// Wait for connection
		StepVerifier.create(Mono.delay(Duration.ofMillis(500))).expectNextCount(1).verifyComplete();

		// Initiate shutdown
		transport.closeGracefully().block(Duration.ofSeconds(5));

		// Verify server rejects new connections with timeout
		webTestClient.get()
			.uri("/sse")
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody(String.class)
			.isEqualTo("Server is shutting down");

		// Verify server rejects new messages with timeout
		webTestClient.post()
			.uri(messageEndpoint)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
					{
					    "jsonrpc": "2.0",
					    "method": "test",
					    "id": "1",
					    "params": {}
					}
					""")
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody(String.class)
			.isEqualTo("Server is shutting down");
	}

	@Test
	void testInvalidMessageHandling() {
		// Test invalid JSON
		webTestClient.post()
			.uri(messageEndpoint)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("invalid json")
			.exchange()
			.expectStatus()
			.isBadRequest();

		// Test invalid message format
		webTestClient.post().uri(messageEndpoint).contentType(MediaType.APPLICATION_JSON).bodyValue("""
				{
				    "invalid": "message"
				}
				""").exchange().expectStatus().isBadRequest();
	}

}
