/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;
import reactor.core.publisher.Mono;

/**
 * Asks the user for input a server requested with {@code elicitation/create}.
 * <p>
 * A form handler receives an {@link ElicitFormRequest}, a URL handler an
 * {@link ElicitUrlRequest}. For a URL request, ask the user for consent, open the URL,
 * and complete with the user's answer right away; the server learns from the browser when
 * the interaction is done.
 * <p>
 * The returned {@code Mono} is subscribed in the call's Reactor context and must not
 * block. It is cancelled when the call ends without its answer, for example when another
 * handler of the same round fails or a timeout expires; close any pending dialog then.
 *
 * @param <R> the kind of elicitation request
 * @author Dariusz Jędrzejczyk
 */
@FunctionalInterface
public interface McpAsyncElicitationHandler<R extends ElicitRequest> {

	/**
	 * Asks the user and emits the answer.
	 */
	Mono<ElicitResult> elicit(R request);

}
