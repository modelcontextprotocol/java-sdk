/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.server.McpRequestContext;

/**
 * The blocking counterpart of {@link McpAsyncCompletionRepository}.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpSyncCompletionRepository {

	CompleteResult complete(McpRequestContext ctx, CompleteRequest request);

}
