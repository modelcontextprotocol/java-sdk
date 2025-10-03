/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

class DefaultMcpStatelessServerHandler implements McpStatelessServerHandler {

	private static final Logger logger = LoggerFactory.getLogger(DefaultMcpStatelessServerHandler.class);

	Map<String, McpStatelessRequestHandler<?>> requestHandlers;

	Map<String, McpStatelessNotificationHandler> notificationHandlers;

	private final McpSchema.ServerCapabilities serverCapabilities;

	public DefaultMcpStatelessServerHandler(Map<String, McpStatelessRequestHandler<?>> requestHandlers,
			Map<String, McpStatelessNotificationHandler> notificationHandlers,
			McpSchema.ServerCapabilities serverCapabilities) {
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
		this.serverCapabilities = serverCapabilities;
	}

	@Override
	public Mono<JSONRPCResponse> handleRequest(McpTransportContext transportContext, McpSchema.JSONRPCRequest request) {
		McpStatelessRequestHandler<?> requestHandler = this.requestHandlers.get(request.method());
		if (requestHandler == null) {
			// Capability is not declared, but the client is trying to call the method –
			// this is an invalid request.
			if (!isCapabilityDeclaredForMethod(request.method())) {
				JSONRPCError error = new JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
						"Server does not provide " + request.method() + " capability", null);
				return Mono.just(new JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null, error));
			}
			// Capability is declared, but we failed to register a handler – this is a
			// server error.
			return Mono.error(new McpError(new JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
					"Missing handler for request type: " + request.method(), null)));
		}
		return requestHandler.handle(transportContext, request.params())
			.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
			.onErrorResume(t -> {
				JSONRPCError error;
				if (t instanceof McpError mcpError && mcpError.getJsonRpcError() != null) {
					error = mcpError.getJsonRpcError();
				}
				else {
					error = new JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR, t.getMessage(), null);
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

	private boolean isCapabilityDeclaredForMethod(String method) {
		if (this.serverCapabilities == null) {
			return false;
		}

		// Ping is always supported
		if (McpSchema.METHOD_PING.equals(method) || McpSchema.METHOD_INITIALIZE.equals(method))
			return true;

		if (McpSchema.METHOD_TOOLS_LIST.equals(method) || McpSchema.METHOD_TOOLS_CALL.equals(method)) {
			return this.serverCapabilities.tools() != null;
		}
		if (McpSchema.METHOD_RESOURCES_LIST.equals(method) || McpSchema.METHOD_RESOURCES_READ.equals(method)
				|| McpSchema.METHOD_RESOURCES_TEMPLATES_LIST.equals(method)) {
			return this.serverCapabilities.resources() != null;
		}
		if (McpSchema.METHOD_PROMPT_LIST.equals(method) || McpSchema.METHOD_PROMPT_GET.equals(method)) {
			return this.serverCapabilities.prompts() != null;
		}
		if (McpSchema.METHOD_LOGGING_SET_LEVEL.equals(method)) {
			return this.serverCapabilities.logging() != null;
		}
		if (McpSchema.METHOD_COMPLETION_COMPLETE.equals(method)) {
			return this.serverCapabilities.completions() != null;
		}
		return false;
	}

}
