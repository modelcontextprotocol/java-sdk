package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpTransportContext;
import reactor.core.publisher.Mono;

public interface McpStatelessNotificationHandler {

	Mono<Void> handle(McpTransportContext transportContext, Object params);

}
