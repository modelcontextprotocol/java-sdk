/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Interface for queueing messages associated with tasks.
 *
 * <p>
 * The TaskMessageQueue enables bidirectional communication during task execution,
 * supporting scenarios like elicitation or sampling during a long-running task.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 */
public interface TaskMessageQueue {

	/**
	 * Enqueues a message for a task.
	 * @param taskId the task identifier
	 * @param message the message to enqueue
	 * @param maxSize maximum queue size (older messages are dropped if exceeded)
	 * @return a Mono that completes when the message is enqueued
	 */
	Mono<Void> enqueue(String taskId, QueuedMessage message, Integer maxSize);

	/**
	 * Dequeues the next message for a task.
	 * @param taskId the task identifier
	 * @return a Mono emitting the next message, or empty if queue is empty
	 */
	Mono<QueuedMessage> dequeue(String taskId);

	/**
	 * Dequeues all messages for a task.
	 * @param taskId the task identifier
	 * @return a Mono emitting a list of all queued messages
	 */
	Mono<List<QueuedMessage>> dequeueAll(String taskId);

	/**
	 * Clears all messages for a task. Called during task cleanup/expiration.
	 * @param taskId the task identifier
	 * @return a Mono that completes when cleanup is done
	 */
	default Mono<Void> clearTask(String taskId) {
		return Mono.empty();
	}

	/**
	 * Gets the current queue size for a task.
	 *
	 * <p>
	 * This method is useful for monitoring and debugging queue depth during task
	 * execution. Note that the returned size is a snapshot and may change immediately
	 * after the call returns in concurrent scenarios.
	 *
	 * <p>
	 * Default implementation returns 0 (no monitoring support). Implementations that
	 * support monitoring should override this method.
	 * @param taskId the task identifier
	 * @return a Mono emitting the current queue size, or 0 if task has no queue
	 */
	default Mono<Integer> getQueueSize(String taskId) {
		return Mono.just(0);
	}

}
