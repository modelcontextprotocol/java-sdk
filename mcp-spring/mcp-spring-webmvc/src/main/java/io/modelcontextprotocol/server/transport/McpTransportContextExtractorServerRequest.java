/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * {@link McpTransportContextExtractor} implementation for {@link ServerRequest}.
 */
public class McpTransportContextExtractorServerRequest implements McpTransportContextExtractor<ServerRequest> {

	@Override
	public McpTransportContext extract(ServerRequest request) {
		return McpTransportContext
			.create(McpTransportContext.createMetadata(headerName -> request.headers().firstHeader(headerName)));
	}

}
