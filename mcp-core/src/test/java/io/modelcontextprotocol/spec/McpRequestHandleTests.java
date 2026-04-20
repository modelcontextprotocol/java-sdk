/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link McpRequestHandle}.
 */
class McpRequestHandleTests {

	@Test
	void requestIdIsExposed() {
		McpRequestHandle<String> handle = new McpRequestHandle<>("req-1", Mono.just("result"), reason -> Mono.empty());

		assertThat(handle.requestId()).isEqualTo("req-1");
	}

	@Test
	void responseMonoEmitsValue() {
		McpRequestHandle<String> handle = new McpRequestHandle<>("req-1", Mono.just("result"), reason -> Mono.empty());

		StepVerifier.create(handle.response()).expectNext("result").verifyComplete();
	}

	@Test
	void cancelInvokesCancelFunction() {
		AtomicReference<String> capturedReason = new AtomicReference<>();
		McpRequestHandle<String> handle = new McpRequestHandle<>("req-1", Mono.just("result"), reason -> {
			capturedReason.set(reason);
			return Mono.empty();
		});

		StepVerifier.create(handle.cancel("user requested")).verifyComplete();
		assertThat(capturedReason.get()).isEqualTo("user requested");
	}

	@Test
	void cancelWithNullReason() {
		AtomicReference<String> capturedReason = new AtomicReference<>("not-null");
		McpRequestHandle<String> handle = new McpRequestHandle<>("req-1", Mono.just("result"), reason -> {
			capturedReason.set(reason);
			return Mono.empty();
		});

		StepVerifier.create(handle.cancel(null)).verifyComplete();
		assertThat(capturedReason.get()).isNull();
	}

	@Test
	void cancelPropagatesError() {
		McpRequestHandle<String> handle = new McpRequestHandle<>("req-1", Mono.just("result"),
				reason -> Mono.error(new RuntimeException("cancel failed")));

		StepVerifier.create(handle.cancel("reason")).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(RuntimeException.class).hasMessage("cancel failed");
		});
	}

	@Test
	void handleWithNullRequestId() {
		McpRequestHandle<String> handle = new McpRequestHandle<>(null, Mono.just("result"), reason -> Mono.empty());

		assertThat(handle.requestId()).isNull();
		StepVerifier.create(handle.response()).expectNext("result").verifyComplete();
	}

	@Test
	void handleWithErrorResponse() {
		McpRequestHandle<String> handle = new McpRequestHandle<>("req-err", Mono.error(new McpError("server error")),
				reason -> Mono.empty());

		StepVerifier.create(handle.response()).expectError(McpError.class).verify();
	}

	@Test
	void requestIdResolvesLazilyFromAtomicReference() {
		AtomicReference<String> idRef = new AtomicReference<>();
		McpRequestHandle<String> handle = McpRequestHandle.lazy(idRef, Mono.just("result"), reason -> Mono.empty());

		assertThat(handle.requestId()).isNull();

		idRef.set("lazy-req-1");
		assertThat(handle.requestId()).isEqualTo("lazy-req-1");
	}

}
