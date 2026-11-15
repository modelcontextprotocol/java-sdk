/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ElicitInputRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;
import io.modelcontextprotocol.modern.McpSchema.InputRequest;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The input handlers in effect for a call, one per kind of input. The capabilities a call
 * declares are derived from them, so a kind is declared exactly when it has a handler.
 *
 * @param <A> what a handler returns: a {@code Mono} of the answer for the asynchronous
 * client, the answer itself for the synchronous one
 */
final class InputHandlers<A> {

	private static final InputHandlers<?> NONE = new InputHandlers<>(null, null);

	@Nullable private final Function<ElicitFormRequest, ? extends A> formElicitation;

	@Nullable private final Function<ElicitUrlRequest, ? extends A> urlElicitation;

	private InputHandlers(@Nullable Function<ElicitFormRequest, ? extends A> formElicitation,
			@Nullable Function<ElicitUrlRequest, ? extends A> urlElicitation) {
		this.formElicitation = formElicitation;
		this.urlElicitation = urlElicitation;
	}

	@SuppressWarnings("unchecked")
	static <A> InputHandlers<A> none() {
		return (InputHandlers<A>) NONE;
	}

	InputHandlers<A> withFormElicitation(Function<ElicitFormRequest, ? extends A> handler) {
		Assert.notNull(handler, "handler must not be null");
		return new InputHandlers<>(handler, this.urlElicitation);
	}

	InputHandlers<A> withUrlElicitation(Function<ElicitUrlRequest, ? extends A> handler) {
		Assert.notNull(handler, "handler must not be null");
		return new InputHandlers<>(this.formElicitation, handler);
	}

	ClientCapabilities capabilities() {
		if (this.formElicitation == null && this.urlElicitation == null) {
			return ClientCapabilities.NONE;
		}
		return ClientCapabilities.builder()
			.elicitation(new ClientCapabilities.Elicitation(
					this.formElicitation != null ? new ClientCapabilities.Elicitation.Form() : null,
					this.urlElicitation != null ? new ClientCapabilities.Elicitation.Url() : null))
			.build();
	}

	/**
	 * Prepares one round of input requests: checks every request for a handler before any
	 * handler runs.
	 * @param method the method of the call, for errors
	 * @throws McpInputHandlerMissingException if a request has no handler
	 * @throws McpInvalidResultException if a request is {@code null}
	 */
	List<PendingInputRequest<A>> prepare(String method, Map<String, InputRequest> requests) {
		List<PendingInputRequest<A>> pending = new ArrayList<>(requests.size());
		requests.forEach((key, request) -> pending.add(prepare(method, key, request)));
		return pending;
	}

	private PendingInputRequest<A> prepare(String method, String key, @Nullable InputRequest request) {
		if (request == null) {
			throw new McpInvalidResultException(method, ResultType.INPUT_REQUIRED,
					"The input request '" + key + "' of " + method + " is null");
		}
		if (request instanceof ElicitInputRequest elicit && elicit.params() instanceof ElicitFormRequest form
				&& this.formElicitation != null) {
			Function<ElicitFormRequest, ? extends A> handler = this.formElicitation;
			return new PendingInputRequest<>(key, request, false, () -> handler.apply(form));
		}
		if (request instanceof ElicitInputRequest elicit && elicit.params() instanceof ElicitUrlRequest url
				&& this.urlElicitation != null) {
			Function<ElicitUrlRequest, ? extends A> handler = this.urlElicitation;
			return new PendingInputRequest<>(key, request, true, () -> handler.apply(url));
		}
		throw new McpInputHandlerMissingException(key, request);
	}

	/**
	 * Responds to one round of input requests with asynchronous handlers. Every request
	 * is checked for a handler before any handler runs; then all handlers run
	 * concurrently, and the first failure cancels the others and fails the round.
	 * @param method the method of the call, for errors
	 */
	static Mono<HandlerResponses> respond(InputHandlers<Mono<?>> handlers, String method,
			Map<String, InputRequest> requests) {
		return Mono.defer(() -> {
			List<PendingInputRequest<Mono<?>>> pending = handlers.prepare(method, requests);
			return Flux.fromIterable(pending)
				.flatMap(p -> Mono.<Object>defer(() -> p.handler().get())
					.switchIfEmpty(Mono.error(() -> noResponse(p)))
					.map(response -> Map.entry(p.key(), response)))
				.collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
				.map(responses -> responses(pending, responses));
		});
	}

	/**
	 * The error for a handler that completed without a response.
	 */
	static IllegalStateException noResponse(PendingInputRequest<?> pending) {
		return new IllegalStateException(
				"The " + pending.request.method() + " handler completed without a result for '" + pending.key + "'");
	}

	/**
	 * The responses of a round whose handlers all responded.
	 */
	static HandlerResponses responses(List<? extends PendingInputRequest<?>> pending, Map<String, Object> responses) {
		boolean acceptedUrlElicitation = false;
		for (PendingInputRequest<?> p : pending) {
			if (p.url && responses.get(p.key) instanceof ElicitResult result
					&& result.action() == ElicitResult.Action.ACCEPT) {
				acceptedUrlElicitation = true;
			}
		}
		return new HandlerResponses(Map.copyOf(responses), acceptedUrlElicitation);
	}

	/**
	 * The responses of one round's handlers, keyed like the input requests.
	 *
	 * @param acceptedUrlElicitation whether the user accepted a URL-mode elicitation,
	 * after which the server may hold the retry open until the user is done in the
	 * browser
	 */
	record HandlerResponses(Map<String, Object> responses, boolean acceptedUrlElicitation) {

		/** No responses, for a round in which the server asked for nothing. */
		static final HandlerResponses NONE = new HandlerResponses(Map.of(), false);

	}

	/**
	 * One input request of a round, ready for its handler.
	 *
	 * @param handler calls the handler with the request
	 */
	record PendingInputRequest<A>(String key, InputRequest request, boolean url, Supplier<? extends A> handler) {
	}

}
