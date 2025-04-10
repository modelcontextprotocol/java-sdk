package io.modelcontextprotocol.spec;

import reactor.core.publisher.Mono;

/**
 * Interface for close operations that are asynchronous.
 */
public interface AsyncCloseable {

	/**
	 * Begins the process of closing the resource gracefully, if there is one in progress,
	 * return the existing one.
	 * @return A {@link Mono} that completes when the resource has been closed.
	 */
	Mono<Void> closeGracefully();

	/**
	 * Immediately closes the resource gracefully.
	 */
	default void close() {
		this.closeGracefully().subscribe();
	}

}
