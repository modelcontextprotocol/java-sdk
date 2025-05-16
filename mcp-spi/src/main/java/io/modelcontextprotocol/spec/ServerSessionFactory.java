/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

/**
 * Factory for creating server sessions which delegate to a provided 1:1 transport with a
 * connected client.
 */
@FunctionalInterface
public interface ServerSessionFactory {

	/**
	 * Creates a new 1:1 representation of the client-server interaction.
	 * @param sessionTransport the transport to use for communication with the client.
	 * @return a new server session.
	 */
	McpSession create(McpServerTransport sessionTransport);

}
