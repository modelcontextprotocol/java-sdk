/*
 * Copyright 2025-2025 the original author or authors.
 */
package io.modelcontextprotocol.server.servlet;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link McpTransportContextExtractor} implementation for {@link HttpServletRequest}.
 */
public class HttpServletRequestMcpTransportContextExtractor
		implements McpTransportContextExtractor<HttpServletRequest> {

	@Override
	public McpTransportContext extract(HttpServletRequest request) {
		return McpTransportContext.create(McpTransportContext.createMetadata(request::getHeader));
	}

}
