/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Collects the events of a sync listen stream, so a test can wait for a number of them.
 */
final class CollectingListener implements McpSyncSubscriptionListener {

	private final List<SubscriptionEvent> events = new ArrayList<>();

	@Override
	public synchronized void onEvent(SubscriptionEvent event) {
		this.events.add(event);
		notifyAll();
	}

	/**
	 * The events, once at least {@code count} have arrived.
	 */
	synchronized List<SubscriptionEvent> await(int count) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (this.events.size() < count) {
			long left = deadline - System.nanoTime();
			if (left <= 0) {
				throw new AssertionError("Received only " + this.events);
			}
			TimeUnit.NANOSECONDS.timedWait(this, left);
		}
		return List.copyOf(this.events);
	}

}
