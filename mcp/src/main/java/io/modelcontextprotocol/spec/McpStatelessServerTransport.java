package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import reactor.core.publisher.Mono;

public interface McpStatelessServerTransport {

	void setMcpHandler(McpStatelessServerHandler mcpHandler);

	/**
	 * Immediately closes all the transports with connected clients and releases any
	 * associated resources.
	 */
	default void close() {
		this.closeGracefully().subscribe();
	}

	/**
	 * Gracefully closes all the transports with connected clients and releases any
	 * associated resources asynchronously.
	 * @return a {@link Mono<Void>} that completes when the connections have been closed.
	 */
	Mono<Void> closeGracefully();

}
