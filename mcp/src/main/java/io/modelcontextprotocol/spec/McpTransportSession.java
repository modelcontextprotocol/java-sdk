/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import org.reactivestreams.Publisher;

import java.util.Optional;

/**
 * An abstraction of the session as perceived from the MCP transport layer. Not to be
 * confused with the {@link McpSession} type that operates at the level of the JSON-RPC
 * communication protocol and matches asynchronous responses with previously issued
 * requests.
 *
 * @param <CONNECTION> the resource representing the connection that the transport
 * manages.
 * @author Dariusz Jędrzejczyk
 */
public interface McpTransportSession<CONNECTION> {

	/**
	 * In case of stateful MCP servers, the value is present and contains the String
	 * identifier for the transport-level session.
	 * @return optional session id
	 */
	Optional<String> sessionId();

	/**
	 * Stateful operation that flips the un-initialized state to initialized if this is
	 * the first call. If the transport provides a session id for the communication,
	 * argument should not be null to record the current identifier.
	 * @param sessionId session identifier as provided by the server
	 * @return if successful, this method returns {@code true} and means that a
	 * post-initialization step can be performed
	 */
	boolean markInitialized(String sessionId);

	/**
	 * Adds a resource that this transport session can monitor and dismiss when needed.
	 * @param connection the managed resource
	 */
	void addConnection(CONNECTION connection);

	/**
	 * Called when the resource is terminating by itself and the transport session does
	 * not need to track it anymore.
	 * @param connection the resource to remove from the monitored collection
	 */
	void removeConnection(CONNECTION connection);

	/**
	 * Close and clear the monitored resources. Potentially asynchronous.
	 */
	void close();

	/**
	 * Close and clear the monitored resources in a graceful manner.
	 * @return completes once all resources have been dismissed
	 */
	Publisher<Void> closeGracefully();

}
