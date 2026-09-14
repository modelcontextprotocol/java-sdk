/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

}
