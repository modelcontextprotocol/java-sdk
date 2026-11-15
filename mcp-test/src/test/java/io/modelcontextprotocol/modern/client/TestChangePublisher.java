/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import reactor.core.publisher.Flux;

/**
 * The changes of a server under test. A test announces the listen stream it is about to
 * open with {@link #nextStream()}, and uses the returned handle to broadcast once that
 * stream is listening, so no change is dropped for arriving early, and to wait until the
 * stream has stopped listening.
 */
final class TestChangePublisher implements McpChangePublisher {

	private final McpChangeFeed feed = new McpChangeFeed();

	private final Queue<Stream> announced = new ConcurrentLinkedQueue<>();

	@Override
	public Flux<ServerChange> changes() {
		return Flux.defer(() -> {
			Stream stream = this.announced.poll();
			if (stream == null) {
				return this.feed.changes();
			}
			return this.feed.changes()
				.doOnSubscribe(subscription -> stream.listening.countDown())
				.doOnCancel(stream.cancelled::countDown);
		});
	}

	/**
	 * Broadcasts {@code change} to the streams that listen now.
	 */
	void broadcast(ServerChange change) {
		this.feed.broadcast(change);
	}

	/**
	 * The next listen stream the server will serve.
	 */
	Stream nextStream() {
		Stream stream = new Stream();
		this.announced.add(stream);
		return stream;
	}

	/**
	 * One listen stream, as the server sees it.
	 */
	final class Stream {

		private final CountDownLatch listening = new CountDownLatch(1);

		private final CountDownLatch cancelled = new CountDownLatch(1);

		/** Broadcasts {@code change} once this stream listens. */
		void broadcastWhenListening(ServerChange change) {
			await(this.listening, "the listen stream to listen");
			broadcast(change);
		}

		/** Broadcasts {@code change} to the streams that listen now. */
		void broadcast(ServerChange change) {
			TestChangePublisher.this.broadcast(change);
		}

		/** Waits until this stream has stopped listening. */
		void awaitCancelled() {
			await(this.cancelled, "the listen stream to be cancelled");
		}

	}

	private static void await(CountDownLatch latch, String what) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("Timed out waiting for " + what);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError(ex);
		}
	}

}
