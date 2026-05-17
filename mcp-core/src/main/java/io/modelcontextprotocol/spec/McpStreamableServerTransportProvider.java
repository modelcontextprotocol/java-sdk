/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import reactor.core.publisher.Mono;

/**
 * The core building block providing the server-side MCP transport for Streamable HTTP
 * servers. Implement this interface to bridge between a particular server-side technology
 * and the MCP server transport layer.
 *
 * <p>
 * The lifecycle of the provider dictates that it be created first, upon application
 * startup, and then passed into either
 * {@link io.modelcontextprotocol.server.McpServer#sync(McpStreamableServerTransportProvider)}
 * or
 * {@link io.modelcontextprotocol.server.McpServer#async(McpStreamableServerTransportProvider)}.
 * As a result of the MCP server creation, the provider will be notified of a
 * {@link McpStreamableServerSession.Factory} which will be used to handle a 1:1
 * communication between a newly connected client and the server using a session concept.
 * The provider's responsibility is to create instances of
 * {@link McpStreamableServerTransport} that the session will utilise during the session
 * lifetime.
 *
 * <p>
 * Finally, the {@link McpStreamableServerTransport}s can be closed in bulk when
 * {@link #close()} or {@link #closeGracefully()} are called as part of the normal
 * application shutdown event. Individual {@link McpStreamableServerTransport}s can also
 * be closed on a per-session basis, where the {@link McpServerSession#close()} or
 * {@link McpServerSession#closeGracefully()} closes the provided transport.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpStreamableServerTransportProvider extends McpServerTransportProviderBase {

	/**
	 * Sets the session factory that will be used to create sessions for new clients. An
	 * implementation of the MCP server MUST call this method before any MCP interactions
	 * take place.
	 * @param sessionFactory the session factory to be used for initiating client sessions
	 */
	void setSessionFactory(McpStreamableServerSession.Factory sessionFactory);

	/**
	 * Sends a notification to all connected clients.
	 * @param method the name of the notification method to be called on the clients
	 * @param params parameters to be sent with the notification
	 * @return a Mono that completes when the notification has been broadcast
	 */
	Mono<Void> notifyClients(String method, Object params);

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
	 * @return a {@link Mono} that completes when the connections have been closed.
	 */
	Mono<Void> closeGracefully();

}
