/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.modern.McpHttp.Headers;
import io.modelcontextprotocol.modern.server.McpHeaders;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import org.jspecify.annotations.Nullable;

/**
 * Strict decoder for MCP header values such as {@code Mcp-Name} and
 * {@code Mcp-Param-{Name}}, shared by HTTP transports so they all accept and reject
 * exactly the same values.
 * <p>
 * A value wrapped in the lowercase {@code =?base64?} prefix and {@code ?=} suffix is
 * Base64-encoded UTF-8. Any other value, including one that carries only the prefix or
 * only the suffix, is plain.
 *
 * @author Daniel Garnier-Moiroux
 */
public final class McpHeaderDecoder {

	private McpHeaderDecoder() {
	}

	/**
	 * Decodes a single header value.
	 * <p>
	 * A Base64 value must use the standard alphabet, with padding and zero trailing bits,
	 * and must decode to valid UTF-8. A plain value may contain only visible ASCII
	 * ({@code 0x21}-{@code 0x7E}), space and horizontal tab.
	 * <p>
	 * The value must already have its leading and trailing whitespace removed, as RFC
	 * 9110 requires and servlet containers do.
	 * @param raw the header value as received
	 * @return the decoded value, or {@code null} if the value is malformed
	 */
	@Nullable public static String decodeValue(String raw) {
		if (raw.length() >= Headers.BASE64_VALUE_PREFIX.length() + Headers.BASE64_VALUE_SUFFIX.length()
				&& raw.startsWith(Headers.BASE64_VALUE_PREFIX) && raw.endsWith(Headers.BASE64_VALUE_SUFFIX)) {
			String encoded = raw.substring(Headers.BASE64_VALUE_PREFIX.length(),
					raw.length() - Headers.BASE64_VALUE_SUFFIX.length());
			byte[] bytes;
			try {
				bytes = Base64.getDecoder().decode(encoded);
			}
			catch (IllegalArgumentException ex) {
				return null;
			}
			// Java's decoder accepts missing padding and non-zero trailing bits;
			// re-encoding exposes both.
			if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
				return null;
			}
			try {
				return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes))
					.toString();
			}
			catch (CharacterCodingException ex) {
				return null;
			}
		}
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if ((c < 0x21 || c > 0x7E) && c != ' ' && c != '\t') {
				return null;
			}
		}
		return raw;
	}

	/**
	 * Collects the {@code Mcp-Name} and {@code Mcp-Param-{Name}} headers of a request.
	 * <p>
	 * {@code Mcp-Name} is selected by its exact name and {@code Mcp-Param-{Name}} by its
	 * prefix, both ignoring case, so {@code Mcp-Param-Mcp-Name} is a param header. A name
	 * that carries more than one value, including names that differ only in case, is
	 * malformed. Every other value is decoded with {@link #decodeValue(String)}, and
	 * marked malformed when it can't be decoded. Rejecting a malformed header is left to
	 * whoever compares it with the body: the transport for the methods it validates, and
	 * the feature handling the method otherwise. For instance, only a
	 * {@code Mcp-Param-{Name}} header that the called tool declares may cause a
	 * rejection.
	 * <p>
	 * As with {@link #decodeValue(String)}, the accessor must return values with their
	 * leading and trailing whitespace already removed.
	 * @param headers the request headers
	 * @return the decoded headers
	 */
	public static McpHeaders headers(HeaderAccessor headers) {
		// The accessor may be case-sensitive, so values are gathered under every name
		// that differs only in case.
		Map<String, Set<String>> names = new LinkedHashMap<>();
		for (String name : headers.getHeaderNames()) {
			if (name.equalsIgnoreCase(Headers.MCP_NAME)
					|| name.regionMatches(true, 0, Headers.MCP_PARAM_PREFIX, 0, Headers.MCP_PARAM_PREFIX.length())) {
				names.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(name);
			}
		}
		McpHeaders.Builder builder = McpHeaders.builder();
		for (Set<String> variants : names.values()) {
			List<String> values = new ArrayList<>();
			for (String name : variants) {
				values.addAll(headers.getHeader(name));
			}
			String decoded = values.size() == 1 ? decodeValue(values.get(0)) : null;
			String headerName = variants.iterator().next();
			if (headerName.equalsIgnoreCase(Headers.MCP_NAME)) {
				builder.header(Headers.MCP_NAME, decoded);
			}
			else {
				builder.paramHeader(headerName.substring(Headers.MCP_PARAM_PREFIX.length()), decoded);
			}
		}
		return builder.build();
	}

}
