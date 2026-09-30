/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.modelcontextprotocol.util.Assert;

/**
 * The {@link McpClientCacheStore} used when none is configured: a bounded, insertion
 * ordered map guarded by its own monitor.
 *
 * @author Sylwester Lachiewicz
 */
class InMemoryMcpClientCacheStore implements McpClientCacheStore {

	static final int DEFAULT_MAX_ENTRIES = 512;

	private record CacheEntry(Object value, long expiresAtMillis) {

		boolean isExpired(long now) {
			return now >= this.expiresAtMillis;
		}

	}

	/**
	 * Insertion-ordered, so that eviction drops the oldest entry.
	 */
	private final Map<McpClientCacheKey, CacheEntry> cache = new LinkedHashMap<>();

	private final int maxEntries;

	private final Supplier<Long> timeProvider;

	InMemoryMcpClientCacheStore(int maxEntries, Supplier<Long> timeProvider) {
		Assert.isTrue(maxEntries > 0, "maxEntries must be positive");
		Assert.notNull(timeProvider, "timeProvider must not be null");
		this.maxEntries = maxEntries;
		this.timeProvider = timeProvider;
	}

	@Override
	public Object get(McpClientCacheKey key) {
		synchronized (this.cache) {
			CacheEntry entry = this.cache.get(key);
			if (entry == null) {
				return null;
			}
			if (entry.isExpired(this.timeProvider.get())) {
				this.cache.remove(key);
				return null;
			}
			return entry.value();
		}
	}

	@Override
	public void put(McpClientCacheKey key, Object value, long ttlMs) {
		long now = this.timeProvider.get();
		synchronized (this.cache) {
			// Re-insert so that insertion order stays age order.
			this.cache.remove(key);
			this.cache.put(key, new CacheEntry(value, saturatedAdd(now, ttlMs)));
			this.evict(now);
		}
	}

	@Override
	public void removeIf(Predicate<McpClientCacheKey> matcher) {
		synchronized (this.cache) {
			this.cache.keySet().removeIf(matcher);
		}
	}

	@Override
	public void clear() {
		synchronized (this.cache) {
			this.cache.clear();
		}
	}

	int size() {
		synchronized (this.cache) {
			return this.cache.size();
		}
	}

	private void evict(long now) {
		if (this.cache.size() <= this.maxEntries) {
			return;
		}
		this.cache.values().removeIf(entry -> entry.isExpired(now));
		Iterator<McpClientCacheKey> oldestFirst = this.cache.keySet().iterator();
		while (this.cache.size() > this.maxEntries && oldestFirst.hasNext()) {
			oldestFirst.next();
			oldestFirst.remove();
		}
	}

	private static long saturatedAdd(long left, long right) {
		long sum = left + right;
		return ((left ^ sum) & (right ^ sum)) < 0 ? Long.MAX_VALUE : sum;
	}

}
