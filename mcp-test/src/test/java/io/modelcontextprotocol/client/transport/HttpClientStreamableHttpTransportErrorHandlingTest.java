/*
 * Copyright 2025-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientAuthorizationErrorHandler;
import io.modelcontextprotocol.common.McpTransportContext;
import org.reactivestreams.Publisher;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests for error handling changes in HttpClientStreamableHttpTransport. Specifically
 * tests the distinction between session-related errors and general transport errors for
 * 404 and 400 status codes.
 *
 * @author Christian Tzolov
 * @author Daniel Garnier-Moiroux
 */
@Timeout(15)
public class HttpClientStreamableHttpTransportErrorHandlingTest {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String HOST = "http://localhost:" + PORT;

	private HttpServer server;

	private final AtomicInteger serverResponseStatus = new AtomicInteger(200);

	private final AtomicInteger serverSseResponseStatus = new AtomicInteger(200);

	private final AtomicReference<String> currentServerSessionId = new AtomicReference<>(null);

	private final AtomicReference<String> lastReceivedSessionId = new AtomicReference<>(null);

	private final AtomicInteger processedMessagesCount = new AtomicInteger(0);

	private final AtomicInteger processedSseConnectCount = new AtomicInteger(0);

	private McpClientTransport transport;

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
				processedMessagesCount.incrementAndGet();
			}
			else if ("GET".equals(httpExchange.getRequestMethod())) {
				int status = serverSseResponseStatus.get();
				if (status == 200) {
					httpExchange.getResponseHeaders().set("Content-Type", "text/event-stream");
					httpExchange.sendResponseHeaders(200, 0);
					String sseData = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"test\",\"params\":{}}\n\n";
					httpExchange.getResponseBody().write(sseData.getBytes());
				}
				else {
					httpExchange.sendResponseHeaders(status, 0);
				}
				processedSseConnectCount.incrementAndGet();
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
		StepVerifier.create(transport.closeGracefully()).verifyComplete();
	}

	/**
	 * Test that 404 response WITHOUT session ID throws McpTransportException (not
	 * SessionNotFoundException)
	 */
	@Test
	void test404WithoutSessionId() {
		serverResponseStatus.set(404);
		currentServerSessionId.set(null); // No session ID in response

		var testMessage = createTestRequestMessage();

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
		serverResponseStatus.set(200);
		currentServerSessionId.set("test-session-123");

		// Set up exception handler to verify session invalidation
		@SuppressWarnings("unchecked")
		Consumer<Throwable> exceptionHandler = mock(Consumer.class);
		transport.setExceptionHandler(exceptionHandler);

		// Connect with handler
		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

		// Send initial message to establish session
		var testMessage = createTestRequestMessage();
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// The session should now be established, next request will include session ID
		// Now return 404 for next request
		serverResponseStatus.set(404);

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
		serverResponseStatus.set(400);
		currentServerSessionId.set(null); // No session ID

		var testMessage = createTestRequestMessage();

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
		serverResponseStatus.set(200);
		currentServerSessionId.set("test-session-456");

		// Set up exception handler
		@SuppressWarnings("unchecked")
		Consumer<Throwable> exceptionHandler = mock(Consumer.class);
		transport.setExceptionHandler(exceptionHandler);

		// Connect with handler
		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

		// Send initial message to establish session
		var testMessage = createTestRequestMessage();
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// The session should now be established, next request will include session ID
		// Now return 400 for next request (simulating unknown session ID)
		serverResponseStatus.set(400);

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
		serverResponseStatus.set(200);
		currentServerSessionId.set("session-1");

		// Send initial message to establish session
		var testMessage = createTestRequestMessage();
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(lastReceivedSessionId.get()).isNull();

		// The session should now be established
		// Simulate session loss - return 404
		serverResponseStatus.set(404);

		// This should fail with SessionNotFoundException
		StepVerifier.create(transport.sendMessage(testMessage))
			.expectError(McpTransportSessionNotFoundException.class)
			.verify();

		// Now server is back with new session
		serverResponseStatus.set(200);
		currentServerSessionId.set("session-2");
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
				int status = serverResponseStatus.get();

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
				String responseSessionId = currentServerSessionId.get();
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
		serverResponseStatus.set(200);
		currentServerSessionId.set("sse-session-1");

		var transport = HttpClientStreamableHttpTransport.builder(HOST)
			.endpoint("/mcp-sse")
			.openConnectionOnStartup(true) // This will trigger GET request on connect
			.build();

		// First connect successfully
		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

		// Send message to establish session
		var testMessage = createTestRequestMessage();
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Now simulate server returning 404 on reconnect
		serverResponseStatus.set(404);

		// This should trigger reconnect which will fail
		// The error should be handled internally and passed to exception handler

		StepVerifier.create(transport.closeGracefully()).verifyComplete();
	}

	@Nested
	class AuthorizationError {

		@Nested
		class SendMessage {

			@ParameterizedTest
			@ValueSource(ints = { 401, 403 })
			void invokeHandler(int httpStatus) {
				serverResponseStatus.set(httpStatus);

				AtomicReference<HttpResponse.ResponseInfo> capturedResponseInfo = new AtomicReference<>();
				AtomicReference<McpTransportContext> capturedContext = new AtomicReference<>();

				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.authorizationErrorHandler((responseInfo, context) -> {
						capturedResponseInfo.set(responseInfo);
						capturedContext.set(context);
						return Mono.just(false);
					})
					.build();

				StepVerifier.create(authTransport.sendMessage(createTestRequestMessage()))
					.expectErrorMatches(authorizationError(httpStatus))
					.verify();
				assertThat(processedMessagesCount.get()).isEqualTo(1);
				assertThat(capturedResponseInfo.get()).isNotNull();
				assertThat(capturedResponseInfo.get().statusCode()).isEqualTo(httpStatus);
				assertThat(capturedContext.get()).isNotNull();

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void defaultHandler() {
				serverResponseStatus.set(401);

				var authTransport = HttpClientStreamableHttpTransport.builder(HOST).build();

				StepVerifier.create(authTransport.sendMessage(createTestRequestMessage()))
					.expectErrorMatches(authorizationError(401))
					.verify();
				assertThat(processedMessagesCount.get()).isEqualTo(1);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void retry() {
				serverResponseStatus.set(401);
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.authorizationErrorHandler((responseInfo, context) -> {
						serverResponseStatus.set(200);
						return Mono.just(true);
					})
					.build();
				StepVerifier.create(authTransport.sendMessage(createTestRequestMessage())).verifyComplete();
				// initial request + retry
				assertThat(processedMessagesCount.get()).isEqualTo(2);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void retryAtMostOnce() {
				serverResponseStatus.set(401);
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.authorizationErrorHandler((responseInfo, context) -> Mono.just(true))
					.build();
				StepVerifier.create(authTransport.sendMessage(createTestRequestMessage()))
					.expectErrorMatches(authorizationError(401))
					.verify();
				// initial request + 1 retry (maxRetries default is 1)
				assertThat(processedMessagesCount.get()).isEqualTo(2);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void customMaxRetries() {
				serverResponseStatus.set(401);
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.authorizationErrorHandler(new McpHttpClientAuthorizationErrorHandler() {
						@Override
						public Publisher<Boolean> handle(HttpResponse.ResponseInfo responseInfo,
								McpTransportContext context) {
							return Mono.just(true);
						}

						@Override
						public int maxRetries() {
							return 3;
						}
					})
					.build();
				StepVerifier.create(authTransport.sendMessage(createTestRequestMessage()))
					.expectErrorMatches(authorizationError(401))
					.verify();
				// initial request + 3 retries
				assertThat(processedMessagesCount.get()).isEqualTo(4);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void noRetry() {
				serverResponseStatus.set(401);

				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.authorizationErrorHandler((responseInfo, context) -> Mono.just(false))
					.build();

				StepVerifier.create(authTransport.sendMessage(createTestRequestMessage()))
					.expectErrorMatches(authorizationError(401))
					.verify();
				assertThat(processedMessagesCount.get()).isEqualTo(1);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void propagateHandlerError() {
				serverResponseStatus.set(401);
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.authorizationErrorHandler(
							(responseInfo, context) -> Mono.error(new IllegalStateException("handler error")))
					.build();

				StepVerifier.create(authTransport.sendMessage(createTestRequestMessage()))
					.expectErrorMatches(throwable -> throwable instanceof IllegalStateException
							&& throwable.getMessage().equals("handler error"))
					.verify();

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void emptyHandler() {
				serverResponseStatus.set(401);
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.authorizationErrorHandler((responseInfo, context) -> Mono.empty())
					.build();

				StepVerifier.create(authTransport.sendMessage(createTestRequestMessage()))
					.expectErrorMatches(authorizationError(401))
					.verify();

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

		}

		@Nested
		class Connect {

			@ParameterizedTest
			@ValueSource(ints = { 401, 403 })
			void invokeHandler(int httpStatus) {
				serverSseResponseStatus.set(httpStatus);
				@SuppressWarnings("unchecked")
				AtomicReference<Throwable> capturedException = new AtomicReference<>();

				AtomicReference<HttpResponse.ResponseInfo> capturedResponseInfo = new AtomicReference<>();
				AtomicReference<McpTransportContext> capturedContext = new AtomicReference<>();

				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.authorizationErrorHandler((responseInfo, context) -> {
						capturedResponseInfo.set(responseInfo);
						capturedContext.set(context);
						return Mono.just(false);
					})
					.openConnectionOnStartup(true)
					.build();
				authTransport.setExceptionHandler(capturedException::set);

				var messages = new ArrayList<McpSchema.JSONRPCMessage>();
				StepVerifier.create(authTransport.connect(msg -> msg.doOnNext(messages::add))).verifyComplete();
				Awaitility.await()
					.atMost(Duration.ofSeconds(1))
					.untilAsserted(() -> assertThat(processedSseConnectCount.get()).isEqualTo(1));
				assertThat(messages).isEmpty();
				assertThat(capturedResponseInfo.get()).isNotNull();
				assertThat(capturedResponseInfo.get().statusCode()).isEqualTo(httpStatus);
				assertThat(capturedContext.get()).isNotNull();
				assertThat(capturedException.get()).hasMessage("Authorization error connecting to SSE stream")
					.asInstanceOf(type(McpHttpClientTransportAuthorizationException.class))
					.extracting(McpHttpClientTransportAuthorizationException::getResponseInfo)
					.extracting(HttpResponse.ResponseInfo::statusCode)
					.isEqualTo(httpStatus);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void defaultHandler() {
				serverSseResponseStatus.set(401);
				AtomicReference<Throwable> capturedException = new AtomicReference<>();
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.openConnectionOnStartup(true)
					.build();
				authTransport.setExceptionHandler(capturedException::set);

				StepVerifier.create(authTransport.connect(msg -> msg)).verifyComplete();
				Awaitility.await()
					.atMost(Duration.ofSeconds(1))
					.untilAsserted(() -> assertThat(processedSseConnectCount.get()).isEqualTo(1));
				assertThat(capturedException.get()).isInstanceOf(McpHttpClientTransportAuthorizationException.class);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void retry() {
				serverSseResponseStatus.set(401);
				AtomicReference<Throwable> capturedException = new AtomicReference<>();
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.openConnectionOnStartup(true)
					.authorizationErrorHandler((responseInfo, context) -> {
						serverSseResponseStatus.set(200);
						return Mono.just(true);
					})
					.build();
				authTransport.setExceptionHandler(capturedException::set);

				var messages = new ArrayList<McpSchema.JSONRPCMessage>();
				var messageHandlerClosed = new AtomicBoolean(false);
				StepVerifier
					.create(authTransport
						.connect(msg -> msg.doOnNext(messages::add).doFinally(s -> messageHandlerClosed.set(true))))
					.verifyComplete();
				Awaitility.await()
					.atMost(Duration.ofSeconds(1))
					.untilAsserted(() -> assertThat(messageHandlerClosed).isTrue());
				assertThat(processedSseConnectCount.get()).isEqualTo(2);
				assertThat(messages).hasSize(1);
				assertThat(capturedException.get()).isNull();
				assertThat(messageHandlerClosed.get()).isTrue();

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void retryAtMostOnce() {
				serverSseResponseStatus.set(401);
				AtomicReference<Throwable> capturedException = new AtomicReference<>();
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.openConnectionOnStartup(true)
					.authorizationErrorHandler((responseInfo, context) -> {
						return Mono.just(true);
					})
					.build();
				authTransport.setExceptionHandler(capturedException::set);

				var messages = new ArrayList<McpSchema.JSONRPCMessage>();
				StepVerifier.create(authTransport.connect(msg -> msg.doOnNext(messages::add))).verifyComplete();
				Awaitility.await()
					.atMost(Duration.ofSeconds(1))
					.untilAsserted(() -> assertThat(capturedException.get()).isNotNull());
				// initial request + 1 retry (maxRetries default is 1)
				assertThat(processedSseConnectCount.get()).isEqualTo(2);
				assertThat(messages).isEmpty();
				assertThat(capturedException.get()).isInstanceOf(McpHttpClientTransportAuthorizationException.class);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void customMaxRetries() {
				serverSseResponseStatus.set(401);
				AtomicReference<Throwable> capturedException = new AtomicReference<>();
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.openConnectionOnStartup(true)
					.authorizationErrorHandler(new McpHttpClientAuthorizationErrorHandler() {
						@Override
						public Publisher<Boolean> handle(HttpResponse.ResponseInfo responseInfo,
								McpTransportContext context) {
							return Mono.just(true);
						}

						@Override
						public int maxRetries() {
							return 3;
						}
					})
					.build();
				authTransport.setExceptionHandler(capturedException::set);

				var messages = new ArrayList<McpSchema.JSONRPCMessage>();
				StepVerifier.create(authTransport.connect(msg -> msg.doOnNext(messages::add))).verifyComplete();
				Awaitility.await()
					.atMost(Duration.ofSeconds(1))
					.untilAsserted(() -> assertThat(capturedException.get()).isNotNull());
				// initial request + 3 retries
				assertThat(processedSseConnectCount.get()).isEqualTo(4);
				assertThat(messages).isEmpty();
				assertThat(capturedException.get()).isInstanceOf(McpHttpClientTransportAuthorizationException.class);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void noRetry() {
				serverSseResponseStatus.set(401);
				AtomicReference<Throwable> capturedException = new AtomicReference<>();
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.openConnectionOnStartup(true)
					.authorizationErrorHandler((responseInfo, context) -> {
						// if there was a retry, the request would succeed.
						serverSseResponseStatus.set(200);
						return Mono.just(false);
					})
					.build();
				authTransport.setExceptionHandler(capturedException::set);

				var messages = new ArrayList<McpSchema.JSONRPCMessage>();
				StepVerifier.create(authTransport.connect(msg -> msg.doOnNext(messages::add))).verifyComplete();
				Awaitility.await()
					.atMost(Duration.ofSeconds(1))
					.untilAsserted(() -> assertThat(processedSseConnectCount.get()).isEqualTo(1));
				assertThat(messages).isEmpty();
				assertThat(capturedException.get()).isInstanceOf(McpHttpClientTransportAuthorizationException.class);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void emptyHandler() {
				serverSseResponseStatus.set(401);
				AtomicReference<Throwable> capturedException = new AtomicReference<>();
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.openConnectionOnStartup(true)
					.authorizationErrorHandler((responseInfo, context) -> Mono.empty())
					.build();
				authTransport.setExceptionHandler(capturedException::set);

				var messages = new ArrayList<McpSchema.JSONRPCMessage>();
				StepVerifier.create(authTransport.connect(msg -> msg.doOnNext(messages::add))).verifyComplete();
				Awaitility.await()
					.atMost(Duration.ofSeconds(1))
					.untilAsserted(() -> assertThat(processedSseConnectCount.get()).isEqualTo(1));
				assertThat(messages).isEmpty();
				assertThat(capturedException.get()).isInstanceOf(McpHttpClientTransportAuthorizationException.class);

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

			@Test
			void propagateHandlerError() {
				serverSseResponseStatus.set(401);
				AtomicReference<Throwable> capturedException = new AtomicReference<>();
				var authTransport = HttpClientStreamableHttpTransport.builder(HOST)
					.openConnectionOnStartup(true)
					.authorizationErrorHandler(
							(responseInfo, context) -> Mono.error(new IllegalStateException("handler error")))
					.build();
				authTransport.setExceptionHandler(capturedException::set);

				var messages = new ArrayList<McpSchema.JSONRPCMessage>();
				StepVerifier.create(authTransport.connect(msg -> msg.doOnNext(messages::add))).verifyComplete();
				Awaitility.await()
					.atMost(Duration.ofSeconds(1))
					.untilAsserted(() -> assertThat(processedSseConnectCount.get()).isEqualTo(1));
				assertThat(messages).isEmpty();
				assertThat(capturedException.get()).isInstanceOf(IllegalStateException.class)
					.hasMessage("handler error");

				StepVerifier.create(authTransport.closeGracefully()).verifyComplete();
			}

		}

		private static Predicate<Throwable> authorizationError(int httpStatus) {
			return throwable -> throwable instanceof McpHttpClientTransportAuthorizationException
					&& throwable.getMessage().contains("Authorization error")
					&& ((McpHttpClientTransportAuthorizationException) throwable).getResponseInfo()
						.statusCode() == httpStatus;
		}

	}

	private McpSchema.JSONRPCRequest createTestRequestMessage() {
		var initializeRequest = new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_03_26,
				McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("Test Client", "1.0.0"));
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE, "test-id",
				initializeRequest);
	}

}
