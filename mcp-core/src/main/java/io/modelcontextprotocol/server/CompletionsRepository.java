/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Repository contract for handling stateless completion requests from the current MCP
 * request context.
 *
 * @author Taewoong Kim
 */
public interface CompletionsRepository {

	/**
	 * Complete the request for the current request context.
	 * @param request the completion request
	 * @param transportContext the transport context for the current request
	 * @return the completion result
	 */
	McpSchema.CompleteResult complete(McpSchema.CompleteRequest request, McpTransportContext transportContext);

}
