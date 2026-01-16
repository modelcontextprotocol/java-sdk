/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import static io.modelcontextprotocol.experimental.tasks.TaskTestUtils.runConcurrent;
import static org.assertj.core.api.Assertions.assertThat;

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
			.create(messageQueue.enqueue("task-1", notification("test/method"), null)
				.then(messageQueue.dequeue("task-1")))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "test/method"))
			.verifyComplete();
	}

	@Test
	void testMultipleEnqueuesRespectFifoOrdering() {
		StepVerifier
			.create(messageQueue.enqueue("task-1", notification("method-1"), null)
				.then(messageQueue.enqueue("task-1", notification("method-2"), null))
				.then(messageQueue.enqueue("task-1", notification("method-3"), null))
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
			.create(messageQueue.enqueue("task-1", notification("method-1"), null)
				.then(messageQueue.enqueue("task-1", notification("method-2"), null))
				.then(messageQueue.enqueue("task-1", notification("method-3"), null))
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
	void testMaxSizeEnforcementDropsOldestMessage() {
		// Max size of 2 - oldest should be dropped
		StepVerifier
			.create(messageQueue.enqueue("task-1", notification("method-1"), 2)
				.then(messageQueue.enqueue("task-1", notification("method-2"), 2))
				.then(messageQueue.enqueue("task-1", notification("method-3"), 2))
				.then(messageQueue.dequeueAll("task-1")))
			.consumeNextWith(messages -> {
				assertThat(messages).hasSize(2);
				assertNotificationMethod(messages.get(0), "method-2");
				assertNotificationMethod(messages.get(1), "method-3");
			})
			.verifyComplete();
	}

	@Test
	void testQueueIsolationPerTaskId() {
		StepVerifier
			.create(messageQueue.enqueue("task-1", notification("task1-method"), null)
				.then(messageQueue.enqueue("task-2", notification("task2-method"), null))
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
		messageQueue.enqueue("task-1", notification("method-1"), null)
			.then(messageQueue.enqueue("task-1", notification("method-2"), null))
			.block();

		messageQueue.clear("task-1");
		StepVerifier.create(messageQueue.dequeue("task-1")).verifyComplete();
	}

	@Test
	void testClearAllRemovesAllQueues() {
		messageQueue.enqueue("task-1", notification("task1-method"), null)
			.then(messageQueue.enqueue("task-2", notification("task2-method"), null))
			.block();

		messageQueue.clearAll();
		StepVerifier.create(messageQueue.dequeue("task-1")).verifyComplete();
		StepVerifier.create(messageQueue.dequeue("task-2")).verifyComplete();
	}

	@Test
	void testDifferentMessageTypes() {
		var request = new QueuedMessage.Request("req-1", "sampling/createMessage",
				new McpSchema.CreateMessageRequest(null, null, null, null, null, null, null, null));
		var response = new QueuedMessage.Response("req-1",
				new McpSchema.CreateMessageResult(null, null, null, null, null));
		var notification = new QueuedMessage.Notification("notifications/progress", null);

		StepVerifier
			.create(messageQueue.enqueue("task-1", request, null)
				.then(messageQueue.enqueue("task-1", response, null))
				.then(messageQueue.enqueue("task-1", notification, null))
				.then(messageQueue.dequeueAll("task-1")))
			.consumeNextWith(messages -> {
				assertThat(messages).hasSize(3);
				assertThat(messages.get(0)).isInstanceOf(QueuedMessage.Request.class);
				assertThat(messages.get(1)).isInstanceOf(QueuedMessage.Response.class);
				assertThat(messages.get(2)).isInstanceOf(QueuedMessage.Notification.class);
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
				messageQueue.enqueue(taskId, notification("method-" + i), null).block();
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
			messageQueue.enqueue(taskId, notification("method-" + i), null).block();
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
	void testMaxSizeOne() {
		// Queue with max size 1 should only keep the most recent message
		StepVerifier
			.create(messageQueue.enqueue("task-1", notification("method-1"), 1)
				.then(messageQueue.enqueue("task-1", notification("method-2"), 1))
				.then(messageQueue.dequeueAll("task-1")))
			.consumeNextWith(messages -> {
				assertThat(messages).hasSize(1);
				assertNotificationMethod(messages.get(0), "method-2");
			})
			.verifyComplete();
	}

	@Test
	void testEnqueueAfterClear() {
		messageQueue.enqueue("task-1", notification("method-1"), null).block();
		messageQueue.clear("task-1");
		messageQueue.enqueue("task-1", notification("method-2"), null).block();

		StepVerifier.create(messageQueue.dequeue("task-1"))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "method-2"))
			.verifyComplete();
	}

	// ------------------------------------------
	// Validation Tests
	// ------------------------------------------

	@Test
	void testEnqueueWithMaxSizeBelowMinimumThrows() {
		// maxSize of 0 should throw
		StepVerifier.create(messageQueue.enqueue("task-1", notification("test"), 0))
			.expectError(IllegalArgumentException.class)
			.verify();
	}

	@Test
	void testEnqueueWithMaxSizeAboveMaximumThrows() {
		// maxSize above MAX_ALLOWED_QUEUE_SIZE should throw
		StepVerifier
			.create(messageQueue.enqueue("task-1", notification("test"), TaskDefaults.MAX_ALLOWED_QUEUE_SIZE + 1))
			.expectError(IllegalArgumentException.class)
			.verify();
	}

	@Test
	void testEnqueueWithMaxSizeAtBoundary() {
		// maxSize at exactly MAX_ALLOWED_QUEUE_SIZE should work
		StepVerifier.create(messageQueue.enqueue("task-1", notification("test"), TaskDefaults.MAX_ALLOWED_QUEUE_SIZE))
			.verifyComplete();

		// Verify it was added
		StepVerifier.create(messageQueue.dequeue("task-1"))
			.consumeNextWith(msg -> assertNotificationMethod(msg, "test"))
			.verifyComplete();
	}

	@Test
	void testClearTaskReactive() {
		// Add some messages
		messageQueue.enqueue("task-1", notification("method-1"), null).block();
		messageQueue.enqueue("task-1", notification("method-2"), null).block();

		// Clear using the reactive method
		StepVerifier.create(messageQueue.clearTask("task-1")).verifyComplete();

		// Verify queue is empty
		StepVerifier.create(messageQueue.dequeue("task-1")).verifyComplete();
	}

}
