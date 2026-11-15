/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.modern.McpSchema.DiscoverResult;
import org.jspecify.annotations.Nullable;

/**
 * The last {@code server/discover} result of a client, kept for its {@code ttlMs}.
 * Concurrent calls that find nothing fresh may each ask the server. Thread-safe.
 */
final class DiscoverCache {

	private final AtomicReference<Kept> kept = new AtomicReference<>();

	/**
	 * The kept result, or {@code null} if there is none or its {@code ttlMs} has passed.
	 */
	@Nullable DiscoverResult fresh() {
		Kept current = this.kept.get();
		return current != null && System.nanoTime() - current.expiresAtNanos < 0 ? current.result : null;
	}

	/**
	 * Keeps {@code result} if its {@code ttlMs} allows it.
	 */
	DiscoverResult keep(DiscoverResult result) {
		if (result.ttlMs() > 0) {
			this.kept.set(new Kept(result, Deadlines.after(Duration.ofMillis(result.ttlMs()))));
		}
		return result;
	}

	private record Kept(DiscoverResult result, long expiresAtNanos) {
	}

}
