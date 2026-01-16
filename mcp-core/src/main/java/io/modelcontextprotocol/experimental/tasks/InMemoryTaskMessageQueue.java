/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import reactor.core.publisher.Mono;

/**
 * In-memory implementation of {@link TaskMessageQueue}.
 *
 * <p>
 * This implementation stores messages in memory using thread-safe concurrent queues. Each
 * task has its own isolated queue.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This implementation is thread-safe. Queue operations use internal synchronization to
 * ensure atomicity of size checks and modifications during enqueue operations. The
 * internal queue references are managed by this class and should not be accessed directly
 * by external code.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 */
public class InMemoryTaskMessageQueue implements TaskMessageQueue {

	// Use centralized default from TaskDefaults
	private static final int DEFAULT_MAX_SIZE = TaskDefaults.DEFAULT_MAX_QUEUE_SIZE;

	private final Map<String, Queue<QueuedMessage>> queues = new ConcurrentHashMap<>();

	@Override
	public Mono<Void> enqueue(String taskId, QueuedMessage message, Integer maxSize) {
		// Validate maxSize bounds to prevent unbounded memory growth
		if (maxSize != null) {
			if (maxSize < 1) {
				return Mono.error(new IllegalArgumentException("maxSize must be at least 1, got: " + maxSize));
			}
			if (maxSize > TaskDefaults.MAX_ALLOWED_QUEUE_SIZE) {
				return Mono.error(new IllegalArgumentException(
						"maxSize must not exceed " + TaskDefaults.MAX_ALLOWED_QUEUE_SIZE + ", got: " + maxSize));
			}
		}

		return Mono.fromRunnable(() -> {
			Queue<QueuedMessage> queue = queues.computeIfAbsent(taskId, k -> new ConcurrentLinkedQueue<>());

			int effectiveMaxSize = maxSize != null ? maxSize : DEFAULT_MAX_SIZE;

			// Synchronize to make size check + poll + offer atomic
			// This prevents race conditions where concurrent enqueues could exceed
			// maxSize
			synchronized (queue) {
				while (queue.size() >= effectiveMaxSize) {
					queue.poll();
				}
				queue.offer(message);
			}
		});
	}

	@Override
	public Mono<QueuedMessage> dequeue(String taskId) {
		return Mono.fromCallable(() -> {
			Queue<QueuedMessage> queue = queues.get(taskId);
			if (queue == null) {
				return null;
			}
			return queue.poll();
		});
	}

	@Override
	public Mono<List<QueuedMessage>> dequeueAll(String taskId) {
		return Mono.fromCallable(() -> {
			Queue<QueuedMessage> queue = queues.get(taskId);
			if (queue == null) {
				return List.of();
			}

			// Synchronize to prevent race with enqueue() which also syncs on queue
			synchronized (queue) {
				List<QueuedMessage> messages = new ArrayList<>(queue);
				queue.clear();
				return messages;
			}
		});
	}

	/**
	 * Clears all messages for a specific task.
	 * @param taskId the task identifier
	 */
	public void clear(String taskId) {
		queues.remove(taskId);
	}

	@Override
	public Mono<Void> clearTask(String taskId) {
		return Mono.fromRunnable(() -> queues.remove(taskId));
	}

	/**
	 * Clears all queues. Use with caution.
	 */
	public void clearAll() {
		queues.clear();
	}

	@Override
	public Mono<Integer> getQueueSize(String taskId) {
		return Mono.fromCallable(() -> {
			Queue<QueuedMessage> queue = queues.get(taskId);
			return queue != null ? queue.size() : 0;
		});
	}

}
