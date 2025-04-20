/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link StdioServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Disabled
class StdioServerTransportProviderTests {

	private final PrintStream originalOut = System.out;

	private final PrintStream originalErr = System.err;

	private ByteArrayOutputStream testErr;

	private PrintStream testOutPrintStream;

	private StdioServerTransportProvider transportProvider;

	private ObjectMapper objectMapper;

	private McpServerSession.Factory sessionFactory;

	private McpServerSession mockSession;

	@BeforeEach
	void setUp() {
		testErr = new ByteArrayOutputStream();

		testOutPrintStream = new PrintStream(testErr, true);
		System.setOut(testOutPrintStream);
		System.setErr(testOutPrintStream);

		objectMapper = new ObjectMapper();

		// Create mocks for session factory and session
		mockSession = mock(McpServerSession.class);
		sessionFactory = mock(McpServerSession.Factory.class);

		// Configure mock behavior
		when(sessionFactory.create(any(), any(McpServerTransport.class))).thenReturn(mockSession);
		when(mockSession.closeGracefully()).thenReturn(Mono.empty());
		when(mockSession.sendNotification(any(), any())).thenReturn(Mono.empty());

		transportProvider = new StdioServerTransportProvider(objectMapper, System.in, testOutPrintStream);
	}

	@AfterEach
	void tearDown() {
		if (transportProvider != null) {
			transportProvider.closeGracefully().block();
		}
		if (testOutPrintStream != null) {
			testOutPrintStream.close();
		}
		System.setOut(originalOut);
		System.setErr(originalErr);
	}

	@Test
	void shouldCreateSessionWhenSessionFactoryIsSet() {
		// Set session factory
		transportProvider.setSessionFactory(sessionFactory);

		// Verify session was created with a transport
		assertThat(testErr.toString()).doesNotContain("Error");
	}

	@Test
	void shouldHandleIncomingMessages() throws Exception {

		String jsonMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"params\":{},\"id\":1}\n";
		InputStream stream = new ByteArrayInputStream(jsonMessage.getBytes(StandardCharsets.UTF_8));

		transportProvider = new StdioServerTransportProvider(objectMapper, stream, System.out);
		// Set up a real session to capture the message
		AtomicReference<McpSchema.JSONRPCMessage> capturedMessage = new AtomicReference<>();
		CountDownLatch messageLatch = new CountDownLatch(1);

		McpServerSession.Factory realSessionFactory = (id, transport) -> {
			McpServerSession session = mock(McpServerSession.class);
			when(session.handle(any())).thenAnswer(invocation -> {
				capturedMessage.set(invocation.getArgument(0));
				messageLatch.countDown();
				return Mono.empty();
			});
			when(session.closeGracefully()).thenReturn(Mono.empty());
			return session;
		};

		// Set session factory
		transportProvider.setSessionFactory(realSessionFactory);

		// Wait for the message to be processed using the latch
		StepVerifier.create(Mono.fromCallable(() -> messageLatch.await(100, TimeUnit.SECONDS)).flatMap(success -> {
			if (!success) {
				return Mono.error(new AssertionError("Timeout waiting for message processing"));
			}
			return Mono.just(capturedMessage.get());
		})).assertNext(message -> {
			assertThat(message).isNotNull();
			assertThat(message).isInstanceOf(McpSchema.JSONRPCRequest.class);
			McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) message;
			assertThat(request.method()).isEqualTo("test");
			assertThat(request.id()).isEqualTo(1);
		}).verifyComplete();
	}

	@Test
	void shouldNotifyClients() {
		// Set session factory
		transportProvider.setSessionFactory(sessionFactory);

		// Send notification
		String method = "testNotification";
		Map<String, Object> params = Map.of("key", "value");

		StepVerifier.create(transportProvider.notifyClients(method, params)).verifyComplete();

		// Error log should be empty
		assertThat(testErr.toString()).doesNotContain("Error");
	}

	@Test
	void shouldCloseGracefully() {
		// Set session factory
		transportProvider.setSessionFactory(sessionFactory);

		// Close gracefully
		StepVerifier.create(transportProvider.closeGracefully()).verifyComplete();

		// Error log should be empty
		assertThat(testErr.toString()).doesNotContain("Error");
	}

	@Test
	void shouldHandleMultipleCloseGracefullyCalls() {
		// Set session factory
		transportProvider.setSessionFactory(sessionFactory);

		// Close gracefully multiple times
		StepVerifier
			.create(transportProvider.closeGracefully()
				.then(transportProvider.closeGracefully())
				.then(transportProvider.closeGracefully()))
			.verifyComplete();

		// Error log should be empty
		assertThat(testErr.toString()).doesNotContain("Error");
	}

	@Test
	void shouldHandleNotificationBeforeSessionFactoryIsSet() {

		transportProvider = new StdioServerTransportProvider(objectMapper);
		// Send notification before setting session factory
		StepVerifier.create(transportProvider.notifyClients("testNotification", Map.of("key", "value")))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpError.class);
			});
	}

	@Test
	void shouldHandleInvalidJsonMessage() throws Exception {

		// Write an invalid JSON message to the input stream
		String jsonMessage = "{invalid json}\n";
		InputStream stream = new ByteArrayInputStream(jsonMessage.getBytes(StandardCharsets.UTF_8));

		transportProvider = new StdioServerTransportProvider(objectMapper, stream, testOutPrintStream);

		// Set up a session factory
		transportProvider.setSessionFactory(sessionFactory);

		// Use StepVerifier with a timeout to wait for the error to be processed
		StepVerifier
			.create(Mono.delay(java.time.Duration.ofMillis(500)).then(Mono.fromCallable(() -> testErr.toString())))
			.assertNext(errorOutput -> assertThat(errorOutput).contains("Error processing inbound message"))
			.verifyComplete();
	}

	@Test
	void shouldHandleSessionClose() throws Exception {
		// Set session factory
		transportProvider.setSessionFactory(sessionFactory);

		// Close the transport provider
		transportProvider.close();

		// Verify session was closed
		verify(mockSession).closeGracefully();
	}

}
