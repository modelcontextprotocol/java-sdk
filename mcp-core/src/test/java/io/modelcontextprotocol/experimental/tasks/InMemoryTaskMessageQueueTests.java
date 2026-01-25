/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import static io.modelcontextprotocol.experimental.tasks.TaskTestUtils.runConcurrent;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link InMemoryTaskMessageQueue}.
 */
class InMemoryTaskMessageQueueTests {

	private InMemoryTaskMessageQueue messageQueue;

	@BeforeEach
	void setUp() {
		messageQueue = new InMemoryTaskMessageQueue();
	}

	// Helper to create test notifications
	private QueuedMessage.Notification notification(String method) {
		return new QueuedMessage.Notification(method, null);
	}

	// Helper to assert notification method
	private void assertNotificationMethod(QueuedMessage msg, String expectedMethod) {
		assertThat(msg).isInstanceOf(QueuedMessage.Notification.class);
		assertThat(((QueuedMessage.Notification) msg).method()).isEqualTo(expectedMethod);
	}

	@Test
	void testEnqueueThenDequeueReturnsSameMessage() {
		StepVerifier
			.create(messageQueue.enqueue("task-1", notification("test/method")).then(messageQueue.dequeue("task-1")))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "test/method"))
			.verifyComplete();
	}

	@Test
	void testMultipleEnqueuesRespectFifoOrdering() {
		StepVerifier
			.create(messageQueue.enqueue("task-1", notification("method-1"))
				.then(messageQueue.enqueue("task-1", notification("method-2")))
				.then(messageQueue.enqueue("task-1", notification("method-3")))
				.then(messageQueue.dequeue("task-1")))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "method-1"))
			.verifyComplete();

		StepVerifier.create(messageQueue.dequeue("task-1"))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "method-2"))
			.verifyComplete();

		StepVerifier.create(messageQueue.dequeue("task-1"))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "method-3"))
			.verifyComplete();
	}

	@Test
	void testDequeueAllReturnsAllMessagesInOrder() {
		StepVerifier
			.create(messageQueue.enqueue("task-1", notification("method-1"))
				.then(messageQueue.enqueue("task-1", notification("method-2")))
				.then(messageQueue.enqueue("task-1", notification("method-3")))
				.then(messageQueue.dequeueAll("task-1")))
			.consumeNextWith(messages -> {
				assertThat(messages).hasSize(3);
				assertNotificationMethod(messages.get(0), "method-1");
				assertNotificationMethod(messages.get(1), "method-2");
				assertNotificationMethod(messages.get(2), "method-3");
			})
			.verifyComplete();

		// Queue should be empty after dequeueAll
		StepVerifier.create(messageQueue.dequeue("task-1")).verifyComplete();
	}

	@Test
	void testQueueIsolationPerTaskId() {
		StepVerifier
			.create(messageQueue.enqueue("task-1", notification("task1-method"))
				.then(messageQueue.enqueue("task-2", notification("task2-method")))
				.then(messageQueue.dequeue("task-1")))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "task1-method"))
			.verifyComplete();

		StepVerifier.create(messageQueue.dequeue("task-2"))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "task2-method"))
			.verifyComplete();
	}

	@Test
	void testDequeueFromEmptyQueue() {
		// Dequeue from non-existent task should complete empty
		StepVerifier.create(messageQueue.dequeue("nonexistent")).verifyComplete();
	}

	@Test
	void testDequeueAllFromEmptyQueue() {
		// dequeueAll from non-existent task should return empty list
		StepVerifier.create(messageQueue.dequeueAll("nonexistent")).consumeNextWith(messages -> {
			assertThat(messages).isEmpty();
		}).verifyComplete();
	}

	@Test
	void testClearRemovesAllMessagesForTask() {
		messageQueue.enqueue("task-1", notification("method-1"))
			.then(messageQueue.enqueue("task-1", notification("method-2")))
			.block();

		messageQueue.clear("task-1");
		StepVerifier.create(messageQueue.dequeue("task-1")).verifyComplete();
	}

	@Test
	void testClearAllRemovesAllQueues() {
		messageQueue.enqueue("task-1", notification("task1-method"))
			.then(messageQueue.enqueue("task-2", notification("task2-method")))
			.block();

		messageQueue.clearAll();
		StepVerifier.create(messageQueue.dequeue("task-1")).verifyComplete();
		StepVerifier.create(messageQueue.dequeue("task-2")).verifyComplete();
	}

	@Test
	void testActionableMessagesOnlyReturnedByDequeue() {
		// Request and Notification go to actionable queue
		var request = new QueuedMessage.Request("req-1", "sampling/createMessage",
				new McpSchema.CreateMessageRequest(null, null, null, null, null, null, null, null));
		var notification = new QueuedMessage.Notification("notifications/progress", null);

		// Response goes to response queue (only retrieved via waitForResponse)
		var response = new QueuedMessage.Response("req-1",
				new McpSchema.CreateMessageResult(null, null, null, null, null));

		StepVerifier
			.create(messageQueue.enqueue("task-1", request)
				.then(messageQueue.enqueue("task-1", response))
				.then(messageQueue.enqueue("task-1", notification))
				.then(messageQueue.dequeueAll("task-1")))
			.consumeNextWith(messages -> {
				// Only Request and Notification should be returned, not Response
				assertThat(messages).hasSize(2);
				assertThat(messages.get(0)).isInstanceOf(QueuedMessage.Request.class);
				assertThat(messages.get(1)).isInstanceOf(QueuedMessage.Notification.class);
			})
			.verifyComplete();
	}

	// ------------------------------------------
	// waitForResponse Tests
	// ------------------------------------------

	@Test
	void testWaitForResponseReturnsMatchingResponse() {
		var response = new QueuedMessage.Response("req-123",
				new McpSchema.CreateMessageResult(null, null, null, null, null));

		// Enqueue response first
		messageQueue.enqueue("task-1", response).block();

		// Wait for response should return it
		StepVerifier.create(messageQueue.waitForResponse("task-1", "req-123", Duration.ofSeconds(5)))
			.consumeNextWith(res -> {
				assertThat(res.requestId()).isEqualTo("req-123");
			})
			.verifyComplete();
	}

	@Test
	void testWaitForResponseBlocksUntilResponseArrives() {
		AtomicBoolean responseArrived = new AtomicBoolean(false);

		// Start waiting in background
		var waitMono = messageQueue.waitForResponse("task-1", "req-456", Duration.ofSeconds(5))
			.doOnNext(r -> responseArrived.set(true));

		// Enqueue response after a short delay
		Thread delayedEnqueue = new Thread(() -> {
			try {
				Thread.sleep(100);
				var response = new QueuedMessage.Response("req-456", new McpSchema.ElicitResult(null, null, null));
				messageQueue.enqueue("task-1", response).block();
			}
			catch (InterruptedException ignored) {
			}
		});
		delayedEnqueue.start();

		// Verify wait completes with the response
		StepVerifier.create(waitMono).consumeNextWith(res -> {
			assertThat(res.requestId()).isEqualTo("req-456");
		}).verifyComplete();

		assertThat(responseArrived.get()).isTrue();
	}

	@Test
	void testWaitForResponseTimesOut() {
		// Wait for a response that never arrives
		StepVerifier.create(messageQueue.waitForResponse("task-1", "never-arrives", Duration.ofMillis(100)))
			.expectError(TimeoutException.class)
			.verify();
	}

	@Test
	void testWaitForResponseMatchesCorrectRequestId() {
		// Enqueue multiple responses with different IDs
		var response1 = new QueuedMessage.Response("req-aaa",
				new McpSchema.CreateMessageResult(null, null, null, null, null));
		var response2 = new QueuedMessage.Response("req-bbb",
				new McpSchema.CreateMessageResult(null, null, null, null, null));
		var response3 = new QueuedMessage.Response("req-ccc",
				new McpSchema.CreateMessageResult(null, null, null, null, null));

		messageQueue.enqueue("task-1", response1).block();
		messageQueue.enqueue("task-1", response2).block();
		messageQueue.enqueue("task-1", response3).block();

		// Wait for specific response - should only get the matching one
		StepVerifier.create(messageQueue.waitForResponse("task-1", "req-bbb", Duration.ofSeconds(5)))
			.consumeNextWith(res -> {
				assertThat(res.requestId()).isEqualTo("req-bbb");
			})
			.verifyComplete();

		// Other responses should still be in the queue
		StepVerifier.create(messageQueue.waitForResponse("task-1", "req-aaa", Duration.ofSeconds(5)))
			.consumeNextWith(res -> {
				assertThat(res.requestId()).isEqualTo("req-aaa");
			})
			.verifyComplete();
	}

	// ------------------------------------------
	// Concurrency Tests
	// ------------------------------------------

	@Test
	void testConcurrentEnqueueDequeuePreservesConsistency() throws InterruptedException {
		String taskId = "concurrent-task";
		int totalOps = 150; // 100 enqueues + 50 dequeues

		runConcurrent(totalOps, 30, i -> {
			if (i < 100) {
				messageQueue.enqueue(taskId, notification("method-" + i)).block();
			}
			else {
				messageQueue.dequeue(taskId).block();
			}
		});

		// Remaining messages = enqueues - dequeues = 100 - 50 = 50
		var remaining = messageQueue.dequeueAll(taskId).block();
		assertThat(remaining).isNotNull();
		assertThat(remaining).hasSize(50);
	}

	@Test
	void testConcurrentEnqueueToMultipleQueues() throws InterruptedException {
		int numTasks = 10;
		int messagesPerTask = 50;

		runConcurrent(numTasks * messagesPerTask, 20, i -> {
			String taskId = "task-" + (i / messagesPerTask);
			messageQueue.enqueue(taskId, notification("method-" + i)).block();
		});

		// Each task queue should have the right number of messages
		for (int t = 0; t < numTasks; t++) {
			var messages = messageQueue.dequeueAll("task-" + t).block();
			assertThat(messages).hasSize(messagesPerTask);
		}
	}

	// ------------------------------------------
	// Edge Case Tests
	// ------------------------------------------

	@Test
	void testEnqueueAfterClear() {
		messageQueue.enqueue("task-1", notification("method-1")).block();
		messageQueue.clear("task-1");
		messageQueue.enqueue("task-1", notification("method-2")).block();

		StepVerifier.create(messageQueue.dequeue("task-1"))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "method-2"))
			.verifyComplete();
	}

	@Test
	void testClearTaskReactive() {
		// Add some messages
		messageQueue.enqueue("task-1", notification("method-1")).block();
		messageQueue.enqueue("task-1", notification("method-2")).block();

		// Clear using the reactive method
		StepVerifier.create(messageQueue.clearTask("task-1")).verifyComplete();

		// Verify queue is empty
		StepVerifier.create(messageQueue.dequeue("task-1")).verifyComplete();
	}

	@Test
	void testClearTaskAlsoClearsResponseQueue() {
		var response = new QueuedMessage.Response("req-123",
				new McpSchema.CreateMessageResult(null, null, null, null, null));
		messageQueue.enqueue("task-1", notification("method-1")).block();
		messageQueue.enqueue("task-1", response).block();

		messageQueue.clearTask("task-1").block();

		// Both actionable and response queues should be cleared
		StepVerifier.create(messageQueue.dequeue("task-1")).verifyComplete();
		StepVerifier.create(messageQueue.waitForResponse("task-1", "req-123", Duration.ofMillis(50)))
			.expectError(TimeoutException.class)
			.verify();
	}

	@Test
	void testGetQueueSizeIncludesBothQueues() {
		var response = new QueuedMessage.Response("req-123",
				new McpSchema.CreateMessageResult(null, null, null, null, null));
		messageQueue.enqueue("task-1", notification("method-1")).block();
		messageQueue.enqueue("task-1", notification("method-2")).block();
		messageQueue.enqueue("task-1", response).block();

		// Size should include both actionable (2) and response (1) queues
		StepVerifier.create(messageQueue.getQueueSize("task-1")).consumeNextWith(size -> {
			assertThat(size).isEqualTo(3);
		}).verifyComplete();
	}

}
