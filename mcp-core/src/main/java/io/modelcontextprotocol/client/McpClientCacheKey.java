/*
 * Copyright 2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Identifies one entry in an {@link McpClientCacheStore}: the request whose result was
 * cached.
 * <p>
 * Keys are value types with well-behaved {@code equals} and {@code hashCode}, and the
 * {@code _meta} they carry is deep-copied on construction (nested maps and lists), so a
 * key stays usable as a map key even if the caller mutates the map it passed or a nested
 * JSON object or array inside it.
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

	@SuppressWarnings("unchecked")
	private static Map<String, Object> snapshot(Map<String, Object> meta) {
		return meta == null ? null : (Map<String, Object>) deepCopy(meta);
	}

	/**
	 * Copies nested maps and lists into unmodifiable collections, so that mutating a
	 * nested value after construction cannot change the key's hash code. Other values
	 * (strings, numbers, booleans) are immutable and are kept as they are.
	 */
	private static Object deepCopy(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<Object, Object> copy = new LinkedHashMap<>();
			map.forEach((k, v) -> copy.put(k, deepCopy(v)));
			return Collections.unmodifiableMap(copy);
		}
		if (value instanceof List<?> list) {
			List<Object> copy = new ArrayList<>(list.size());
			list.forEach(v -> copy.add(deepCopy(v)));
			return Collections.unmodifiableList(copy);
		}
		return value;
	}

}
