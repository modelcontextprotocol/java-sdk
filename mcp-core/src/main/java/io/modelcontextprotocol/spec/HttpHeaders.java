/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

/**
 * Names of HTTP headers in use by MCP HTTP transports.
 *
 * @author Dariusz Jędrzejczyk
 * @author Yanming Zhou
 */
public interface HttpHeaders {

	/**
	 * Identifies individual MCP sessions.
	 */
	String MCP_SESSION_ID = "Mcp-Session-Id";

	/**
	 * Identifies the MCP protocol version.
	 */
	String MCP_PROTOCOL_VERSION = "MCP-Protocol-Version";

	/**
	 * Identifies events within an SSE Stream.
	 */
	String LAST_EVENT_ID = "Last-Event-ID";

	/**
	 * Identifies the MCP protocol version.
	 * @deprecated use {@link MCP_PROTOCOL_VERSION} instead
	 */
	@Deprecated(forRemoval = true)
	String PROTOCOL_VERSION = MCP_PROTOCOL_VERSION;

}
