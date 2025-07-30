package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

public interface McpStatelessServerHandler {

	Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext transportContext,
			McpSchema.JSONRPCRequest request);

	Mono<Void> handleNotification(McpTransportContext transportContext, McpSchema.JSONRPCNotification notification);

}
