/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncNotifier;

/**
 * Answers a request for one primitive with a stream of notifications ending in the
 * result, e.g. the handler of {@link McpServerTool#syncStreaming}. It runs once the
 * transport has started the stream; see {@link McpSyncHandler} for where.
 *
 * @param <R> the request type, e.g. {@code CallToolRequest}
 * @param <O> the outcome type, e.g. {@code CallToolOutcome}
 */
@FunctionalInterface
public interface McpSyncStreamingHandler<R, O> {

	O handle(McpRequestContext ctx, R request, McpSyncNotifier notifier);

}
