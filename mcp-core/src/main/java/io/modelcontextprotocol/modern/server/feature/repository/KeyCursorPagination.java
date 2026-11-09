/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature.repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.function.Function;

import io.modelcontextprotocol.modern.McpException;

/**
 * Pages the entries of a sorted map, in ascending key order. The cursor is the opaque,
 * encoded key of the last entry of the previous page, so a page stays correct when
 * entries are added or removed between two requests: the next page starts at the first
 * key after the cursor, whether or not that key still exists.
 *
 * @author Christian Tzolov
 */
final class KeyCursorPagination {

	// Distinguishes cursors produced here from arbitrary base64 input.
	private static final String PREFIX = "k:";

	private KeyCursorPagination() {
	}

	/**
	 * Returns the page of {@code entries} following {@code cursor}, with every value
	 * mapped through {@code item}.
	 * <p>
	 * The page starts with a {@code tailMap} seek to the cursor key rather than a scan
	 * from the first entry, so walking a list of {@code n} entries page by page costs
	 * O(n) overall instead of O(n²/pageSize).
	 * @param entries the entries, keyed by the value the cursor encodes
	 * @param pageSize {@code null} returns everything on one page; any cursor is then
	 * invalid, since this method never produced one
	 * @throws McpException invalid params when the cursor is not one this method produced
	 */
	static <S, D> Page<D> page(NavigableMap<String, S> entries, Function<S, D> item, String cursor, Integer pageSize) {
		if (pageSize == null && cursor != null) {
			throw invalidCursor(cursor);
		}
		NavigableMap<String, S> remaining = cursor != null ? entries.tailMap(decode(cursor), false) : entries;
		List<D> items = new ArrayList<>();
		String last = null;
		boolean more = false;
		for (Map.Entry<String, S> entry : remaining.entrySet()) {
			if (pageSize != null && items.size() == pageSize) {
				more = true;
				break;
			}
			items.add(item.apply(entry.getValue()));
			last = entry.getKey();
		}
		return new Page<>(List.copyOf(items), more ? encode(last) : null);
	}

	static String encode(String lastKey) {
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString((PREFIX + lastKey).getBytes(StandardCharsets.UTF_8));
	}

	static String decode(String cursor) {
		String decoded;
		try {
			decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException e) {
			throw invalidCursor(cursor);
		}
		if (!decoded.startsWith(PREFIX)) {
			throw invalidCursor(cursor);
		}
		return decoded.substring(PREFIX.length());
	}

	private static McpException invalidCursor(String cursor) {
		return McpException.invalidParams("Invalid cursor: " + cursor);
	}

	record Page<D>(List<D> items, String nextCursor) {
	}

}
