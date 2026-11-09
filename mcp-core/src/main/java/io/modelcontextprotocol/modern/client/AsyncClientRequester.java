/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.client.InputHandlers.HandlerResponses;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sends the requests of an asynchronous client. A call sends its request and retries it
 * for every {@code input_required} result, answering the server's input requests with the
 * call's asynchronous handlers, until the result is final.
 */
final class AsyncClientRequester {

	private final ClientRequestFactory requests;

	private final CallSettings<Mono<?>> settings;

	private final SubscriptionStream subscriptions;

	/**
	 * @param settings the client's settings, which every request spec starts from
	 */
	AsyncClientRequester(ClientRequestFactory requests, CallSettings<Mono<?>> settings) {
		this.requests = requests;
		this.settings = settings;
		this.subscriptions = requests.subscriptions(settings.capabilities());
	}

	/**
	 * The client's settings.
	 */
	CallSettings<Mono<?>> settings() {
		return this.settings;
	}

	int maxListPages() {
		return this.requests.maxListPages();
	}

	/**
	 * Opens a {@code subscriptions/listen} stream per subscription, declaring the
	 * capabilities of the client's handlers.
	 */
	Flux<SubscriptionEvent> listen(SubscriptionFilter filter, Map<String, Object> applicationMeta) {
		return this.subscriptions.events(filter, applicationMeta);
	}

	/**
	 * Runs a call to its final result, bound to {@code completeType}.
	 * @param params the request's params, a schema record or a map, without {@code _meta}
	 */
	<R extends Result> Mono<R> call(String method, @Nullable String name, Object params, CallSettings<Mono<?>> settings,
			Class<R> completeType) {
		return run(() -> this.requests.request(method, name, params, settings, completeType), settings, method);
	}

	/**
	 * Runs a {@code tools/call} to its final result.
	 * @param tool the tool's definition, used instead of the catalog's; {@code null} to
	 * look it up
	 */
	Mono<CallToolResult> callTool(String name, Object params, CallSettings<Mono<?>> settings, @Nullable Tool tool) {
		return run(() -> this.requests.toolCall(name, params, settings, tool), settings, McpSchema.METHOD_TOOLS_CALL);
	}

	/**
	 * Sends a request once and emits its result: a result of {@code completeType}, or an
	 * {@link InputRequiredResult} on the methods that may answer with one.
	 */
	Mono<Result> once(String method, @Nullable String name, Object params, CallSettings<Mono<?>> settings,
			Class<? extends Result> completeType) {
		return runOnce(() -> this.requests.request(method, name, params, settings, completeType), settings, method);
	}

	/**
	 * Sends a {@code tools/call} once, as {@link #once}.
	 */
	Mono<Result> onceTool(String name, Object params, CallSettings<Mono<?>> settings, @Nullable Tool tool) {
		return runOnce(() -> this.requests.toolCall(name, params, settings, tool), settings,
				McpSchema.METHOD_TOOLS_CALL);
	}

	/**
	 * A page of {@code tools/list} as the client lists it, after the tool catalog has
	 * kept its tools.
	 */
	ListToolsResult listedTools(ListToolsResult page) {
		return this.requests.listedTools(page);
	}

	private <R extends Result> Mono<R> run(Supplier<MultiRoundTripRequest<R>> requestSupplier,
			CallSettings<Mono<?>> settings, String method) {
		Mono<R> call = Mono.defer(() -> {
			MultiRoundTripRequest<R> request = requestSupplier.get();
			// expand runs the rounds one after another from a queue, so neither the
			// operator chain nor the stack grows with the number of rounds.
			return request.sendFirst()
				.expand(raw -> nextRound(request, settings.handlers(), raw))
				.last()
				.map(request::finalResult);
		});
		return withCallTimeout(call, settings, method);
	}

	private Mono<Result> runOnce(Supplier<MultiRoundTripRequest<? extends Result>> requestSupplier,
			CallSettings<Mono<?>> settings, String method) {
		Mono<Result> once = Mono.defer(() -> {
			MultiRoundTripRequest<? extends Result> request = requestSupplier.get();
			return request.sendFirst().map(request::outcome);
		});
		return withCallTimeout(once, settings, method);
	}

	/**
	 * The raw result of the round that follows {@code raw}, or nothing when {@code raw}
	 * is final.
	 */
	private static Mono<Object> nextRound(MultiRoundTripRequest<?> request, InputHandlers<Mono<?>> handlers,
			Object raw) {
		InputRequiredResult inputRequired = request.inputRequired(raw);
		if (inputRequired == null) {
			return Mono.empty();
		}
		Mono<HandlerResponses> responses = MultiRoundTripRequest.isStateOnly(inputRequired)
				? Mono.delay(request.stateOnlyBackoff()).thenReturn(HandlerResponses.NONE)
				: InputHandlers.respond(handlers, request.method(), inputRequired.inputRequests());
		return responses.flatMap(r -> request.sendRetry(inputRequired, r));
	}

	private static <T> Mono<T> withCallTimeout(Mono<T> call, CallSettings<?> settings, String method) {
		Duration callTimeout = settings.callTimeout();
		if (callTimeout == null) {
			return call;
		}
		return call.timeout(callTimeout,
				Mono.error(() -> new McpTimeoutException(McpTimeoutException.Kind.CALL, callTimeout, method)));
	}

}
