/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.server.McpRequestContext;

/**
 * The blocking counterpart of {@link McpAsyncCompletionHandler}.
 *
 * @author Dariusz Jędrzejczyk
 */
@FunctionalInterface
public interface McpSyncCompletionHandler {

	CompleteResult complete(McpRequestContext ctx, CompleteRequest request);

}
