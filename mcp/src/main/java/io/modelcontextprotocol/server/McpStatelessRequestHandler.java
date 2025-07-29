package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpTransportContext;
import reactor.core.publisher.Mono;

public interface McpStatelessRequestHandler<R> {

	Mono<R> handle(McpTransportContext transportContext, Object params);

}
