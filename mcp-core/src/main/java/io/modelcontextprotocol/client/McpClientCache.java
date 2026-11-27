/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Caching policy for MCP list and resource operations, applied on top of an
 * {@link McpClientCacheStore} that does the actual storing (SEP-2549).
 *
 * <p>
 * Entries are invalidated when their TTL expires, when a corresponding change
 * notification arrives from the server, or when the client starts a new session.
 *
 * @author Sylwester Lachiewicz
 */
class McpClientCache {

	/**
	 * Upper bound applied to a server-supplied TTL. Caps how long a client keeps serving
	 * a response the server can no longer invalidate, and keeps the expiry computation
	 * away from {@link Long#MAX_VALUE}.
	 */
	static final long MAX_TTL_MS = Duration.ofHours(24).toMillis();

	private final McpClientCacheStore store;

	private final boolean enabled;

	/**
	 * Incremented by every invalidation. A response that was already in flight when its
	 * generation was invalidated is not stored, otherwise the pre-invalidation value
	 * would be pinned for the whole TTL with no further notification to evict it.
	 */
	private final AtomicLong generation = new AtomicLong();

	/**
	 * Held so that reading the generation and writing to the store are one step, and a
	 * response cannot slip in between an invalidation's two halves.
	 */
	private final Object invalidationLock = new Object();

	McpClientCache() {
		this(true, McpClientCacheStore.inMemory());
	}

	McpClientCache(McpClientCacheStore store) {
		this(true, store);
	}

	/**
	 * @param enabled when false the cache never stores or returns anything, so that a
	 * caller who opted out always observes current server state.
	 */
	McpClientCache(boolean enabled, McpClientCacheStore store) {
		this.enabled = enabled;
		this.store = store;
	}

	/**
	 * The current generation, to be read before a request is sent and passed back to
	 * {@link #put(McpClientCacheKey, Object, Long, long)} when its response arrives.
	 */
	long generation() {
		return this.generation.get();
	}

	@SuppressWarnings("unchecked")
	<T> T get(McpClientCacheKey key) {
		return this.enabled ? (T) this.store.get(key) : null;
	}

	<T> void put(McpClientCacheKey key, T value, Long ttlMs) {
		this.put(key, value, ttlMs, this.generation.get());
	}

	<T> void put(McpClientCacheKey key, T value, Long ttlMs, long generation) {
		if (!this.enabled || ttlMs == null || ttlMs <= 0 || value == null) {
			return;
		}
		synchronized (this.invalidationLock) {
			if (generation != this.generation.get()) {
				return;
			}
			this.store.put(key, value, Math.min(ttlMs, MAX_TTL_MS));
		}
	}

	void clearTools() {
		this.invalidate(k -> k instanceof McpClientCacheKey.ListTools);
	}

	void clearPrompts() {
		this.invalidate(k -> k instanceof McpClientCacheKey.ListPrompts);
	}

	void clearResources() {
		this.invalidate(k -> k instanceof McpClientCacheKey.ListResources
				|| k instanceof McpClientCacheKey.ListResourceTemplates || k instanceof McpClientCacheKey.ReadResource);
	}

	void clearResource(String uri) {
		this.invalidate(k -> k instanceof McpClientCacheKey.ReadResource readKey && readKey.uri().equals(uri));
	}

	void clear() {
		synchronized (this.invalidationLock) {
			this.generation.incrementAndGet();
			this.store.clear();
		}
	}

	private void invalidate(Predicate<McpClientCacheKey> matcher) {
		synchronized (this.invalidationLock) {
			this.generation.incrementAndGet();
			this.store.removeIf(matcher);
		}
	}

}
