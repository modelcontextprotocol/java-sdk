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

import io.modelcontextprotocol.modern.server.McpParamHeaders;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import reactor.util.annotation.Nullable;

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

	private static final String BASE64_PREFIX = "=?base64?";

	private static final String BASE64_SUFFIX = "?=";

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
	@Nullable
	public static String decodeValue(String raw) {
		if (raw.length() >= BASE64_PREFIX.length() + BASE64_SUFFIX.length() && raw.startsWith(BASE64_PREFIX)
				&& raw.endsWith(BASE64_SUFFIX)) {
			String encoded = raw.substring(BASE64_PREFIX.length(), raw.length() - BASE64_SUFFIX.length());
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
	 * Collects the {@code Mcp-Param-{Name}} headers of a request.
	 * <p>
	 * Headers are selected by their {@code Mcp-Param-} prefix, ignoring case. A name that
	 * carries more than one value, including names that differ only in case, is
	 * malformed. Every other value is decoded with {@link #decodeValue(String)}, and
	 * marked malformed when it can't be decoded. Rejecting a malformed header is left to
	 * the tools feature, because only a header that the called tool declares may cause a
	 * rejection.
	 * <p>
	 * As with {@link #decodeValue(String)}, the accessor must return values with their
	 * leading and trailing whitespace already removed.
	 * @param headers the request headers
	 * @return the decoded headers
	 */
	public static McpParamHeaders paramHeaders(HeaderAccessor headers) {
		// The accessor may be case-sensitive, so values are gathered under every name
		// that differs only in case.
		Map<String, Set<String>> names = new LinkedHashMap<>();
		for (String name : headers.getHeaderNames()) {
			if (name.regionMatches(true, 0, McpParamHeaders.HEADER_PREFIX, 0, McpParamHeaders.HEADER_PREFIX.length())) {
				names.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(name);
			}
		}
		McpParamHeaders.Builder builder = McpParamHeaders.builder();
		for (Set<String> variants : names.values()) {
			String paramName = variants.iterator().next().substring(McpParamHeaders.HEADER_PREFIX.length());
			List<String> values = new ArrayList<>();
			for (String name : variants) {
				values.addAll(headers.getHeader(name));
			}
			String decoded = values.size() == 1 ? decodeValue(values.get(0)) : null;
			if (decoded != null) {
				builder.value(paramName, decoded);
			}
			else {
				builder.malformed(paramName);
			}
		}
		return builder.build();
	}

}
