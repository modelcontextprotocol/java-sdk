/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.client.InputHandlers.HandlerResponses;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiRoundTripRequestTests {

	private static final Duration ROUND_TIMEOUT = Duration.ofSeconds(2);

	private static final CallSettings<Mono<?>> SETTINGS = new CallSettings<>(ROUND_TIMEOUT, null, null, Map.of(),
			InputHandlers.none());

	private static final Map<String, Object> ORIGINAL = Map.of("name", "book", "arguments", Map.of("city", "Oslo"),
			"inputResponses", Map.of("old", "answer"), "requestState", "old-state");

	private static final Map<String, Object> DONE = Map.of("content", List.of(), "resultType", ResultType.COMPLETE);

	private static final InputRequiredResult WITH_STATE = InputRequiredResult.builder()
		.elicit("name", ElicitFormRequest.builder("Name?", Map.of("type", "object")).build())
		.requestState("s-2")
		.build();

	private static final InputRequiredResult WITHOUT_STATE = InputRequiredResult.builder()
		.elicit("name", ElicitFormRequest.builder("Name?", Map.of("type", "object")).build())
		.build();

	private static final InputRequiredResult STATE_ONLY = InputRequiredResult.builder().requestState("s").build();

	private final List<McpTransportRequest> sent = new CopyOnWriteArrayList<>();

	@Test
	void retryCarriesThisRoundsResponsesAndTheServersState() {
		Map<String, Object> retry = MultiRoundTripRequest.retryParams(ORIGINAL, WITH_STATE,
				Map.of("name", ElicitResult.decline()));

		assertThat(retry).containsEntry("name", "book")
			.containsEntry("arguments", Map.of("city", "Oslo"))
			.containsEntry("inputResponses", Map.of("name", ElicitResult.decline()))
			.containsEntry("requestState", "s-2");
	}

	@Test
	void retryOmitsStateTheServerDidNotSend() {
		Map<String, Object> retry = MultiRoundTripRequest.retryParams(ORIGINAL, WITHOUT_STATE, Map.of("name", "r"));

		assertThat(retry).doesNotContainKey("requestState").containsEntry("inputResponses", Map.of("name", "r"));
	}

	@Test
	void retryWithoutResponsesOmitsInputResponses() {
		Map<String, Object> retry = MultiRoundTripRequest.retryParams(ORIGINAL, STATE_ONLY, Map.of());

		assertThat(retry).doesNotContainKey("inputResponses").containsEntry("requestState", "s");
	}

	@Test
	void backoffDoublesFromFiftyToTwoHundredFiftyMillis() {
		assertThat(MultiRoundTripRequest.backoff(1)).isEqualTo(Duration.ofMillis(50));
		assertThat(MultiRoundTripRequest.backoff(2)).isEqualTo(Duration.ofMillis(100));
		assertThat(MultiRoundTripRequest.backoff(3)).isEqualTo(Duration.ofMillis(200));
		assertThat(MultiRoundTripRequest.backoff(4)).isEqualTo(Duration.ofMillis(250));
		assertThat(MultiRoundTripRequest.backoff(100)).isEqualTo(Duration.ofMillis(250));
	}

	@Test
	void completeOrMissingResultTypeIsFinal() {
		MultiRoundTripRequest<CallToolResult> request = toolCall(10);

		assertThat(request.inputRequired(DONE)).isNull();
		assertThat(request.inputRequired(Map.of("content", List.of()))).isNull();
	}

	@Test
	void inputRequiredStartsARound() {
		InputRequiredResult inputRequired = toolCall(10).inputRequired(raw(WITH_STATE));

		assertThat(inputRequired).isNotNull();
		assertThat(inputRequired.inputRequests()).containsOnlyKeys("name");
		assertThat(MultiRoundTripRequest.isStateOnly(inputRequired)).isFalse();
		assertThat(MultiRoundTripRequest.isStateOnly(STATE_ONLY)).isTrue();
	}

	@Test
	void consecutiveStateOnlyRoundsBackOffUntilARoundAsksForInput() {
		MultiRoundTripRequest<CallToolResult> request = toolCall(10);

		request.inputRequired(raw(STATE_ONLY));
		assertThat(request.stateOnlyBackoff()).isEqualTo(Duration.ofMillis(50));
		request.inputRequired(raw(STATE_ONLY));
		assertThat(request.stateOnlyBackoff()).isEqualTo(Duration.ofMillis(100));
		request.inputRequired(raw(WITH_STATE));
		request.inputRequired(raw(STATE_ONLY));
		assertThat(request.stateOnlyBackoff()).isEqualTo(Duration.ofMillis(50));
	}

	@Test
	void roundsBeyondTheLimitFail() {
		MultiRoundTripRequest<CallToolResult> request = toolCall(2);

		request.inputRequired(raw(WITH_STATE));
		request.inputRequired(raw(WITH_STATE));

		assertThatThrownBy(() -> request.inputRequired(raw(WITH_STATE)))
			.isInstanceOfSatisfying(McpInputRoundsExceededException.class, ex -> {
				assertThat(ex.maxRounds()).isEqualTo(2);
				assertThat(ex.method()).isEqualTo(McpSchema.METHOD_TOOLS_CALL);
			});
	}

	@Test
	void inputRequiredIsInvalidOnMethodsThatMayNotAnswerWithIt() {
		MultiRoundTripRequest<ListPromptsResult> request = request(McpSchema.METHOD_PROMPTS_LIST,
				ListPromptsResult.class, 10);

		assertThatThrownBy(() -> request.inputRequired(raw(WITH_STATE))).isInstanceOfSatisfying(
				McpInvalidResultException.class,
				ex -> assertThat(ex.resultType()).isEqualTo(ResultType.INPUT_REQUIRED));
	}

	@Test
	void unknownResultTypeIsInvalid() {
		assertThatThrownBy(() -> toolCall(10).inputRequired(Map.of("resultType", "pending"))).isInstanceOfSatisfying(
				McpInvalidResultException.class, ex -> assertThat(ex.resultType()).isEqualTo("pending"));
	}

	@Test
	void finalResultMustBeComplete() {
		MultiRoundTripRequest<CallToolResult> request = toolCall(10);

		assertThat(request.finalResult(DONE)).isInstanceOf(CallToolResult.class);
		assertThatThrownBy(() -> request.finalResult(raw(WITH_STATE))).isInstanceOf(McpInvalidResultException.class);
	}

	@Test
	void outcomeOfASingleRoundIsEitherResult() {
		MultiRoundTripRequest<CallToolResult> request = toolCall(10);

		Result inputRequired = request.outcome(raw(WITH_STATE));
		Result complete = request.outcome(DONE);

		assertThat(inputRequired).isInstanceOf(InputRequiredResult.class);
		assertThat(complete).isInstanceOf(CallToolResult.class);
	}

	@Test
	void retrySendsTheResponsesAndTheStateWithTheOriginalArguments() {
		MultiRoundTripRequest<CallToolResult> request = toolCall(10);

		request.sendFirst().block();
		request.sendRetry(WITH_STATE, new HandlerResponses(Map.of("name", ElicitResult.decline()), false)).block();

		assertThat(this.sent).hasSize(2);
		Map<?, ?> retry = (Map<?, ?>) this.sent.get(1).message().params();
		assertThat(retry.get("arguments")).isEqualTo(Map.of("city", "Oslo"));
		assertThat(retry.get("requestState")).isEqualTo("s-2");
		assertThat(retry.get("inputResponses")).isEqualTo(Map.of("name", ElicitResult.decline()));
		assertThat(this.sent.get(0).message().id()).isNotEqualTo(this.sent.get(1).message().id());
	}

	@Test
	void roundAfterAnAcceptedUrlElicitationHasNoRoundTimeout() {
		MultiRoundTripRequest<CallToolResult> request = toolCall(10);

		request.sendFirst().block();
		request.sendRetry(WITH_STATE, new HandlerResponses(Map.of("name", ElicitResult.accept(null)), true)).block();
		request.sendRetry(WITH_STATE, HandlerResponses.NONE).block();

		assertThat(this.sent).extracting(McpTransportRequest::roundTimeout)
			.containsExactly(ROUND_TIMEOUT, null, ROUND_TIMEOUT);
	}

	private MultiRoundTripRequest<CallToolResult> toolCall(int maxInputRounds) {
		return request(McpSchema.METHOD_TOOLS_CALL, CallToolResult.class, maxInputRounds);
	}

	private <R extends Result> MultiRoundTripRequest<R> request(String method, Class<R> completeType,
			int maxInputRounds) {
		JsonRpcRequestSender sender = new JsonRpcRequestSender(request -> Flux.defer(() -> {
			this.sent.add(request);
			return Flux.<JSONRPCMessage>just(JSONRPCResponse.result(request.message().id(), DONE));
		}), McpJsonDefaults.getMapper(), null, List.of(McpSchema.LATEST_PROTOCOL_VERSION));
		return new ClientRequestFactory(sender, null, maxInputRounds, McpClient.DEFAULT_MAX_LIST_PAGES).request(method,
				"book", ORIGINAL, SETTINGS, completeType);
	}

	private static Object raw(InputRequiredResult result) {
		return McpJsonDefaults.getMapper().convertValue(result, Map.class);
	}

}
