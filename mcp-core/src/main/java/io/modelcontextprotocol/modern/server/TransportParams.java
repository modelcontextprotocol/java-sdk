/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.util.Assert;

/**
 * The SEP-2243 {@code Mcp-Param-{Name}} headers of one request, decoded. Transports that
 * mirror tool arguments into headers store them in the {@link McpTransportContext} under
 * {@link #KEY}; {@code ToolsFeature} checks them against the {@code tools/call}
 * arguments.
 * <p>
 * Only {@link #NOT_MIRRORED} skips that check. A context without {@link #KEY} reads as
 * {@link #EMPTY}: a request with no headers, so a tool argument marked with
 * {@code x-mcp-header} is rejected unless the transport declares it does not mirror.
 *
 * @author Pratham Goyal
 */
public final class TransportParams {

	/** The {@link McpTransportContext} key the transports store the params under. */
	public static final String KEY = TransportParams.class.getName();

	/** A transport that mirrors no arguments into headers, such as stdio. */
	public static final TransportParams NOT_MIRRORED = new TransportParams(false, Map.of());

	/** A mirroring transport's request without any {@code Mcp-Param-*} header. */
	public static final TransportParams EMPTY = new TransportParams(true, Map.of());

	private static final String HEADER_PREFIX = "mcp-param-";

	private static final String BASE64_PREFIX = "=?base64?";

	private static final String BASE64_SUFFIX = "?=";

	// Base64 needs its padding: java.util.Base64 would accept "SGVsbG8".
	private static final Pattern BASE64 = Pattern
		.compile("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?");

	private final boolean mirrored;

	// Keyed by the lower-cased name; a null value marks a malformed header.
	private final Map<String, String> values;

	private TransportParams(boolean mirrored, Map<String, String> values) {
		this.mirrored = mirrored;
		this.values = values;
	}

	/**
	 * Collects the {@code Mcp-Param-*} entries of the given headers. Header names are
	 * case-insensitive. A value that repeats its header, holds characters a client must
	 * have Base64-encoded, or is not valid {@code =?base64?…?=} is kept as malformed
	 * rather than failing here: only a header the called tool declares rejects the
	 * request.
	 * @param headers every value of every request header, by header name
	 */
	public static TransportParams fromHeaders(Map<String, List<String>> headers) {
		Assert.notNull(headers, "headers must not be null");
		Map<String, String> values = new HashMap<>();
		headers.forEach((name, headerValues) -> {
			if (name == null || name.length() <= HEADER_PREFIX.length()
					|| !name.regionMatches(true, 0, HEADER_PREFIX, 0, HEADER_PREFIX.length())) {
				return;
			}
			String key = name.substring(HEADER_PREFIX.length()).toLowerCase(Locale.ROOT);
			for (String value : headerValues) {
				// A repeated header is ambiguous, whatever its values.
				values.put(key, values.containsKey(key) ? null : decode(value));
			}
		});
		return new TransportParams(true, values);
	}

	/**
	 * The params stored in the context, {@link #EMPTY} if there are none.
	 */
	public static TransportParams from(McpTransportContext context) {
		return context != null && context.get(KEY) instanceof TransportParams params ? params : EMPTY;
	}

	/**
	 * A context answering {@link #KEY} with {@code params} and every other key from
	 * {@code context}, so whatever {@code context} holds under {@link #KEY} is ignored.
	 */
	public static McpTransportContext attach(McpTransportContext context, TransportParams params) {
		Assert.notNull(context, "context must not be null");
		Assert.notNull(params, "params must not be null");
		return key -> KEY.equals(key) ? params : context.get(key);
	}

	/**
	 * Whether the transport mirrors arguments into headers at all; {@code false} only for
	 * {@link #NOT_MIRRORED}.
	 */
	public boolean isMirrored() {
		return this.mirrored;
	}

	/** Whether a {@code Mcp-Param-{name}} header was sent, malformed or not. */
	public boolean contains(String name) {
		return this.values.containsKey(name.toLowerCase(Locale.ROOT));
	}

	/** Whether a {@code Mcp-Param-{name}} header was sent but cannot be read. */
	public boolean isMalformed(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		return this.values.containsKey(key) && this.values.get(key) == null;
	}

	/**
	 * The decoded value of {@code Mcp-Param-{name}}, or {@code null} if the header is
	 * absent or malformed.
	 */
	public String get(String name) {
		return this.values.get(name.toLowerCase(Locale.ROOT));
	}

	/**
	 * Decodes one header value: optional whitespace around it is dropped, a
	 * {@code =?base64?…?=} value is decoded as UTF-8 and anything else is taken
	 * literally. The sentinel is case-sensitive.
	 * @return the decoded value, or {@code null} if it is malformed
	 */
	static String decode(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = trimOws(value);
		if (trimmed.length() >= BASE64_PREFIX.length() + BASE64_SUFFIX.length() && trimmed.startsWith(BASE64_PREFIX)
				&& trimmed.endsWith(BASE64_SUFFIX)) {
			String base64 = trimmed.substring(BASE64_PREFIX.length(), trimmed.length() - BASE64_SUFFIX.length());
			if (!BASE64.matcher(base64).matches()) {
				return null;
			}
			try {
				return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(Base64.getDecoder().decode(base64)))
					.toString();
			}
			catch (CharacterCodingException | IllegalArgumentException ex) {
				return null;
			}
		}
		// Plain values are printable ASCII: anything else needed Base64.
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (c < 0x20 || c > 0x7E) {
				return null;
			}
		}
		return trimmed;
	}

	private static String trimOws(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && isOws(value.charAt(start))) {
			start++;
		}
		while (end > start && isOws(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(start, end);
	}

	private static boolean isOws(char c) {
		return c == ' ' || c == '\t';
	}

}
