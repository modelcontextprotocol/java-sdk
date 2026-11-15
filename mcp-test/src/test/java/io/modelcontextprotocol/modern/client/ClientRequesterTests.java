/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.client.transport.McpClientTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rounds of the asynchronous and the synchronous client against a transport that
 * answers on the calling thread.
 */
class ClientRequesterTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	// Enough rounds to overflow the stack if each round nested the next.
	private static final int MANY_ROUNDS = 10_000;

	private static final Map<String, Object> DONE = Map.of("content", List.of(), "resultType", ResultType.COMPLETE);

	private static final Object ASK_NAME = raw(InputRequiredResult.builder()
		.elicit("name", ElicitFormRequest.builder("Name?", Map.of("type", "object")).build())
		.requestState("s")
		.build());

	private static final Object STATE_ONLY = raw(InputRequiredResult.builder().requestState("s").build());

	@Test
	void asyncClientRunsManyRoundsAnsweredOnTheCallingThread() {
		AtomicInteger rounds = new AtomicInteger();
		McpAsyncClient client = McpClient
			.async(answering(raw -> rounds.incrementAndGet() < MANY_ROUNDS ? ASK_NAME : DONE))
			.maxInputRounds(MANY_ROUNDS)
			.formElicitation(request -> Mono.just(ElicitResult.decline()))
			.build();

		StepVerifier.create(client.tool("t").call()).expectNextCount(1).expectComplete().verify(TIMEOUT);

		assertThat(rounds).hasValue(MANY_ROUNDS);
	}

	@Test
	void syncClientRunsManyRoundsAnsweredOnTheCallingThread() {
		AtomicInteger rounds = new AtomicInteger();
		McpSyncClient client = McpClient
			.sync(answering(raw -> rounds.incrementAndGet() < MANY_ROUNDS ? ASK_NAME : DONE))
			.maxInputRounds(MANY_ROUNDS)
			.formElicitation(request -> ElicitResult.decline())
			.build();

		assertThat(client.tool("t").call()).isNotNull();
		assertThat(rounds).hasValue(MANY_ROUNDS);
	}

	@Test
	void syncCallTimeoutCancelsTheExchangeItIsBlockedOn() {
		AtomicBoolean cancelled = new AtomicBoolean();
		McpClientTransport silent = request -> Flux.<JSONRPCMessage>never().doOnCancel(() -> cancelled.set(true));
		McpSyncClient client = McpClient.sync(silent)
			.roundTimeout(Duration.ofMinutes(1))
			.callTimeout(Duration.ofMillis(100))
			.build();

		assertThatThrownBy(() -> client.tool("t").call()).isInstanceOfSatisfying(McpTimeoutException.class,
				ex -> assertThat(ex.kind()).isEqualTo(McpTimeoutException.Kind.CALL));
		assertThat(cancelled).isTrue();
	}

	@Test
	void syncCallTimeoutEndsTheWaitBeforeAStateOnlyRetry() {
		McpSyncClient client = McpClient.sync(answering(raw -> STATE_ONLY))
			.maxInputRounds(MANY_ROUNDS)
			.callTimeout(Duration.ofMillis(300))
			.build();

		long started = System.nanoTime();
		assertThatThrownBy(() -> client.tool("t").call()).isInstanceOfSatisfying(McpTimeoutException.class,
				ex -> assertThat(ex.kind()).isEqualTo(McpTimeoutException.Kind.CALL));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
	}

	/**
	 * A transport that answers every request at once, on the thread that subscribes.
	 */
	private static McpClientTransport answering(Function<Object, Object> answer) {
		return request -> Flux.defer(() -> Flux.<JSONRPCMessage>just(
				JSONRPCResponse.result(request.message().id(), answer.apply(request.message().params()))));
	}

	private static Object raw(InputRequiredResult result) {
		return McpJsonDefaults.getMapper().convertValue(result, Map.class);
	}

}
