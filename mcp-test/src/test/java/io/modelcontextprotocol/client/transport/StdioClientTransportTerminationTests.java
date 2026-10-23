/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the bounded server process termination in
 * {@link StdioClientTransport#closeGracefully()} (#937).
 */
class StdioClientTransportTerminationTests {

	@Test
	@DisabledOnOs(OS.WINDOWS) // Process#destroy is TerminateProcess there, it cannot be
								// ignored
	void closeGracefullyDestroysForciblyWhenServerIgnoresSigterm() {
		ServerParameters params = ServerParameters.builder("sh")
			.args("-c", "trap '' TERM; echo ready >&2; while :; do sleep 1; done")
			.processTerminationTimeout(Duration.ofSeconds(1))
			.build();

		assertClosesWithin(params, Duration.ofSeconds(10));
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void closeGracefullyDoesNotWaitForTimeoutWhenServerExitsOnSigterm() {
		ServerParameters params = ServerParameters.builder("sh")
			.args("-c", "echo ready >&2; while :; do sleep 1; done")
			.processTerminationTimeout(Duration.ofSeconds(30))
			.build();

		assertClosesWithin(params, Duration.ofSeconds(10));
	}

	@Test
	void processTerminationTimeoutDefaultsToTenSeconds() {
		assertThat(ServerParameters.builder("sh").build().getProcessTerminationTimeout())
			.isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void processTerminationTimeoutMustBePositive() {
		ServerParameters.Builder builder = ServerParameters.builder("sh");
		assertThatIllegalArgumentException().isThrownBy(() -> builder.processTerminationTimeout(null));
		assertThatIllegalArgumentException().isThrownBy(() -> builder.processTerminationTimeout(Duration.ZERO));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> builder.processTerminationTimeout(Duration.ofSeconds(-1)));
	}

	@Test
	void processTerminationTimeoutMustFitInNanoseconds() {
		ServerParameters.Builder builder = ServerParameters.builder("sh");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> builder.processTerminationTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
		assertThat(builder.processTerminationTimeout(Duration.ofNanos(Long.MAX_VALUE))
			.build()
			.getProcessTerminationTimeout()).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
	}

	private static void assertClosesWithin(ServerParameters params, Duration limit) {
		StdioClientTransport transport = new StdioClientTransport(params, JSON_MAPPER);
		AtomicReference<String> stderr = new AtomicReference<>();
		transport.setStdErrorHandler(stderr::set);

		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();
		Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> "ready".equals(stderr.get()));
		ProcessHandle server = ProcessHandle.current()
			.children()
			.filter(p -> p.info().command().map(c -> c.endsWith("sh")).orElse(false))
			.findFirst()
			.orElseThrow();

		try {
			StepVerifier.create(transport.closeGracefully()).expectComplete().verify(limit);
		}
		finally {
			boolean alive = server.isAlive();
			server.destroyForcibly();
			assertThat(alive).as("server process still alive after closeGracefully()").isFalse();
		}
	}

}
