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
	 * Mirrors the JSON-RPC method of the request or notification carried in the body.
	 * @see <a href=
	 * "https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#http">MCP
	 * Streamable HTTP transport</a>
	 * @see <a href=
	 * "https://modelcontextprotocol.io/seps/2243-http-standardization">SEP-2243 HTTP
	 * header standardisation</a>
	 */
	String MCP_METHOD = "Mcp-Method";

	/**
	 * Identifies the name or URI of the tool, prompt, or resource referenced by a
	 * request.
	 * @see <a href=
	 * "https://modelcontextprotocol.io/seps/2243-http-standardization">SEP-2243 HTTP
	 * header standardisation</a>
	 */
	String MCP_NAME = "Mcp-Name";

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

	/**
	 * Prefix used for Base64 sentinel-encoded header values per SEP-2243.
	 */
	String BASE64_SENTINEL_PREFIX = "=?base64?";

	/**
	 * Suffix used for Base64 sentinel-encoded header values per SEP-2243.
	 */
	String BASE64_SENTINEL_SUFFIX = "?=";

	/**
	 * Encodes an HTTP header value per SEP-2243. If the value contains non-ASCII
	 * characters, control characters, leading/trailing whitespace, or matches the
	 * sentinel pattern, it is wrapped in Base64 sentinel encoding
	 * ({@code =?base64?...?=}).
	 * @param value the raw string value
	 * @return the header-safe value, encoded if necessary
	 */
	static String encodeHeaderValue(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		if (requiresBase64Encoding(value)) {
			return BASE64_SENTINEL_PREFIX
					+ java.util.Base64.getEncoder()
						.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
					+ BASE64_SENTINEL_SUFFIX;
		}
		return value;
	}

	/**
	 * Decodes an HTTP header value that may be Base64 sentinel-encoded per SEP-2243.
	 * @param headerValue the raw header value from the HTTP request
	 * @return the decoded string value, or the original value if not encoded or malformed
	 */
	static String decodeHeaderValue(String headerValue) {
		if (headerValue == null || headerValue.isEmpty()) {
			return headerValue;
		}
		if (headerValue.startsWith(BASE64_SENTINEL_PREFIX) && headerValue.endsWith(BASE64_SENTINEL_SUFFIX)) {
			String encoded = headerValue.substring(BASE64_SENTINEL_PREFIX.length(),
					headerValue.length() - BASE64_SENTINEL_SUFFIX.length());
			try {
				byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
				return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
			}
			catch (IllegalArgumentException ignored) {
				return headerValue;
			}
		}
		return headerValue;
	}

	private static boolean requiresBase64Encoding(String s) {
		if (s.isEmpty()) {
			return false;
		}
		if (s.charAt(0) == ' ' || s.charAt(0) == '\t' || s.charAt(s.length() - 1) == ' '
				|| s.charAt(s.length() - 1) == '\t') {
			return true;
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 0x20 || c > 0x7E) {
				return true;
			}
		}
		if (s.startsWith(BASE64_SENTINEL_PREFIX) && s.endsWith(BASE64_SENTINEL_SUFFIX)) {
			return true;
		}
		return false;
	}

}
