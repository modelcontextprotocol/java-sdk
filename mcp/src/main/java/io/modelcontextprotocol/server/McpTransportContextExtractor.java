/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;

/**
 * The contract for extracting metadata from a generic transport request of type
 * {@link T}.
 *
 * @param <T> transport-specific representation of the request which allows extracting
 * metadata for use in the MCP features implementations.
 * @author Dariusz Jędrzejczyk
 */
public interface McpTransportContextExtractor<T> {

	/**
	 * Extract transport-specific metadata from the request into an McpTransportContext.
	 * @param request the generic representation for the request in the context of a
	 * specific transport implementation
	 * @return the context containing the metadata
	 */
	McpTransportContext extract(T request);

}
