/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;

/**
 * Asks the user for input a server requested with {@code elicitation/create}, for a
 * {@link McpSyncClient}.
 * <p>
 * A form handler receives an {@link ElicitFormRequest}, a URL handler an
 * {@link ElicitUrlRequest}. For a URL request, ask the user for consent, open the URL,
 * and return the user's answer right away; the server learns from the browser when the
 * interaction is done.
 * <p>
 * The handler runs on the thread that made the call, so it sees that thread's
 * thread-locals. It can't be interrupted by a timeout; the call's timeout is checked when
 * it returns.
 *
 * @param <R> the kind of elicitation request
 * @author Dariusz Jędrzejczyk
 */
@FunctionalInterface
public interface McpSyncElicitationHandler<R extends ElicitRequest> {

	/**
	 * Asks the user and returns the answer.
	 */
	ElicitResult elicit(R request);

}
