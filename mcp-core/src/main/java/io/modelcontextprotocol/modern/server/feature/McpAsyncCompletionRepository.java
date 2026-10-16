/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import reactor.core.publisher.Mono;

/**
 * Answers {@code completion/complete}. Completion never streams and never returns MRTR.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpAsyncCompletionRepository {

	Mono<CompleteResult> complete(McpRequestContext ctx, CompleteRequest request);

}
