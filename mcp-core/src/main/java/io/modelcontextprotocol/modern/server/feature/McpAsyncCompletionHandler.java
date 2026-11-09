/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import reactor.core.publisher.Mono;

/**
 * Answers {@code completion/complete} for the arguments of one prompt or resource
 * template. Completion never streams and never returns MRTR.
 *
 * @author Dariusz Jędrzejczyk
 */
@FunctionalInterface
public interface McpAsyncCompletionHandler {

	/**
	 * The request's ref names this handler's prompt or resource template, and its
	 * argument is one that prompt or template declares.
	 */
	Mono<CompleteResult> complete(McpRequestContext ctx, CompleteRequest request);

}
