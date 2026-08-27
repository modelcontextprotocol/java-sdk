/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Thread-safe client cache for caching MCP list and resource operations according to
 * server-provided {@code ttlMs} hints (SEP-2549).
 *
 * <p>
 * Cached entries are invalidated either when their TTL expires or when corresponding
 * change notifications are received from the server.
 *
 * @author Sylwester Lachiewicz
 */
class McpClientCache {

	sealed interface CacheKey {

	}

	record ListToolsCacheKey(String cursor, Map<String, Object> meta) implements CacheKey {

	}

	record ListPromptsCacheKey(String cursor, Map<String, Object> meta) implements CacheKey {

	}

	record ListResourcesCacheKey(String cursor, Map<String, Object> meta) implements CacheKey {

	}

	record ListResourceTemplatesCacheKey(String cursor, Map<String, Object> meta) implements CacheKey {

	}

	record ReadResourceCacheKey(String uri) implements CacheKey {

	}

	private record CacheEntry<T>(T value, long expiresAtMillis) {

		boolean isExpired(long now) {
			return now >= this.expiresAtMillis;
		}

	}

	private final ConcurrentHashMap<CacheKey, CacheEntry<?>> cache = new ConcurrentHashMap<>();

	private final Supplier<Long> timeProvider;

	McpClientCache() {
		this(System::currentTimeMillis);
	}

	McpClientCache(Supplier<Long> timeProvider) {
		this.timeProvider = timeProvider;
	}

	@SuppressWarnings("unchecked")
	<T> T get(CacheKey key) {
		CacheEntry<?> entry = this.cache.get(key);
		if (entry == null) {
			return null;
		}
		if (entry.isExpired(this.timeProvider.get())) {
			this.cache.remove(key, entry);
			return null;
		}
		return (T) entry.value();
	}

	<T> void put(CacheKey key, T value, Long ttlMs) {
		if (ttlMs != null && ttlMs > 0 && value != null) {
			long expiresAt = this.timeProvider.get() + ttlMs;
			this.cache.put(key, new CacheEntry<>(value, expiresAt));
		}
	}

	void clearTools() {
		this.cache.keySet().removeIf(k -> k instanceof ListToolsCacheKey);
	}

	void clearPrompts() {
		this.cache.keySet().removeIf(k -> k instanceof ListPromptsCacheKey);
	}

	void clearResources() {
		this.cache.keySet()
			.removeIf(k -> k instanceof ListResourcesCacheKey || k instanceof ListResourceTemplatesCacheKey
					|| k instanceof ReadResourceCacheKey);
	}

	void clearResource(String uri) {
		this.cache.remove(new ReadResourceCacheKey(uri));
	}

	void clear() {
		this.cache.clear();
	}

	int size() {
		return this.cache.size();
	}

}
