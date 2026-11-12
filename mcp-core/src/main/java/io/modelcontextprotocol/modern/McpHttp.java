/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

/**
 * Wire values of the Streamable HTTP transport of the MCP {@code 2026-07-28} revision,
 * shared by the client and server transports.
 *
 * @author Dariusz Jędrzejczyk
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http">Streamable
 * HTTP</a>
 */
public final class McpHttp {

	/** The type of the SSE events that carry JSON-RPC messages. */
	public static final String MESSAGE_EVENT = "message";

	private McpHttp() {
	}

	/**
	 * Header names, and the markers of a Base64-encoded header value.
	 */
	public static final class Headers {

		/** The HTTP {@code Content-Type} header. */
		public static final String CONTENT_TYPE = "Content-Type";

		/** The HTTP {@code Content-Length} header. */
		public static final String CONTENT_LENGTH = "Content-Length";

		/** The HTTP {@code Accept} header. */
		public static final String ACCEPT = "Accept";

		/** The header that mirrors the JSON-RPC method of a request. */
		public static final String MCP_METHOD = "Mcp-Method";

		/**
		 * The header that mirrors the tool or prompt name, or resource URI, of a request.
		 */
		public static final String MCP_NAME = "Mcp-Name";

		/**
		 * The header that mirrors the protocol version in the {@code _meta} of a request.
		 */
		public static final String MCP_PROTOCOL_VERSION = "MCP-Protocol-Version";

		/** The prefix of every {@code Mcp-Param-{Name}} header name. */
		public static final String MCP_PARAM_PREFIX = "Mcp-Param-";

		/** Starts a Base64-encoded {@code Mcp-Name} or {@code Mcp-Param-{Name}} value. */
		public static final String BASE64_VALUE_PREFIX = "=?base64?";

		/** Ends a Base64-encoded {@code Mcp-Name} or {@code Mcp-Param-{Name}} value. */
		public static final String BASE64_VALUE_SUFFIX = "?=";

		private Headers() {
		}

	}

	/**
	 * Media types of request and response bodies.
	 */
	public static final class MediaTypes {

		/** A single JSON-RPC message. */
		public static final String APPLICATION_JSON = "application/json";

		/** A stream of Server-Sent Events, each carrying a JSON-RPC message. */
		public static final String TEXT_EVENT_STREAM = "text/event-stream";

		private MediaTypes() {
		}

	}

}
