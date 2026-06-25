/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

/**
 * Classic implementation of {@link McpServerTransportProviderBase} for a single outgoing
 * stream in bidirectional communication (STDIO and the legacy HTTP SSE).
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpServerTransportProvider extends McpServerTransportProviderBase {

	/**
	 * Sets the session factory that will be used to create sessions for new clients. An
	 * implementation of the MCP server MUST call this method before any MCP interactions
	 * take place.
	 * @param sessionFactory the session factory to be used for initiating client sessions
	 */
	void setSessionFactory(McpServerSession.Factory sessionFactory);

}
