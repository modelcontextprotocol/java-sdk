/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.server.McpAsyncNotifier;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import reactor.core.publisher.Mono;

/**
 * Answers a request for one primitive with a stream of notifications ending in the
 * result, e.g. the handler of {@link McpServerTool#asyncStreaming}. It runs once the
 * transport has started the stream, and must not block.
 *
 * @param <R> the request type, e.g. {@code CallToolRequest}
 * @param <O> the outcome type, e.g. {@code CallToolOutcome}
 */
@FunctionalInterface
public interface McpAsyncStreamingHandler<R, O> {

	Mono<O> handle(McpRequestContext ctx, R request, McpAsyncNotifier notifier);

}
