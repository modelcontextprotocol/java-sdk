package io.modelcontextprotocol.spec;

import reactor.core.publisher.Mono;

/**
 * @author Jermaine Hua
 */
public interface McpClientTransportProvider {

	/**
	 * Sets the session factory that will be used to create sessions for new clients. An
	 * implementation of the MCP server MUST call this method before any MCP interactions
	 * take place.
	 * @param sessionFactory the session factory to be used for initiating client sessions
	 */
	void setSessionFactory(McpClientSession.Factory sessionFactory);

	/**
	 * Get the active session.
	 * @return active client session
	 */
	McpClientSession getSession();

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
