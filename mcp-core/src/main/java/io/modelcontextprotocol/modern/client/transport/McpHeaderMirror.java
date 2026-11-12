/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.McpHttp.Headers;

/**
 * Mirrors an MCP request into the headers of the Streamable HTTP transport, for
 * {@link McpHttpRequestHeaders#forRequest}.
 *
 * @author Dariusz Jędrzejczyk
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http#request-metadata">Streamable
 * HTTP: Request Metadata</a>
 */
final class McpHeaderMirror {

	private McpHeaderMirror() {
	}

	/**
	 * Puts the {@code Mcp-Method}, {@code MCP-Protocol-Version}, {@code Mcp-Name} and
	 * {@code Mcp-Param-{Name}} headers of a request into the given headers, with their
	 * values encoded, replacing any values they had.
	 * @param request the request
	 * @param headers header names mapped to their values
	 */
	static void mirror(McpTransportRequest request, Map<String, List<String>> headers) {
		headers.put(Headers.MCP_METHOD, List.of(request.method()));
		headers.put(Headers.MCP_PROTOCOL_VERSION, List.of(request.protocolVersion()));
		if (request.name() != null) {
			headers.put(Headers.MCP_NAME, List.of(encodeValue(request.name())));
		}
		request.paramHeaders()
			.forEach((name, value) -> headers.put(Headers.MCP_PARAM_PREFIX + name, List.of(encodeValue(value))));
	}

	/**
	 * Encodes a value for {@code Mcp-Name} or {@code Mcp-Param-{Name}}. A value that is
	 * safe as a plain header value is returned as it is; any other is the Base64 of its
	 * UTF-8 bytes between {@code =?base64?} and {@code ?=}.
	 * @param value the value
	 * @return the header value
	 */
	static String encodeValue(String value) {
		if (isPlain(value)) {
			return value;
		}
		return Headers.BASE64_VALUE_PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
				+ Headers.BASE64_VALUE_SUFFIX;
	}

	// Plain values are visible ASCII, space and tab, without surrounding whitespace,
	// which HTTP would strip. A value that looks encoded is encoded so it decodes back
	// to itself. An empty value is encoded too, as intermediaries may drop an empty
	// header.
	private static boolean isPlain(String value) {
		if (value.isEmpty() || isWhitespace(value.charAt(0)) || isWhitespace(value.charAt(value.length() - 1))) {
			return false;
		}
		if (value.startsWith(Headers.BASE64_VALUE_PREFIX) && value.endsWith(Headers.BASE64_VALUE_SUFFIX)) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if ((c < 0x21 || c > 0x7E) && !isWhitespace(c)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t';
	}

}
