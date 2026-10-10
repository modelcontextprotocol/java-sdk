/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;

class McpChangeFeedTests {

	@Test
	void concurrentEmittersLoseNoChanges() throws Exception {
		int threads = 8;
		int perThread = 10_000;
		McpChangeFeed feed = new McpChangeFeed();
		AtomicInteger received = new AtomicInteger();
		Disposable listener = feed.changes().subscribe(change -> received.incrementAndGet());

		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> emitters = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				emitters.add(executor.submit(() -> {
					start.await();
					for (int i = 0; i < perThread; i++) {
						feed.broadcast(new ServerChange.ToolsListChanged());
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> emitter : emitters) {
				emitter.get();
			}
		}
		finally {
			executor.shutdownNow();
			listener.dispose();
		}

		assertThat(received).hasValue(threads * perThread);
	}

}
