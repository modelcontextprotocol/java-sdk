/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of {@link TaskMessageQueue}.
 *
 * <p>
 * This implementation stores messages in memory using thread-safe concurrent queues. Each
 * task has its own isolated queue for actionable messages (Request, Notification) and a
 * separate queue for Response messages (used by waitForResponse).
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This implementation is thread-safe. Queue operations use internal synchronization to
 * ensure atomicity of operations. The internal queue references are managed by this class
 * and should not be accessed directly by external code.
 *
 * <h2>Message Routing</h2>
 * <p>
 * Messages are routed based on type:
 * <ul>
 * <li><b>Request/Notification</b> - Stored in the actionable queue, retrieved via
 * {@link #dequeue} or {@link #dequeueAll}</li>
 * <li><b>Response</b> - Stored in the response queue, retrieved exclusively via
 * {@link #waitForResponse}</li>
 * </ul>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 */
public class InMemoryTaskMessageQueue implements TaskMessageQueue {

	/**
	 * Queue for actionable messages (Request, Notification) per task.
	 */
	private final Map<String, Queue<QueuedMessage>> actionableQueues = new ConcurrentHashMap<>();

	/**
	 * Queue for response messages per task. These are only retrieved via waitForResponse.
	 */
	private final Map<String, Queue<QueuedMessage.Response>> responseQueues = new ConcurrentHashMap<>();

	@Override
	public Mono<Void> enqueue(String taskId, QueuedMessage message) {
		return Mono.fromRunnable(() -> {
			if (message instanceof QueuedMessage.Response response) {
				// Response messages go to the response queue
				Queue<QueuedMessage.Response> responseQueue = responseQueues.computeIfAbsent(taskId,
						k -> new ConcurrentLinkedQueue<>());
				responseQueue.offer(response);
			}
			else {
				// Request and Notification messages go to the actionable queue
				Queue<QueuedMessage> actionableQueue = actionableQueues.computeIfAbsent(taskId,
						k -> new ConcurrentLinkedQueue<>());
				actionableQueue.offer(message);
			}
		});
	}

	@Override
	public Mono<QueuedMessage> dequeue(String taskId) {
		return Mono.fromCallable(() -> {
			Queue<QueuedMessage> queue = actionableQueues.get(taskId);
			if (queue == null) {
				return null;
			}
			return queue.poll();
		});
	}

	@Override
	public Mono<List<QueuedMessage>> dequeueAll(String taskId) {
		return Mono.fromCallable(() -> {
			Queue<QueuedMessage> queue = actionableQueues.get(taskId);
			if (queue == null) {
				return List.of();
			}

			synchronized (queue) {
				List<QueuedMessage> messages = new ArrayList<>(queue);
				queue.clear();
				return messages;
			}
		});
	}

	@Override
	public Mono<QueuedMessage.Response> waitForResponse(String taskId, String requestId, Duration timeout) {
		return Mono.defer(() -> {
			// First check if response already exists
			QueuedMessage.Response existing = pollResponseForRequest(taskId, requestId);
			if (existing != null) {
				return Mono.just(existing);
			}

			// Poll periodically until response arrives or timeout
			return Flux.interval(Duration.ofMillis(TaskDefaults.RESPONSE_POLL_INTERVAL_MS)).flatMap(tick -> {
				QueuedMessage.Response response = pollResponseForRequest(taskId, requestId);
				return response != null ? Mono.just(response) : Mono.empty();
			})
				.next() // Take first matching response
				.timeout(timeout, Mono.error(new TimeoutException(
						"No response received for request " + requestId + " within " + timeout.toMillis() + "ms")));
		});
	}

	/**
	 * Polls for a response with the matching request ID and removes it from the queue.
	 * @param taskId the task identifier
	 * @param requestId the request ID to match
	 * @return the matching response, or null if not found
	 */
	private QueuedMessage.Response pollResponseForRequest(String taskId, String requestId) {
		Queue<QueuedMessage.Response> queue = responseQueues.get(taskId);
		if (queue == null) {
			return null;
		}

		synchronized (queue) {
			Iterator<QueuedMessage.Response> iterator = queue.iterator();
			while (iterator.hasNext()) {
				QueuedMessage.Response response = iterator.next();
				if (requestId.equals(String.valueOf(response.requestId()))) {
					iterator.remove();
					return response;
				}
			}
		}
		return null;
	}

	/**
	 * Clears all messages for a specific task.
	 * @param taskId the task identifier
	 */
	void clear(String taskId) {
		actionableQueues.remove(taskId);
		responseQueues.remove(taskId);
	}

	@Override
	public Mono<Void> clearTask(String taskId) {
		return Mono.fromRunnable(() -> {
			actionableQueues.remove(taskId);
			responseQueues.remove(taskId);
		});
	}

	/**
	 * Clears all queues. Use with caution.
	 */
	void clearAll() {
		actionableQueues.clear();
		responseQueues.clear();
	}

	@Override
	public Mono<Integer> getQueueSize(String taskId) {
		return Mono.fromCallable(() -> {
			Queue<QueuedMessage> actionable = actionableQueues.get(taskId);
			Queue<QueuedMessage.Response> responses = responseQueues.get(taskId);
			int actionableSize = actionable != null ? actionable.size() : 0;
			int responseSize = responses != null ? responses.size() : 0;
			return actionableSize + responseSize;
		});
	}

}
