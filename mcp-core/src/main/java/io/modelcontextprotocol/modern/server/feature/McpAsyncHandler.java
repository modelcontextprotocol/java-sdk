/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import reactor.core.publisher.Mono;

/**
 * Answers a request for one primitive with a single result, e.g. the handler of
 * {@link McpServerTool#async}. It must not block. An {@link McpException} fails the
 * request with its error.
 *
 * @param <R> the request type, e.g. {@code CallToolRequest}
 * @param <O> the outcome type, e.g. {@code CallToolOutcome}
 */
@FunctionalInterface
public interface McpAsyncHandler<R, O> {

	Mono<O> handle(McpRequestContext ctx, R request);

}
