/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.jsonrpc.JSONRPCNotification;
import io.modelcontextprotocol.spec.jsonrpc.JSONRPCRequest;
import io.modelcontextprotocol.spec.jsonrpc.JSONRPCResponse;
import reactor.core.publisher.Mono;

/**
 * Handler for MCP requests and notifications in a Stateless Streamable HTTP Server
 * context.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpStatelessServerHandler {

	/**
	 * Handle the request using user-provided feature implementations.
	 * @param transportContext {@link McpTransportContext} carrying transport layer
	 * metadata
	 * @param request the request JSON object
	 * @return Mono containing the JSON response
	 */
	Mono<JSONRPCResponse> handleRequest(McpTransportContext transportContext, JSONRPCRequest request);

	/**
	 * Handle the notification.
	 * @param transportContext {@link McpTransportContext} carrying transport layer
	 * metadata
	 * @param notification the notification JSON object
	 * @return Mono that completes once handling is finished
	 */
	Mono<Void> handleNotification(McpTransportContext transportContext, JSONRPCNotification notification);

}
