/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Set;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.InputRequired;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import reactor.core.publisher.Mono;

/**
 * A composable unit of server behaviour: the methods it serves, the capabilities it
 * contributes to {@code server/discover}, and the references it completes for
 * {@code completion/complete}. Core primitives (tools, resources, prompts) are features;
 * so is anything an extension adds.
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
	 * {@link InputRequired} result, within the input requests it allows, and accept its
	 * {@code requestState} on retry. Default: none.
	 */
	default Set<String> inputRequiredMethods() {
		return Set.of();
	}

	/**
	 * The {@code completion/complete} reference types, such as {@code ref/prompt}, this
	 * feature answers through {@link #complete}. No two features may answer the same one;
	 * the server serves {@code completion/complete} and advertises {@code completions}
	 * when any feature answers one. Default: none.
	 */
	default Set<String> completionRefTypes() {
		return Set.of();
	}

	/**
	 * Answer a {@code completion/complete} whose ref type is one of
	 * {@link #completionRefTypes()}, with the same error rules as {@link #handle}.
	 */
	default Mono<CompleteResult> complete(McpRequestContext ctx, CompleteRequest request) {
		return Mono.error(new UnsupportedOperationException(getClass().getName() + " does not complete"));
	}

	/**
	 * The part of a {@code subscriptions/listen} filter this feature agrees to honour:
	 * fields it defines, such as an extension's, taken from {@code requested} as they are
	 * or narrowed. The server acknowledges them next to the core fields it honours
	 * itself, and the feature then publishes the matching {@code ServerChange}s. Default:
	 * none.
	 * @throws McpException to reject the request, for example because the client didn't
	 * declare the extension that defines a requested field; it is answered with its error
	 * and no stream opens
	 */
	default SubscriptionFilter honouredSubscriptions(McpRequestContext ctx, SubscriptionFilter requested) {
		return SubscriptionFilter.EMPTY;
	}

}
