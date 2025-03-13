package io.modelcontextprotocol.spec;

import java.util.Map;

import reactor.core.publisher.Mono;

public interface McpServerTransportProvider {

	// TODO: Consider adding a ProviderFactory that gets the Session Factory
	void setSessionFactory(McpServerSession.Factory sessionFactory);

	Mono<Void> notifyClients(String method, Map<String, Object> params);

	/**
	 * Closes the transport connection and releases any associated resources.
	 *
	 * <p>
	 * This method ensures proper cleanup of resources when the transport is no longer
	 * needed. It should handle the graceful shutdown of any active connections.
	 * </p>
	 */
	default void close() {
		this.closeGracefully().subscribe();
	}

	/**
	 * Closes the transport connection and releases any associated resources
	 * asynchronously.
	 * @return a {@link Mono<Void>} that completes when the connection has been closed.
	 */
	Mono<Void> closeGracefully();
}
