package io.modelcontextprotocol.spec;

import reactor.core.publisher.Mono;

import java.util.function.Function;

public interface McpStatelessServerTransport {

	void setHandler(Function<McpSchema.JSONRPCRequest, Mono<McpSchema.JSONRPCResponse>> message);

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
