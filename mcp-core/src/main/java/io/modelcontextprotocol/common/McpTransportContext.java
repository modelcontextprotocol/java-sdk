/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Context associated with the transport layer. It allows to add transport-level metadata
 * for use further down the line. Specifically, it can be beneficial to extract HTTP
 * request metadata for use in MCP feature implementations.
 * <p>
 * The context travels in the Reactor context, under {@link #KEY}. On the server side, the
 * transports populate it from the incoming request. On the client side, writing it is the
 * caller's responsibility:
 * <ul>
 * <li>with a synchronous client, configure
 * {@code McpClient.SyncSpec#transportContextProvider(Supplier)}, which is invoked on the
 * calling thread before every operation;
 * <li>with an asynchronous client, write it into the Reactor context once, where the
 * reactive chain starts, using
 * {@code contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))}. Every client
 * call downstream inherits it, so there is no need to repeat it at each call site.
 * </ul>
 *
 * @author Dariusz Jędrzejczyk
 * @author Daniel Garnier-Moiroux
 */
public interface McpTransportContext {

	/**
	 * Key for use in Reactor Context to transport the context to user land. Write the
	 * context under this key to make it visible to the transport, for example
	 * {@code contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))}.
	 */
	String KEY = "MCP_TRANSPORT_CONTEXT";

	/**
	 * An empty, unmodifiable context.
	 */
	@SuppressWarnings("unchecked")
	McpTransportContext EMPTY = new DefaultMcpTransportContext(Collections.EMPTY_MAP);

	/**
	 * Create an unmodifiable context containing the given metadata.
	 * @param metadata the transport metadata
	 * @return the context containing the metadata
	 */
	static McpTransportContext create(Map<String, Object> metadata) {
		return new DefaultMcpTransportContext(metadata);
	}

	/**
	 * Extract a value from the context.
	 * @param key the key under the data is expected
	 * @return the associated value or {@code null} if missing.
	 */
	Object get(String key);

	/**
	 * Stream the entries of this context, in no particular order.
	 * <p>
	 * Unimplemented and throws by default. Implementations used in the modern version of
	 * the spec MUST implement this method.
	 * @return a stream of the key-value pairs held by this context
	 * @since 2.2.0
	 */
	default Stream<Map.Entry<String, Object>> stream() {
		throw new UnsupportedOperationException("%s must implement stream()".formatted(this.getClass().getName()));
	}

	/**
	 * Create a new context holding the entries of this context and those of
	 * {@code other}. When both contexts hold the same key, the value from {@code other}
	 * wins. Neither context is modified. When {@code other} is empty, this context is
	 * returned as is.
	 * <p>
	 * The entries are copied eagerly into a new context, as created by
	 * {@link #create(Map)}, so the result compares equal to a context created directly
	 * from the same entries.
	 * @param other the context whose entries are added, taking precedence over the
	 * entries of this context
	 * @return a new context holding the merged entries
	 * @since 2.2.0
	 */
	default McpTransportContext putAll(McpTransportContext other) {
		Iterator<Map.Entry<String, Object>> entries = other.stream().iterator();
		if (!entries.hasNext()) {
			return this;
		}
		Map<String, Object> merged = new HashMap<>();
		stream().forEach(entry -> merged.put(entry.getKey(), entry.getValue()));
		entries.forEachRemaining(entry -> merged.put(entry.getKey(), entry.getValue()));
		return create(merged);
	}

	/**
	 * Create a new context holding the entries of this context and those of {@code map}.
	 * When both hold the same key, the value from {@code map} wins. Neither this context
	 * nor the map is modified. When {@code map} is empty, this context is returned as is.
	 * <p>
	 * The entries are copied eagerly into a new context, as created by
	 * {@link #create(Map)}, so the result compares equal to a context created directly
	 * from the same entries. As with {@link #create(Map)}, {@code null} values are
	 * rejected.
	 * @param map the entries to add, taking precedence over the entries of this context
	 * @return a new context holding the merged entries
	 * @since 2.2.0
	 */
	default McpTransportContext putAllMap(Map<String, ?> map) {
		if (map.isEmpty()) {
			return this;
		}
		Map<String, Object> merged = new HashMap<>();
		stream().forEach(entry -> merged.put(entry.getKey(), entry.getValue()));
		merged.putAll(map);
		return create(merged);
	}

}
