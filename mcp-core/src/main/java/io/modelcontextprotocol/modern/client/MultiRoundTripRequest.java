/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.client.InputHandlers.HandlerResponses;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * One request of a call across its round trips: the first send, and a retry for every
 * {@code input_required} result the server answers with, until the result is final. The
 * asynchronous and the synchronous client run the rounds; this class decides what each
 * round sends and how each result is read.
 * <p>
 * The rounds of a call run one after another, so its state needs no synchronization.
 *
 * @param <R> the type of the final result
 */
final class MultiRoundTripRequest<R extends Result> {

	/** The methods the server may answer with {@code input_required}. */
	static final Set<String> METHODS = Set.of(McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_RESOURCES_READ,
			McpSchema.METHOD_PROMPTS_GET);

	static final String INPUT_RESPONSES = "inputResponses";

	static final String REQUEST_STATE = "requestState";

	private static final Duration MIN_BACKOFF = Duration.ofMillis(50);

	private static final Duration MAX_BACKOFF = Duration.ofMillis(250);

	private final String method;

	private final Map<String, Object> params;

	private final Duration roundTimeout;

	private final Class<R> completeType;

	private final McpJsonMapper jsonMapper;

	private final int maxInputRounds;

	private final RoundSender sender;

	private int inputRounds;

	private int stateOnlyRounds;

	// The server may hold the request that follows an accepted URL elicitation open until
	// the user is done in the browser, so that request has no round timeout.
	private boolean untimedNextRound;

	/**
	 * @param params the request's params without {@code _meta}
	 * @param roundTimeout the timeout of each round, except the one after an accepted URL
	 * elicitation
	 * @param sender sends each round
	 */
	MultiRoundTripRequest(String method, Map<String, Object> params, Duration roundTimeout, Class<R> completeType,
			McpJsonMapper jsonMapper, int maxInputRounds, RoundSender sender) {
		this.method = method;
		this.params = params;
		this.roundTimeout = roundTimeout;
		this.completeType = completeType;
		this.jsonMapper = jsonMapper;
		this.maxInputRounds = maxInputRounds;
		this.sender = sender;
	}

	String method() {
		return this.method;
	}

	/**
	 * Sends the request with the params the call started with.
	 */
	Mono<Object> sendFirst() {
		return send(this.params);
	}

	/**
	 * Sends the request again for {@code inputRequired}, with this round's
	 * {@code responses} and the server's {@code requestState}.
	 */
	Mono<Object> sendRetry(InputRequiredResult inputRequired, HandlerResponses responses) {
		this.untimedNextRound = responses.acceptedUrlElicitation();
		return send(retryParams(this.params, inputRequired, responses.responses()));
	}

	private Mono<Object> send(Map<String, Object> params) {
		Duration roundTimeout = this.untimedNextRound ? null : this.roundTimeout;
		this.untimedNextRound = false;
		return this.sender.send(params, roundTimeout);
	}

	/**
	 * The {@code input_required} result {@code raw} carries, which starts another round,
	 * or {@code null} when {@code raw} is final.
	 * @throws McpInvalidResultException if the method may not answer with the result's
	 * {@code resultType}, or the result doesn't bind
	 * @throws McpInputRoundsExceededException if the call has had its rounds
	 */
	@Nullable InputRequiredResult inputRequired(Object raw) {
		String resultType = ResultDecoding.resultType(this.method, raw);
		if (resultType == null || ResultType.COMPLETE.equals(resultType)) {
			return null;
		}
		if (!ResultType.INPUT_REQUIRED.equals(resultType) || !METHODS.contains(this.method)) {
			throw ResultDecoding.unaccepted(this.method, resultType);
		}
		InputRequiredResult inputRequired = ResultDecoding.bind(this.jsonMapper, this.method, resultType, raw,
				InputRequiredResult.class);
		if (++this.inputRounds > this.maxInputRounds) {
			throw new McpInputRoundsExceededException(this.method, this.maxInputRounds);
		}
		this.stateOnlyRounds = isStateOnly(inputRequired) ? this.stateOnlyRounds + 1 : 0;
		return inputRequired;
	}

	/**
	 * Whether the server asks only for a retry with its {@code requestState}, without
	 * input requests.
	 */
	static boolean isStateOnly(InputRequiredResult inputRequired) {
		return inputRequired.inputRequests() == null || inputRequired.inputRequests().isEmpty();
	}

	/**
	 * The wait before the retry of the current state-only round.
	 */
	Duration stateOnlyBackoff() {
		return backoff(this.stateOnlyRounds);
	}

	/**
	 * The final result {@code raw}, bound to the call's result type.
	 * @throws McpInvalidResultException if the result isn't complete or doesn't bind
	 */
	R finalResult(Object raw) {
		return ResultDecoding.bindComplete(this.jsonMapper, this.method, raw, this.completeType);
	}

	/**
	 * The result of a single round: the final result, or an {@link InputRequiredResult}
	 * on the methods that may answer with one.
	 * @throws McpInvalidResultException for any other result
	 */
	Result outcome(Object raw) {
		String resultType = ResultDecoding.resultType(this.method, raw);
		if (ResultType.INPUT_REQUIRED.equals(resultType) && METHODS.contains(this.method)) {
			return ResultDecoding.bind(this.jsonMapper, this.method, resultType, raw, InputRequiredResult.class);
		}
		return finalResult(raw);
	}

	/**
	 * The params of the retry of a request answered with {@code inputRequired}: the
	 * original params with this round's {@code responses} as {@code inputResponses},
	 * omitted when there are none, and the server's {@code requestState} as it was
	 * received, omitted when the server sent none.
	 */
	static Map<String, Object> retryParams(Map<String, Object> original, InputRequiredResult inputRequired,
			Map<String, Object> responses) {
		Map<String, Object> params = new LinkedHashMap<>(original);
		if (responses.isEmpty()) {
			params.remove(INPUT_RESPONSES);
		}
		else {
			params.put(INPUT_RESPONSES, Map.copyOf(responses));
		}
		if (inputRequired.requestState() == null) {
			params.remove(REQUEST_STATE);
		}
		else {
			params.put(REQUEST_STATE, inputRequired.requestState());
		}
		return Collections.unmodifiableMap(params);
	}

	/**
	 * The wait before the {@code n}th consecutive retry the server asked for with
	 * {@code requestState} only: 50 ms, doubling up to 250 ms.
	 */
	static Duration backoff(int n) {
		Duration backoff = MIN_BACKOFF.multipliedBy(1L << Math.min(n - 1, 10));
		return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
	}

	/**
	 * Sends one round of a request: the request with the round's params, as one JSON-RPC
	 * exchange.
	 */
	@FunctionalInterface
	interface RoundSender {

		/**
		 * Sends the request with {@code params} and emits its raw result.
		 * @param params the round's params without {@code _meta}
		 * @param roundTimeout the timeout of the exchange, or {@code null} for none
		 */
		Mono<Object> send(Map<String, Object> params, @Nullable Duration roundTimeout);

	}

}
