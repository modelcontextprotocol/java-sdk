/*
 * Copyright 2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;

/**
 * The caching hint a server attaches to its listing responses — {@code tools/list},
 * {@code prompts/list}, {@code resources/list} and {@code resources/templates/list}
 * (SEP-2549).
 * <p>
 * A {@code resources/read} handler carries its own hint on the
 * {@link McpSchema.ReadResourceResult} it builds and is not covered here.
 *
 * @param ttlMs how long a client may reuse a listing before re-fetching it. Zero disables
 * caching.
 * @param cacheScope whether a shared cache may serve the listing to another principal.
 * @author Sylwester Lachiewicz
 * @see <a href=
 * "https://spec.modelcontextprotocol.io/specification/draft/server/utilities/caching/">Specification:
 * Caching</a>
 */
public record McpListCacheOptions(long ttlMs, McpSchema.CacheScope cacheScope) {

	/**
	 * The default: listings are not cached. Scoped {@code private} so that a gateway
	 * cannot share a listing across principals should a server later attach a TTL without
	 * revisiting the scope.
	 */
	public static final McpListCacheOptions NONE = new McpListCacheOptions(0L, McpSchema.CacheScope.PRIVATE);

	public McpListCacheOptions {
		Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
		Assert.notNull(cacheScope, "cacheScope must not be null");
	}

	/**
	 * The options to use for a listing that may be filtered per caller: a filtered
	 * listing is always scoped {@code private}, whatever scope was configured.
	 * @param filtered whether a per-request filter applies to the listing.
	 */
	McpListCacheOptions forListing(boolean filtered) {
		return filtered && this.cacheScope != McpSchema.CacheScope.PRIVATE
				? new McpListCacheOptions(this.ttlMs, McpSchema.CacheScope.PRIVATE) : this;
	}

	/**
	 * @param ttl how long a client may reuse a listing. MUST NOT be null or negative.
	 * @param cacheScope whether a shared cache may serve the listing to another
	 * principal. Use {@link McpSchema.CacheScope#PUBLIC} only for listings that are
	 * identical for every caller. {@code tools/list} is sent {@code private} regardless
	 * when a tool filter is registered.
	 */
	public static McpListCacheOptions of(Duration ttl, McpSchema.CacheScope cacheScope) {
		Assert.notNull(ttl, "ttl must not be null");
		return new McpListCacheOptions(ttl.toMillis(), cacheScope);
	}

}
