/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StdioServerTransport}.
 *
 * @author Christian Tzolov
 */
class StdioServerTransportTests {

	private final InputStream originalIn = System.in;

	private final PrintStream originalOut = System.out;

	private final PrintStream originalErr = System.err;

	private ByteArrayOutputStream testOut;

	private ByteArrayOutputStream testErr;

	private PrintStream testOutPrintStream;

	private StdioServerTransport transport;

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		testOut = new ByteArrayOutputStream();
		testErr = new ByteArrayOutputStream();
		testOutPrintStream = new PrintStream(testOut, true);
		System.setOut(testOutPrintStream);
		System.setErr(new PrintStream(testErr));

		objectMapper = new ObjectMapper();
	}

	@AfterEach
	void tearDown() {
		if (transport != null) {
			transport.closeGracefully().block();
		}
		if (testOutPrintStream != null) {
			testOutPrintStream.close();
		}
		System.setIn(originalIn);
		System.setOut(originalOut);
		System.setErr(originalErr);
	}

	@Test
	void shouldHandleIncomingMessages() throws Exception {
		// Prepare test input
		String jsonMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"params\":{},\"id\":1}";

		// Create transport with test streams
		transport = new StdioServerTransport(objectMapper);

		// Parse expected message
		McpSchema.JSONRPCRequest expected = objectMapper.readValue(jsonMessage, McpSchema.JSONRPCRequest.class);

		// Connect transport with message handler and verify message
		StepVerifier.create(transport.connect(message -> message.doOnNext(msg -> {
			McpSchema.JSONRPCRequest received = (McpSchema.JSONRPCRequest) msg;
			assertThat(received.id()).isEqualTo(expected.id());
			assertThat(received.method()).isEqualTo(expected.method());
		}))).verifyComplete();
	}

	@Test
	@Disabled
	void shouldHandleOutgoingMessages() throws Exception {
		// Create transport with test streams
		transport = new StdioServerTransport(objectMapper);
		// transport = new StdioServerTransport(objectMapper, new BlockingInputStream(),
		// testOutPrintStream);

		// Create test messages
		JSONRPCRequest initMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "init", "init-id",
				Map.of("init", "true"));
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test", "test-id",
				Map.of("key", "value"));

		// Connect transport, send messages, and verify output in a reactive chain
		StepVerifier.create(transport.connect(message -> message)
			.then(transport.sendMessage(initMessage))
			// .then(Mono.fromRunnable(() -> testOut.reset())) // Clear buffer after init
			// message
			.then(transport.sendMessage(testMessage))
			.then(Mono.fromCallable(() -> {
				String output = testOut.toString(StandardCharsets.UTF_8);
				assertThat(output).contains("\"jsonrpc\":\"2.0\"");
				assertThat(output).contains("\"method\":\"test\"");
				assertThat(output).contains("\"id\":\"test-id\"");
				return null;
			}))).verifyComplete();
	}

	@Test
	void shouldWaitForProcessorsBeforeSendingMessage() {
		// Create transport with test streams
		transport = new StdioServerTransport(objectMapper);

		// Create test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test", "test-id",
				Map.of("key", "value"));

		// Try to send message before connecting (before processors are ready)
		StepVerifier.create(transport.sendMessage(testMessage)).verifyTimeout(java.time.Duration.ofMillis(100));

		// Connect transport and verify message can be sent
		StepVerifier.create(transport.connect(message -> message).then(transport.sendMessage(testMessage)))
			.verifyComplete();
	}

	@Test
	void shouldCloseGracefully() {
		// Create transport with test streams
		transport = new StdioServerTransport(objectMapper);

		// Create test message
		JSONRPCRequest initMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "init", "init-id",
				Map.of("init", "true"));

		// Connect transport, send message, and close gracefully in a reactive chain
		StepVerifier
			.create(transport.connect(message -> message)
				.then(transport.sendMessage(initMessage))
				.then(transport.closeGracefully()))
			.verifyComplete();

		// Verify error log is empty
		assertThat(testErr.toString()).doesNotContain("Error");
	}

}
