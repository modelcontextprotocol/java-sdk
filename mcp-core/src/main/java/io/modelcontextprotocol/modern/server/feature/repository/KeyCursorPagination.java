/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature.repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import io.modelcontextprotocol.modern.McpException;

/**
 * Pages entries that are iterated in ascending key order. The cursor is the opaque,
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
	 * Returns the page of {@code entries} following {@code cursor}, with every entry
	 * mapped through {@code item}.
	 * @param entries the entries in ascending {@code key} order
	 * @param pageSize {@code null} returns everything on one page; any cursor is then
	 * invalid, since this method never produced one
	 * @throws McpException invalid params when the cursor is not one this method produced
	 */
	static <S, D> Page<D> page(Collection<S> entries, Function<S, String> key, Function<S, D> item, String cursor,
			Integer pageSize) {
		if (pageSize == null && cursor != null) {
			throw invalidCursor(cursor);
		}
		String after = cursor != null ? decode(cursor) : null;
		List<D> items = new ArrayList<>();
		String last = null;
		boolean more = false;
		for (S entry : entries) {
			String entryKey = key.apply(entry);
			if (after != null && entryKey.compareTo(after) <= 0) {
				continue;
			}
			if (pageSize != null && items.size() == pageSize) {
				more = true;
				break;
			}
			items.add(item.apply(entry));
			last = entryKey;
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
