/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import reactor.core.publisher.Mono;

/**
 * User-implemented catalogue of tools.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpAsyncToolRepository {

	/**
	 * List (a page of) the tools this repository exposes. Must be deterministically
	 * ordered and must not vary by connection. An unrecognized cursor is an
	 * {@link McpException#invalidParams(String) invalid-params error}.
	 */
	Mono<ToolsPage> list(McpRequestContext ctx, String cursor);

	/** The tool with this name, or empty if there is none. */
	Mono<McpAsyncTool> find(McpRequestContext ctx, String name);

}
