/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

class DefaultMcpStatelessServerHandler implements McpStatelessServerHandler {

	private static final Logger logger = LoggerFactory.getLogger(DefaultMcpStatelessServerHandler.class);

	Map<String, McpStatelessRequestHandler<?>> requestHandlers;

	Map<String, McpStatelessNotificationHandler> notificationHandlers;

	public DefaultMcpStatelessServerHandler(Map<String, McpStatelessRequestHandler<?>> requestHandlers,
			Map<String, McpStatelessNotificationHandler> notificationHandlers) {
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	@Override
	public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext transportContext,
			McpSchema.JSONRPCRequest request) {
		McpStatelessRequestHandler<?> requestHandler = this.requestHandlers.get(request.method());
		if (requestHandler == null) {
			return Mono.error(new McpError("Missing handler for request type: " + request.method()));
		}
		return requestHandler.handle(transportContext, request.params())
			.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
			.onErrorResume(t -> {
				McpSchema.JSONRPCResponse.JSONRPCError error;
				if (t instanceof McpError mcpError && mcpError.getJsonRpcError() != null) {
					error = mcpError.getJsonRpcError();
				}
				else {
					error = new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
							t.getMessage(), null);
				}
				return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null, error));
			});
	}

	@Override
	public Mono<Void> handleNotification(McpTransportContext transportContext,
			McpSchema.JSONRPCNotification notification) {
		McpStatelessNotificationHandler notificationHandler = this.notificationHandlers.get(notification.method());
		if (notificationHandler == null) {
			logger.warn("Missing handler for notification type: {}", notification.method());
			return Mono.empty();
		}
		return notificationHandler.handle(transportContext, notification.params());
	}

}
