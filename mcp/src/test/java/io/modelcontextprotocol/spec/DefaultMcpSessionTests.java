/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.MockMcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for {@link DefaultMcpSession} that verifies its JSON-RPC message handling,
 * request-response correlation, and notification processing.
 *
 * @author Christian Tzolov
 */
class DefaultMcpSessionTests {

	private static final Logger logger = LoggerFactory.getLogger(DefaultMcpSessionTests.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String TEST_METHOD = "test.method";

	private static final String TEST_NOTIFICATION = "test.notification";

	private static final String ECHO_METHOD = "echo";

	private DefaultMcpSession session;

	private MockMcpTransport transport;

	@BeforeEach
	void setUp() {
		transport = new MockMcpTransport();
		session = new DefaultMcpSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: " + params))));
	}

	@AfterEach
	void tearDown() {
		if (session != null) {
			session.close();
		}
	}

	@Test
	void testConstructorWithInvalidArguments() {
		assertThatThrownBy(() -> new DefaultMcpSession(null, transport, Map.of(), Map.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("requstTimeout can not be null");

		assertThatThrownBy(() -> new DefaultMcpSession(TIMEOUT, null, Map.of(), Map.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("transport can not be null");
	}

	TypeReference<String> responseType = new TypeReference<>() {
	};

	@Test
	void testSendRequest() {
		String testParam = "test parameter";
		String responseData = "test response";

		// Create a Mono that will emit the response after the request is sent
		Mono<String> responseMono = session.sendRequest(TEST_METHOD, testParam, responseType);
		// Verify response handling
		StepVerifier.create(responseMono).then(() -> {
			McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			transport.simulateIncomingMessage(
					new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), responseData, null));
		}).consumeNextWith(response -> {
			// Verify the request was sent
			McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessageAsRequest();
			assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCRequest.class);
			McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) sentMessage;
			assertThat(request.method()).isEqualTo(TEST_METHOD);
			assertThat(request.params()).isEqualTo(testParam);
			assertThat(response).isEqualTo(responseData);
		}).verifyComplete();
	}

	@Test
	void testSendRequestWithError() {
		Mono<String> responseMono = session.sendRequest(TEST_METHOD, "test", responseType);

		// Verify error handling
		StepVerifier.create(responseMono).then(() -> {
			McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			// Simulate error response
			McpSchema.JSONRPCResponse.JSONRPCError error = new McpSchema.JSONRPCResponse.JSONRPCError(
					McpSchema.ErrorCodes.METHOD_NOT_FOUND, "Method not found", null);
			transport.simulateIncomingMessage(
					new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null, error));
		}).expectError(McpError.class).verify();
	}

	@Test
	void testRequestTimeout() {
		Mono<String> responseMono = session.sendRequest(TEST_METHOD, "test", responseType);

		// Verify timeout
		StepVerifier.create(responseMono)
			.expectError(java.util.concurrent.TimeoutException.class)
			.verify(TIMEOUT.plusSeconds(1));
	}

	@Test
	void testSendNotification() {
		Map<String, Object> params = Map.of("key", "value");
		Mono<Void> notificationMono = session.sendNotification(TEST_NOTIFICATION, params);

		// Verify notification was sent
		StepVerifier.create(notificationMono).consumeSubscriptionWith(response -> {
			McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
			assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCNotification.class);
			McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) sentMessage;
			assertThat(notification.method()).isEqualTo(TEST_NOTIFICATION);
			assertThat(notification.params()).isEqualTo(params);
		}).verifyComplete();
	}

	@Test
	void testRequestHandling() {
		String echoMessage = "Hello MCP!";
		Map<String, DefaultMcpSession.RequestHandler<?>> requestHandlers = Map.of(ECHO_METHOD,
				params -> Mono.just(params));
		transport = new MockMcpTransport();
		session = new DefaultMcpSession(TIMEOUT, transport, requestHandlers, Map.of());

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, ECHO_METHOD,
				"test-id", echoMessage);
		transport.simulateIncomingMessage(request);

		// Verify response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.result()).isEqualTo(echoMessage);
		assertThat(response.error()).isNull();
	}

	@Test
	void testNotificationHandling() {
		Sinks.One<Object> receivedParams = Sinks.one();

		transport = new MockMcpTransport();
		session = new DefaultMcpSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> receivedParams.tryEmitValue(params))));

		// Simulate incoming notification from the server
		Map<String, Object> notificationParams = Map.of("status", "ready");

		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				TEST_NOTIFICATION, notificationParams);

		transport.simulateIncomingMessage(notification);

		// Verify handler was called
		assertThat(receivedParams.asMono().block(Duration.ofSeconds(1))).isEqualTo(notificationParams);
	}

	@Test
	void testUnknownMethodHandling() {
		// Simulate incoming request for unknown method
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "unknown.method",
				"test-id", null);
		transport.simulateIncomingMessage(request);

		// Verify error response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
	}

	@Test
	void testGracefulShutdown() {
		StepVerifier.create(session.closeGracefully()).verifyComplete();
	}

}
