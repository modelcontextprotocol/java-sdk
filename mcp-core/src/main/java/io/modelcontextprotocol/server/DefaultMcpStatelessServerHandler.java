/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
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
			return Mono.error(McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND)
				.message("Missing handler for request type: " + request.method())
				.build());
		}
		return requestHandler.handle(transportContext, request.params())
			.map(result -> McpSchema.JSONRPCResponse.result(request.id(), result))
			.onErrorResume(t -> {
				McpSchema.JSONRPCResponse.JSONRPCError error;
				if (t instanceof McpError mcpError && mcpError.getJsonRpcError() != null) {
					error = mcpError.getJsonRpcError();
				}
				else {
					error = new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
							t.getMessage());
				}
				return Mono.just(McpSchema.JSONRPCResponse.error(request.id(), error));
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
