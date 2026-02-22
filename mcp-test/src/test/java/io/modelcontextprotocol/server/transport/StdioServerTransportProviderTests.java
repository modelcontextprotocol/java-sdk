/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class StdioServerTransportProviderTests {

	private final PrintStream originalOut = System.out;

	private final PrintStream originalErr = System.err;

	private ByteArrayOutputStream testErr;

	private PrintStream testOutPrintStream;

	private StdioServerTransportProvider transportProvider;

	private McpServerSession.Factory sessionFactory;

	private McpServerSession mockSession;

	@BeforeEach
	void setUp() {
		testErr = new ByteArrayOutputStream();

		testOutPrintStream = new PrintStream(testErr, true);
		System.setOut(testOutPrintStream);
		System.setErr(testOutPrintStream);

		// Create mocks for session factory and session
		mockSession = mock(McpServerSession.class);
		sessionFactory = mock(McpServerSession.Factory.class);

		// Configure mock behavior
		when(sessionFactory.create(any(McpServerTransport.class))).thenReturn(mockSession);
		when(mockSession.closeGracefully()).thenReturn(Mono.empty());
		when(mockSession.sendNotification(any(), any())).thenReturn(Mono.empty());

		transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), System.in,
				testOutPrintStream);
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

		transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), stream, System.out);
		// Set up a real session to capture the message
		AtomicReference<McpSchema.JSONRPCMessage> capturedMessage = new AtomicReference<>();
		CountDownLatch messageLatch = new CountDownLatch(1);

		McpServerSession.Factory realSessionFactory = transport -> {
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
	void shouldHandleUtf8MessagesWithNonUtf8DefaultCharset() throws Exception {
		String utf8Content = "한글 漢字 café 🎉";
		String jsonMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"test\"," + "\"params\":{\"message\":\"" + utf8Content
				+ "\"},\"id\":1}\n";

		// Start a subprocess with non-UTF-8 default charset
		String javaHome = System.getProperty("java.home");
		String classpath = System.getProperty("java.class.path");
		ProcessBuilder pb = new ProcessBuilder(javaHome + "/bin/java", "-Dfile.encoding=ISO-8859-1", "-cp", classpath,
				StdioUtf8TestServer.class.getName());
		pb.redirectErrorStream(false);
		Process process = pb.start();

		try {
			// Write UTF-8 encoded JSON-RPC message to the subprocess stdin
			process.getOutputStream().write(jsonMessage.getBytes(StandardCharsets.UTF_8));
			process.getOutputStream().flush();
			process.getOutputStream().close();

			// Read the echoed message from subprocess stdout
			String result;
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				result = reader.readLine();
			}

			// Verify that multi-byte UTF-8 characters survived the round trip
			assertThat(result).isEqualTo(utf8Content);
		}
		finally {
			process.destroyForcibly();
			process.waitFor(10, TimeUnit.SECONDS);
		}
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

		transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
		// Send notification before setting session factory
		StepVerifier.create(transportProvider.notifyClients("testNotification", Map.of("key", "value")))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(IllegalStateException.class);
			});
	}

	@Test
	void shouldHandleInvalidJsonMessage() throws Exception {

		// Write an invalid JSON message to the input stream
		String jsonMessage = "{invalid json}\n";
		InputStream stream = new ByteArrayInputStream(jsonMessage.getBytes(StandardCharsets.UTF_8));

		transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), stream, testOutPrintStream);

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
