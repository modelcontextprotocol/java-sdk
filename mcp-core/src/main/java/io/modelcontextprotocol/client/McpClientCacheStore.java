/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.function.Predicate;

/**
 * Where a client keeps the list and {@code resources/read} results a server marked
 * cacheable with a {@code ttlMs} hint (SEP-2549).
 * <p>
 * The SDK ships an in-memory store and uses it by default; implement this interface to
 * back the cache with a library such as Caffeine, or with a store shared across clients,
 * and register it with {@code McpClient.sync(transport).cacheStore(...)}.
 * <p>
 * The client decides <em>what</em> may be cached and <em>when</em> an entry must go; a
 * store only has to honour those decisions. It MUST be safe for concurrent use, and it
 * MUST NOT return an entry whose TTL has lapsed.
 *
 * @author Sylwester Lachiewicz
 * @see McpClientCacheKey
 */
public interface McpClientCacheStore {

	/**
	 * The in-memory store used when none is configured: bounded at {@code maxEntries},
	 * evicting the oldest entry first.
	 * @param maxEntries the most entries to retain. MUST be positive.
	 */
	static McpClientCacheStore inMemory(int maxEntries) {
		return new InMemoryMcpClientCacheStore(maxEntries, System::currentTimeMillis);
	}

	/**
	 * The in-memory store used when none is configured, bounded at 512 entries.
	 */
	static McpClientCacheStore inMemory() {
		return inMemory(InMemoryMcpClientCacheStore.DEFAULT_MAX_ENTRIES);
	}

	/**
	 * The value cached under {@code key}, or {@code null} when there is none or its TTL
	 * has lapsed.
	 */
	Object get(McpClientCacheKey key);

	/**
	 * Store {@code value} under {@code key} for at most {@code ttlMs} milliseconds,
	 * replacing any entry already there.
	 * @param ttlMs the lifetime in milliseconds. Always positive; the client has already
	 * dropped non-positive hints and clamped excessive ones.
	 */
	void put(McpClientCacheKey key, Object value, long ttlMs);

	/**
	 * Remove every entry whose key matches, called when a change notification invalidates
	 * a group of entries.
	 */
	void removeIf(Predicate<McpClientCacheKey> matcher);

	/**
	 * Remove every entry, called when the client connects to a new session or closes.
	 */
	void clear();

}
