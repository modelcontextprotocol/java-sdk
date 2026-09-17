/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Identifies one entry in an {@link McpClientCacheStore}: the request whose result was
 * cached.
 * <p>
 * Keys are value types with well-behaved {@code equals} and {@code hashCode}, and the
 * {@code _meta} they carry is copied on construction, so a key stays usable as a map key
 * even if the caller mutates the map it passed.
 *
 * @author Sylwester Lachiewicz
 * @see McpClientCacheStore
 */
public sealed interface McpClientCacheKey {

	/**
	 * A {@code tools/list} page.
	 */
	record ListTools(String cursor, Map<String, Object> meta) implements McpClientCacheKey {

		public ListTools {
			meta = snapshot(meta);
		}

	}

	/**
	 * A {@code prompts/list} page.
	 */
	record ListPrompts(String cursor, Map<String, Object> meta) implements McpClientCacheKey {

		public ListPrompts {
			meta = snapshot(meta);
		}

	}

	/**
	 * A {@code resources/list} page.
	 */
	record ListResources(String cursor, Map<String, Object> meta) implements McpClientCacheKey {

		public ListResources {
			meta = snapshot(meta);
		}

	}

	/**
	 * A {@code resources/templates/list} page.
	 */
	record ListResourceTemplates(String cursor, Map<String, Object> meta) implements McpClientCacheKey {

		public ListResourceTemplates {
			meta = snapshot(meta);
		}

	}

	/**
	 * One {@code resources/read} result. Keyed on the {@code _meta} as well as the URI,
	 * because the server's read handler sees {@code _meta} and may branch on it.
	 */
	record ReadResource(String uri, Map<String, Object> meta) implements McpClientCacheKey {

		public ReadResource {
			meta = snapshot(meta);
		}

		public ReadResource(String uri) {
			this(uri, null);
		}

	}

	private static Map<String, Object> snapshot(Map<String, Object> meta) {
		return meta == null ? null : Collections.unmodifiableMap(new HashMap<>(meta));
	}

}
