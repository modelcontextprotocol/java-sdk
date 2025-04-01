package io.modelcontextprotocol.spec;

import reactor.core.publisher.Mono;

/**
 * Interface for close operations that are asynchronous.
 */
public interface AsyncCloseable {

	/**
	 * Begins the process of closing the resource gracefully.
	 * @return A {@link Mono} that completes when the resource has been closed.
	 */
	Mono<Void> closeGracefully();

}
