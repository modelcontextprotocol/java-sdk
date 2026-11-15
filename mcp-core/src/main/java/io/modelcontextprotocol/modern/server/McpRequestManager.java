/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import reactor.core.publisher.Mono;

/**
 * The transport-facing entry point that answers each request with a
 * {@link McpTransportResponse}. Use {@link #handleBlocking} when the calling thread may
 * block, {@link #handle} when it must not. Neither completes with an error.
 * <p>
 * For every request, the transport context must map
 * {@link McpHeaders#TRANSPORT_CONTEXT_KEY} to the request's mirrored headers
 * ({@code Mcp-Name} and {@code Mcp-Param-{Name}}), or to {@link McpHeaders#NOT_MIRRORED}
 * for a transport without headers. Features read them to check the headers against the
 * body, for instance the tools feature for {@code tools/call}.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpRequestManager {

	/**
	 * Answers a request for a caller that must never block. Sync user code runs on
	 * {@code Schedulers.boundedElastic()}.
	 */
	Mono<McpTransportResponse> handle(McpTransportContext transportContext, JSONRPCRequest request);

	/**
	 * Answers a request for a caller that blocks until the response completes. Sync user
	 * code runs on the subscribing thread.
	 */
	Mono<McpTransportResponse> handleBlocking(McpTransportContext transportContext, JSONRPCRequest request);

	/**
	 * Handles a client notification. The only one defined is
	 * {@code notifications/cancelled}, which transports act on themselves.
	 */
	Mono<Void> handleNotification(McpTransportContext transportContext, JSONRPCNotification notification);

}
