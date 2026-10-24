/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.server.McpRequestContext;

/**
 * Answers a request for one primitive with a single result, e.g. the handler of
 * {@link McpServerTool#sync}. It runs on the calling thread for a blocking transport and
 * off the event loop otherwise. An {@link McpException} fails the request with its error.
 *
 * @param <R> the request type, e.g. {@code CallToolRequest}
 * @param <O> the outcome type, e.g. {@code CallToolOutcome}
 */
@FunctionalInterface
public interface McpSyncHandler<R, O> {

	O handle(McpRequestContext ctx, R request);

}
