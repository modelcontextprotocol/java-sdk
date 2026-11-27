/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link StdioClientTransport}.
 *
 * @author Daniel Garnier-Moiroux
 */
class StdioClientTransportTests {

	private final PrintStream originalOut = System.out;

	private final PrintStream originalErr = System.err;

	private ByteArrayOutputStream testErr;

	@BeforeEach
	void setUp() {
		testErr = new ByteArrayOutputStream();
		PrintStream testOutPrintStream = new PrintStream(testErr, true);
		System.setOut(testOutPrintStream);
		System.setErr(testOutPrintStream);
	}

	@AfterEach
	void tearDown() {
		System.setOut(originalOut);
		System.setErr(originalErr);
	}

	@Test
	void shouldRejectInboundMessageExceedingMaxSize() throws Exception {
		// A server process that emits an endless line with no newline terminator. A
		// plain BufferedReader#readLine would buffer it all; the bounded reader must
		// abort instead of exhausting memory.
		int maxSize = 1024;
		ServerParameters params = ServerParameters.builder("sh").args("-c", "while :; do printf a; done").build();

		StdioClientTransport transport = new StdioClientTransport(params, JSON_MAPPER, maxSize);
		try {
			StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

			Awaitility.await()
				.atMost(Duration.ofSeconds(5))
				.pollInterval(Duration.ofMillis(100))
				.untilAsserted(() -> assertThat(testErr.toString())
					.contains("Inbound message exceeds the maximum allowed size"));
		}
		finally {
			StepVerifier.create(transport.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void shouldFailMalformedResponsesAndKeepProcessing(@TempDir Path tempDir) throws Exception {
		// A server process that answers with two malformed responses and a line that
		// is not JSON, followed by a valid response. It then stays alive until its
		// stdin is closed.
		Path serverOutput = tempDir.resolve("server-output.jsonl");
		Files.write(serverOutput,
				List.of("{\"id\":\"missing-jsonrpc\",\"result\":{}}",
						"{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{},\"error\":{\"code\":-32000,\"message\":\"boom\"}}",
						"this is not json", "{\"jsonrpc\":\"2.0\",\"id\":\"valid\",\"result\":{}}"));
		ServerParameters params = ServerParameters.builder("sh")
			.args("-c", "cat '" + serverOutput.toString().replace('\\', '/') + "'; cat > /dev/null")
			.build();

		List<JSONRPCMessage> received = new CopyOnWriteArrayList<>();
		StdioClientTransport transport = new StdioClientTransport(params, JSON_MAPPER);
		try {
			StepVerifier.create(transport.connect(msg -> msg.doOnNext(received::add))).verifyComplete();

			Awaitility.await()
				.atMost(Duration.ofSeconds(5))
				.pollInterval(Duration.ofMillis(100))
				.untilAsserted(() -> assertThat(received).hasSize(3));

			// each malformed response fails the request it answers
			assertThat(received.get(0)).isInstanceOfSatisfying(JSONRPCResponse.class, response -> {
				assertThat(response.id()).isEqualTo("missing-jsonrpc");
				assertThat(response.result()).isNull();
				assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
			});
			assertThat(received.get(1)).isInstanceOfSatisfying(JSONRPCResponse.class, response -> {
				assertThat(response.id()).isEqualTo(2);
				assertThat(response.result()).isNull();
				assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
			});
			// the transport is still reading, so the valid response gets through
			assertThat(received.get(2)).isInstanceOfSatisfying(JSONRPCResponse.class, response -> {
				assertThat(response.id()).isEqualTo("valid");
				assertThat(response.error()).isNull();
			});
		}
		finally {
			StepVerifier.create(transport.closeGracefully()).verifyComplete();
		}
	}

}
