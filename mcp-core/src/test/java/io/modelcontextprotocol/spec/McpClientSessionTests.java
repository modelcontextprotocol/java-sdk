/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.MockMcpClientTransport;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for {@link McpClientSession} that verifies its JSON-RPC message handling,
 * request-response correlation, and notification processing.
 *
 * @author Christian Tzolov
 */
class McpClientSessionTests {

	private static final Logger logger = LoggerFactory.getLogger(McpClientSessionTests.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String TEST_METHOD = "test.method";

	private static final String TEST_NOTIFICATION = "test.notification";

	private static final String ECHO_METHOD = "echo";

	private McpClientSession session;

	private MockMcpClientTransport transport;

	@BeforeEach
	void setUp() {
		transport = new MockMcpClientTransport();
		session = new McpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))));
	}

	@AfterEach
	void tearDown() {
		if (session != null) {
			session.close();
		}
	}

	TypeRef<String> responseType = new TypeRef<>() {
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
		Map<String, McpClientSession.RequestHandler<?>> requestHandlers = Map.of(ECHO_METHOD,
				params -> Mono.just(params));
		transport = new MockMcpClientTransport();
		session = new McpClientSession(TIMEOUT, transport, requestHandlers, Map.of());

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

		transport = new MockMcpClientTransport();
		session = new McpClientSession(TIMEOUT, transport, Map.of(),
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
	void testRequestHandlerThrowsMcpErrorWithJsonRpcError() {
		// Setup: Create a request handler that throws McpError with custom error code and
		// data
		String testMethod = "test.customError";
		Map<String, Object> errorData = Map.of("customField", "customValue");
		McpClientSession.RequestHandler<?> failingHandler = params -> Mono
			.error(McpError.builder(123).message("Custom error message").data(errorData).build());

		transport = new MockMcpClientTransport();
		session = new McpClientSession(TIMEOUT, transport, Map.of(testMethod, failingHandler), Map.of());

		// Simulate incoming request that will trigger the error
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, testMethod,
				"test-id", null);
		transport.simulateIncomingMessage(request);

		// Verify: The response should contain the custom error from McpError
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(123);
		assertThat(response.error().message()).isEqualTo("Custom error message");
		assertThat(response.error().data()).isEqualTo(errorData);
	}

	@Test
	void testRequestHandlerThrowsGenericException() {
		// Setup: Create a request handler that throws a generic RuntimeException
		String testMethod = "test.genericError";
		RuntimeException exception = new RuntimeException("Something went wrong");
		McpClientSession.RequestHandler<?> failingHandler = params -> Mono.error(exception);

		transport = new MockMcpClientTransport();
		session = new McpClientSession(TIMEOUT, transport, Map.of(testMethod, failingHandler), Map.of());

		// Simulate incoming request that will trigger the error
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, testMethod,
				"test-id", null);
		transport.simulateIncomingMessage(request);

		// Verify: The response should contain INTERNAL_ERROR with aggregated exception
		// messages in data field
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
		assertThat(response.error().message()).isEqualTo("Something went wrong");
		// Verify data field contains aggregated exception messages
		assertThat(response.error().data()).isNotNull();
		assertThat(response.error().data().toString()).contains("RuntimeException");
		assertThat(response.error().data().toString()).contains("Something went wrong");
	}

	@Test
	void testRequestHandlerThrowsExceptionWithCause() {
		// Setup: Create a request handler that throws an exception with a cause chain
		String testMethod = "test.chainedError";
		RuntimeException rootCause = new IllegalArgumentException("Root cause message");
		RuntimeException middleCause = new IllegalStateException("Middle cause message", rootCause);
		RuntimeException topException = new RuntimeException("Top level message", middleCause);
		McpClientSession.RequestHandler<?> failingHandler = params -> Mono.error(topException);

		transport = new MockMcpClientTransport();
		session = new McpClientSession(TIMEOUT, transport, Map.of(testMethod, failingHandler), Map.of());

		// Simulate incoming request that will trigger the error
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, testMethod,
				"test-id", null);
		transport.simulateIncomingMessage(request);

		// Verify: The response should contain INTERNAL_ERROR with full exception chain
		// in data field
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
		assertThat(response.error().message()).isEqualTo("Top level message");
		// Verify data field contains the full exception chain
		String dataString = response.error().data().toString();
		assertThat(dataString).contains("RuntimeException");
		assertThat(dataString).contains("Top level message");
		assertThat(dataString).contains("IllegalStateException");
		assertThat(dataString).contains("Middle cause message");
		assertThat(dataString).contains("IllegalArgumentException");
		assertThat(dataString).contains("Root cause message");
	}

	@Test
	void testRequestHandlerThrowsMcpErrorWithoutJsonRpcError() {
		// Setup: Create a request handler that throws deprecated McpError without
		// JSONRPCError
		String testMethod = "test.deprecatedError";
		@SuppressWarnings("deprecation")
		McpError deprecatedError = new McpError("Deprecated error format");
		McpClientSession.RequestHandler<?> failingHandler = params -> Mono.error(deprecatedError);

		transport = new MockMcpClientTransport();
		session = new McpClientSession(TIMEOUT, transport, Map.of(testMethod, failingHandler), Map.of());

		// Simulate incoming request that will trigger the error
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, testMethod,
				"test-id", null);
		transport.simulateIncomingMessage(request);

		// Verify: The response should create a new INTERNAL_ERROR with aggregated
		// messages
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
		assertThat(response.error().message()).isEqualTo("Deprecated error format");
		// Verify data field contains aggregated exception messages
		assertThat(response.error().data()).isNotNull();
		assertThat(response.error().data().toString()).contains("McpError");
		assertThat(response.error().data().toString()).contains("Deprecated error format");
	}

	@Test
	void testRequestHandlerThrowsResourceNotFoundError() {
		// Setup: Create a request handler that throws RESOURCE_NOT_FOUND error
		String testMethod = "test.resourceError";
		String resourceUri = "file:///missing/resource.txt";
		McpClientSession.RequestHandler<?> failingHandler = params -> Mono
			.error(McpError.RESOURCE_NOT_FOUND.apply(resourceUri));

		transport = new MockMcpClientTransport();
		session = new McpClientSession(TIMEOUT, transport, Map.of(testMethod, failingHandler), Map.of());

		// Simulate incoming request that will trigger the error
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, testMethod,
				"test-id", null);
		transport.simulateIncomingMessage(request);

		// Verify: The response should preserve the RESOURCE_NOT_FOUND error code and
		// data
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND);
		assertThat(response.error().message()).isEqualTo("Resource not found");
		assertThat(response.error().data()).isEqualTo(Map.of("uri", resourceUri));
	}

	@Test
	void testGracefulShutdown() {
		StepVerifier.create(session.closeGracefully()).verifyComplete();
	}

}
