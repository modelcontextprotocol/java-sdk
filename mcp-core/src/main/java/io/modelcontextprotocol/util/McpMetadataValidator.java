/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.util;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects characters in server-supplied metadata that a reviewer cannot see but that
 * still reach the model.
 * <p>
 * A server advertises a tool through {@code tools/list} with a name, a description and a
 * JSON schema. A host renders that once for approval and then puts the same bytes into
 * the model's context on every later turn. Nothing in MCP requires the rendered view and
 * the delivered bytes to agree, so a code point with no glyph is absent from what a
 * person approves while surviving intact into the model's input.
 * <p>
 * Two classes of character are reported:
 * <ul>
 * <li>The Unicode TAG block, {@code U+E0000} to {@code U+E007F}. No mainstream terminal,
 * chat or IDE assigns it a glyph, and it has no legitimate use in tool metadata.</li>
 * <li>Bidirectional overrides and isolates, {@code U+202A} to {@code U+202E} and
 * {@code U+2066} to {@code U+2069}, which reorder how surrounding text is displayed.</li>
 * </ul>
 * Other invisible format characters are deliberately left alone. Zero-width joiners and
 * non-joiners carry meaning in Indic, Arabic and Persian text and in emoji sequences, so
 * reporting them would fire on legitimate metadata.
 * <p>
 * Nothing is rewritten. A description stripped of concealed characters is still text the
 * server chose, and editing a name would break the calls that use it.
 *
 * @see ToolNameValidator
 */
public final class McpMetadataValidator {

	private static final Logger logger = LoggerFactory.getLogger(McpMetadataValidator.class);

	/**
	 * System property for strict metadata validation. Set to "true" to throw instead of
	 * logging a warning. Default is false (warn only).
	 */
	public static final String STRICT_VALIDATION_PROPERTY = "io.modelcontextprotocol.strictMetadataValidation";

	private McpMetadataValidator() {
	}

	/**
	 * Returns the strict validation setting from the system property.
	 * @return true if strict validation was enabled, false to warn only (default)
	 */
	public static boolean isStrictByDefault() {
		return Boolean.parseBoolean(System.getProperty(STRICT_VALIDATION_PROPERTY));
	}

	/**
	 * Reports concealed characters anywhere in {@code metadata}.
	 * <p>
	 * Strings are scanned directly. Maps and iterables are walked, so a nested JSON
	 * schema is covered without the caller unpacking it. Anything else is ignored.
	 * @param source what the metadata describes, used in the message, such as
	 * {@code "tool 'search'"}
	 * @param metadata the value to scan. May be {@code null}
	 * @param strict if true, throws on a finding; if false, logs a warning only
	 * @throws IllegalArgumentException if a concealed character is found and strict is
	 * true
	 */
	public static void validate(String source, Object metadata, boolean strict) {
		Set<String> found = new LinkedHashSet<>();
		scan(metadata, found);
		if (found.isEmpty()) {
			return;
		}
		String message = "Concealed characters in metadata for " + source + ": " + String.join(", ", found)
				+ ". These are invisible to a reviewer but reach the model.";
		if (strict) {
			throw new IllegalArgumentException(message);
		}
		logger.warn(message);
	}

	private static void scan(Object value, Set<String> found) {
		if (value instanceof String text) {
			text.codePoints()
				.filter(McpMetadataValidator::isConcealed)
				.forEach(codePoint -> found.add(String.format("U+%04X", codePoint)));
		}
		else if (value instanceof Map<?, ?> map) {
			map.forEach((key, entry) -> {
				scan(key, found);
				scan(entry, found);
			});
		}
		else if (value instanceof Iterable<?> items) {
			items.forEach(item -> scan(item, found));
		}
	}

	private static boolean isConcealed(int codePoint) {
		return (codePoint >= 0xE0000 && codePoint <= 0xE007F) || (codePoint >= 0x202A && codePoint <= 0x202E)
				|| (codePoint >= 0x2066 && codePoint <= 0x2069);
	}

}
