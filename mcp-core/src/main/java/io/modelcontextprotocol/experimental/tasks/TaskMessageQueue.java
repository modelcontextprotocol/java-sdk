/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Interface for queueing messages associated with tasks.
 *
 * <p>
 * The TaskMessageQueue enables bidirectional communication during task execution,
 * supporting scenarios like elicitation or sampling during a long-running task via
 * side-channeling.
 *
 * <h2>Message Types and Routing</h2>
 * <p>
 * Three message types are supported:
 * <ul>
 * <li><b>Request</b> - Server-to-client requests (e.g., elicitation, sampling) that
 * require a response. These are dequeued by the side-channel handler and sent to the
 * client.</li>
 * <li><b>Notification</b> - Asynchronous notifications (e.g., progress updates) that are
 * dequeued and sent to the client without expecting a response.</li>
 * <li><b>Response</b> - Client responses to requests. These are not returned by
 * {@link #dequeue} or {@link #dequeueAll}; instead, they are retrieved exclusively via
 * {@link #waitForResponse}.</li>
 * </ul>
 *
 * <h2>Side-Channeling Flow</h2> <pre>
 * Tool calls createElicitation()
 *   → Sets task to INPUT_REQUIRED
 *   → Enqueues Request
 *   → Blocks on waitForResponse()
 *
 * Client sees INPUT_REQUIRED, calls tasks/result
 *   → Server dequeues Request via dequeue()
 *   → Server sends request to client
 *   → Client responds
 *   → Server enqueues Response
 *   → waitForResponse() returns the response
 *   → Tool continues execution
 * </pre>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 */
public interface TaskMessageQueue {

	/**
	 * Enqueues a message for a task.
	 * @param taskId the task identifier
	 * @param message the message to enqueue (Request, Response, or Notification)
	 * @return a Mono that completes when the message is enqueued
	 */
	Mono<Void> enqueue(String taskId, QueuedMessage message);

	/**
	 * Dequeues the next actionable message for a task.
	 *
	 * <p>
	 * Returns Request or Notification messages that need to be sent to the client.
	 * Response messages are not returned by this method; they are retrieved exclusively
	 * via {@link #waitForResponse}.
	 * @param taskId the task identifier
	 * @return a Mono emitting the next actionable message, or empty if queue is empty
	 */
	Mono<QueuedMessage> dequeue(String taskId);

	/**
	 * Dequeues all actionable messages for a task.
	 *
	 * <p>
	 * Returns Request or Notification messages only. Response messages are retrieved
	 * exclusively via {@link #waitForResponse}.
	 * @param taskId the task identifier
	 * @return a Mono emitting a list of all actionable messages
	 */
	Mono<List<QueuedMessage>> dequeueAll(String taskId);

	/**
	 * Waits for a response with a matching requestId to be enqueued.
	 *
	 * <p>
	 * Used for side-channeling: after enqueueing a Request, call this to block until the
	 * corresponding Response arrives.
	 * @param taskId the task identifier
	 * @param requestId the request ID to match
	 * @param timeout maximum time to wait
	 * @return a Mono emitting the response when it arrives, or error on timeout
	 */
	Mono<QueuedMessage.Response> waitForResponse(String taskId, String requestId, Duration timeout);

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
