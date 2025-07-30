package io.modelcontextprotocol.server;

import reactor.core.publisher.Mono;

public interface McpStatelessNotificationHandler {

	Mono<Void> handle(McpTransportContext transportContext, Object params);

}
