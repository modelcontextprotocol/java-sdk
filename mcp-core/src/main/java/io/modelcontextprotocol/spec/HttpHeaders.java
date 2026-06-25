/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

/**
 * Names of HTTP headers in use by MCP HTTP transports.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface HttpHeaders {

	/**
	 * Identifies individual MCP sessions.
	 */
	String MCP_SESSION_ID = "Mcp-Session-Id";

	/**
	 * Identifies events within an SSE Stream.
	 */
	String LAST_EVENT_ID = "Last-Event-ID";

	/**
	 * Identifies the MCP protocol version.
	 */
	String PROTOCOL_VERSION = "MCP-Protocol-Version";

	/**
	 * The HTTP Content-Length header.
	 * @see <a href=
	 * "https://httpwg.org/specs/rfc9110.html#field.content-length">RFC9110</a>
	 */
	String CONTENT_LENGTH = "Content-Length";

	/**
	 * The HTTP Content-Type header.
	 * @see <a href=
	 * "https://httpwg.org/specs/rfc9110.html#field.content-type">RFC9110</a>
	 */
	String CONTENT_TYPE = "Content-Type";

	/**
	 * The HTTP Accept header.
	 * @see <a href= "https://httpwg.org/specs/rfc9110.html#field.accept">RFC9110</a>
	 */
	String ACCEPT = "Accept";

	/**
	 * The HTTP Cache-Control header.
	 * @see <a href=
	 * "https://httpwg.org/specs/rfc9111.html#field.cache-control">RFC9111</a>
	 */
	String CACHE_CONTROL = "Cache-Control";

}
