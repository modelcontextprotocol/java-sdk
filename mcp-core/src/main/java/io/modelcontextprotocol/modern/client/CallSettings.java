/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * The settings of one call. A client holds its own settings; a request spec starts from
 * them, and each of its setters replaces one of them for that call.
 *
 * @param callTimeout the bound on the whole call, or {@code null} for none
 * @param progress receives the call's progress, or {@code null} for none; the result
 * waits for what it returns
 * @param meta the application's entries for the call's {@code _meta}
 * @param <A> what the call's input handlers return, as in {@link InputHandlers}
 */
record CallSettings<A>(Duration roundTimeout, @Nullable Duration callTimeout,
		@Nullable Function<ProgressParams, Mono<Void>> progress, Map<String, Object> meta, InputHandlers<A> handlers) {

	/**
	 * The capabilities the call declares: the ones its handlers cover.
	 */
	ClientCapabilities capabilities() {
		return this.handlers.capabilities();
	}

	CallSettings<A> withRoundTimeout(Duration roundTimeout) {
		requirePositive(roundTimeout, "roundTimeout");
		return new CallSettings<>(roundTimeout, this.callTimeout, this.progress, this.meta, this.handlers);
	}

	CallSettings<A> withCallTimeout(Duration callTimeout) {
		requirePositive(callTimeout, "callTimeout");
		return new CallSettings<>(this.roundTimeout, callTimeout, this.progress, this.meta, this.handlers);
	}

	CallSettings<A> withProgress(Function<ProgressParams, Mono<Void>> progress) {
		Assert.notNull(progress, "progress must not be null");
		return new CallSettings<>(this.roundTimeout, this.callTimeout, progress, this.meta, this.handlers);
	}

	/**
	 * @throws IllegalArgumentException for keys the client sets itself
	 */
	CallSettings<A> withMeta(Map<String, ?> meta) {
		return new CallSettings<>(this.roundTimeout, this.callTimeout, this.progress,
				RequestMeta.applicationEntries(meta), this.handlers);
	}

	CallSettings<A> withFormElicitation(Function<ElicitFormRequest, ? extends A> handler) {
		return withHandlers(this.handlers.withFormElicitation(handler));
	}

	CallSettings<A> withUrlElicitation(Function<ElicitUrlRequest, ? extends A> handler) {
		return withHandlers(this.handlers.withUrlElicitation(handler));
	}

	private CallSettings<A> withHandlers(InputHandlers<A> handlers) {
		return new CallSettings<>(this.roundTimeout, this.callTimeout, this.progress, this.meta, handlers);
	}

	static Duration requirePositive(Duration duration, String name) {
		Assert.notNull(duration, name + " must not be null");
		Assert.isTrue(!duration.isNegative() && !duration.isZero(), name + " must be positive");
		return duration;
	}

}
