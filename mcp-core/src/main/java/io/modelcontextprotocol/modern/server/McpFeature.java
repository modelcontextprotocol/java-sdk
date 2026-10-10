/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Set;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import reactor.core.publisher.Mono;

/**
 * A composable unit of server behaviour: the methods it serves, and the capabilities it
 * contributes to {@code server/discover}. Core primitives (tools, resources, prompts,
 * completions) are features; so is anything an extension adds.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpFeature {

	/**
	 * The JSON-RPC methods this feature serves. No two features may serve the same one.
	 */
	Set<String> methods();

	/**
	 * Answer a request whose method is one of {@link #methods()}. An {@link McpException}
	 * is answered with its error; any other error signal is a bug, answered with an
	 * internal error.
	 */
	Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params);

	/** Contribute this feature's advertised capabilities. Default: none. */
	default void capabilities(ServerCapabilities.Builder builder) {
	}

	/**
	 * The methods that support multi round-trip requests: they may answer with an
	 * {@code InputRequired} result and accept its {@code requestState} on retry. Default:
	 * none.
	 */
	default Set<String> inputRequiredMethods() {
		return Set.of();
	}

}
