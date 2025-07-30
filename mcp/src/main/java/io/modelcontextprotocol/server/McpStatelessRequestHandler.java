package io.modelcontextprotocol.server;

import reactor.core.publisher.Mono;

public interface McpStatelessRequestHandler<R> {

	Mono<R> handle(McpTransportContext transportContext, Object params);

}
