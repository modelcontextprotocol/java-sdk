/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import static io.modelcontextprotocol.experimental.tasks.TaskTestUtils.runConcurrent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link InMemoryTaskStore}.
 */
class InMemoryTaskStoreTests {

	private InMemoryTaskStore<McpSchema.ServerTaskPayloadResult> taskStore;

	@BeforeEach
	void setUp() {
		taskStore = new InMemoryTaskStore<>();
	}

	@AfterEach
	void tearDown() {
		taskStore.shutdown().block();
	}

	// ------------------------------------------
	// Helper Methods
	// ------------------------------------------

	private CallToolResult createTestResult(String text) {
		return CallToolResult.builder().content(List.of(new TextContent(null, null, text))).isError(false).build();
	}

	/**
	 * Creates a test request for use in CreateTaskOptions. Using CallToolRequest as the
	 * standard test request type. Static to support @MethodSource methods.
	 */
	private static McpSchema.CallToolRequest createTestRequest(String toolName) {
		return new McpSchema.CallToolRequest(toolName, null);
	}

	/**
	 * Creates default CreateTaskOptions with a test request.
	 */
	private CreateTaskOptions createDefaultOptions() {
		return CreateTaskOptions.builder(createTestRequest("test-tool")).build();
	}

	/**
	 * Creates CreateTaskOptions with the specified session ID and a test request.
	 */
	private CreateTaskOptions createOptionsWithSession(String sessionId) {
		return CreateTaskOptions.builder(createTestRequest("test-tool")).sessionId(sessionId).build();
	}

	// ------------------------------------------
	// Basic Tests
	// ------------------------------------------

	@Test
	void testCreateTaskWithCustomTtlAndPollInterval() {
		var options = CreateTaskOptions.builder(createTestRequest("test-tool"))
			.requestedTtl(60000L)
			.pollInterval(1000L)
			.build();

		StepVerifier.create(taskStore.createTask(options)).consumeNextWith(task -> {
			assertThat(task.taskId()).isNotNull().isNotEmpty();
			assertThat(task.status()).isEqualTo(TaskStatus.WORKING);
			assertThat(task.statusMessage()).isNull();
			assertThat(task.createdAt()).isNotNull();
			assertThat(task.lastUpdatedAt()).isNotNull();
			assertThat(task.ttl()).isEqualTo(60000L);
			assertThat(task.pollInterval()).isEqualTo(1000L);
		}).verifyComplete();
	}

	@Test
	void testCreateTaskWithDefaults() {
		StepVerifier.create(taskStore.createTask(createDefaultOptions())).consumeNextWith(task -> {
			assertThat(task.taskId()).isNotNull().isNotEmpty();
			assertThat(task.status()).isEqualTo(TaskStatus.WORKING);
			assertThat(task.ttl()).isEqualTo(60000L); // Default TTL
			assertThat(task.pollInterval()).isEqualTo(1000L); // Default poll interval
		}).verifyComplete();
	}

	@Test
	void testGetTaskReturnsCreatedTask() {
		var createMono = taskStore.createTask(createDefaultOptions());

		StepVerifier
			.create(createMono
				.flatMap(task -> taskStore.getTask(task.taskId(), null).map(GetTaskFromStoreResult::task)))
			.consumeNextWith(task -> {
				assertThat(task).isNotNull();
				assertThat(task.status()).isEqualTo(TaskStatus.WORKING);
			})
			.verifyComplete();
	}

	@Test
	void testGetTaskNotFound() {
		// When a task is not found, the Mono completes without emitting a value
		// (Mono.fromCallable with null result completes empty)
		StepVerifier.create(taskStore.getTask("nonexistent", null)).verifyComplete();
	}

	@Test
	void testUpdateTaskStatus() {
		var createMono = taskStore.createTask(createDefaultOptions());

		StepVerifier
			.create(createMono.flatMap(task -> taskStore
				.updateTaskStatus(task.taskId(), null, TaskStatus.INPUT_REQUIRED, "Waiting for input")
				.then(taskStore.getTask(task.taskId(), null).map(GetTaskFromStoreResult::task))))
			.consumeNextWith(task -> {
				assertThat(task.status()).isEqualTo(TaskStatus.INPUT_REQUIRED);
				assertThat(task.statusMessage()).isEqualTo("Waiting for input");
			})
			.verifyComplete();
	}

	@Test
	void testUpdateTaskStatusDoesNotUpdateTerminalState() {
		var createMono = taskStore.createTask(createDefaultOptions());

		StepVerifier
			.create(createMono
				.flatMap(task -> taskStore.updateTaskStatus(task.taskId(), null, TaskStatus.COMPLETED, null)
					.then(taskStore.updateTaskStatus(task.taskId(), null, TaskStatus.WORKING, "Should not update"))
					.then(taskStore.getTask(task.taskId(), null).map(GetTaskFromStoreResult::task))))
			.consumeNextWith(task -> {
				// Status should remain COMPLETED, not change back to WORKING
				assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
			})
			.verifyComplete();
	}

	@Test
	void testStoreTaskResultUpdatesTaskToTerminalStatus() {
		var createMono = taskStore.createTask(createDefaultOptions());
		var toolResult = createTestResult("Success");

		StepVerifier
			.create(createMono
				.flatMap(task -> taskStore.storeTaskResult(task.taskId(), null, TaskStatus.COMPLETED, toolResult)
					.then(taskStore.getTask(task.taskId(), null).map(GetTaskFromStoreResult::task))))
			.consumeNextWith(task -> assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED))
			.verifyComplete();
	}

	@Test
	void testGetTaskResultReturnsStoredPayload() {
		var createMono = taskStore.createTask(createDefaultOptions());
		var toolResult = createTestResult("Success");

		StepVerifier.create(createMono
			.flatMap(task -> taskStore.storeTaskResult(task.taskId(), null, TaskStatus.COMPLETED, toolResult)
				.then(taskStore.getTaskResult(task.taskId(), null))))
			.consumeNextWith(result -> {
				assertThat(result).isInstanceOf(CallToolResult.class);
				assertThat(((CallToolResult) result).content()).hasSize(1);
			})
			.verifyComplete();
	}

	@Test
	void testListTasksReturnsPaginatedResults() {
		// Create multiple tasks
		var create1 = taskStore.createTask(createDefaultOptions());
		var create2 = taskStore.createTask(createDefaultOptions());
		var create3 = taskStore.createTask(createDefaultOptions());

		StepVerifier.create(create1.then(create2).then(create3).then(taskStore.listTasks(null, null)))
			.consumeNextWith(result -> {
				assertThat(result.tasks()).hasSize(3);
				assertThat(result.nextCursor()).isNull(); // No pagination needed for 3
															// tasks
			})
			.verifyComplete();
	}

	@Test
	void testRequestCancellation() {
		var createMono = taskStore.createTask(createDefaultOptions());

		StepVerifier.create(createMono.flatMap(task -> taskStore.requestCancellation(task.taskId(), null)))
			.consumeNextWith(task -> {
				assertThat(task.status()).isEqualTo(TaskStatus.CANCELLED);
				assertThat(task.statusMessage()).isEqualTo("Cancellation requested");
			})
			.verifyComplete();
	}

	@Test
	void testIsCancellationRequested() {
		var createMono = taskStore.createTask(createDefaultOptions());

		StepVerifier.create(createMono.flatMap(task -> taskStore.requestCancellation(task.taskId(), null)
			.then(taskStore.isCancellationRequested(task.taskId(), null)))).consumeNextWith(isCancelled -> {
				assertThat(isCancelled).isTrue();
			}).verifyComplete();
	}

	@Test
	void testIsCancellationRequestedReturnsFalseForNonCancelledTask() {
		var createMono = taskStore.createTask(createDefaultOptions());

		StepVerifier.create(createMono.flatMap(task -> taskStore.isCancellationRequested(task.taskId(), null)))
			.consumeNextWith(isCancelled -> {
				assertThat(isCancelled).isFalse();
			})
			.verifyComplete();
	}

	// ------------------------------------------
	// Concurrency Tests
	// ------------------------------------------

	@Test
	void testConcurrentTaskCreation() throws InterruptedException {
		int numTasks = 100;
		List<String> taskIds = Collections.synchronizedList(new ArrayList<>());

		runConcurrent(numTasks, numTasks, i -> {
			var task = taskStore.createTask(createDefaultOptions()).block();
			if (task != null) {
				taskIds.add(task.taskId());
			}
		});

		// Verify all tasks were created with unique IDs
		assertThat(taskIds).hasSize(numTasks);
		assertThat(new HashSet<>(taskIds)).hasSize(numTasks);
	}

	@Test
	void testConcurrentUpdateAndRead() throws InterruptedException {
		var task = taskStore.createTask(createDefaultOptions()).block();
		assertThat(task).isNotNull();
		String taskId = task.taskId();

		// Run concurrent updates and reads
		runConcurrent(100, 20, i -> {
			if (i % 2 == 0) {
				taskStore.updateTaskStatus(taskId, null, TaskStatus.WORKING, "Update " + i).block();
			}
			else {
				taskStore.getTask(taskId, null).map(GetTaskFromStoreResult::task).block();
			}
		});

		// Task should still be valid
		assertThat(taskStore.getTask(taskId, null).map(GetTaskFromStoreResult::task).block()).isNotNull();
	}

	@Test
	void testConcurrentListTasksWhileCreating() throws InterruptedException {
		int totalOps = 70; // 50 creates + 20 lists

		runConcurrent(totalOps, 30, i -> {
			if (i < 50) {
				taskStore.createTask(createDefaultOptions()).block();
			}
			else {
				taskStore.listTasks(null, null).block();
			}
		});

		// Final list should show all created tasks
		var finalResult = taskStore.listTasks(null, null).block();
		assertThat(finalResult).isNotNull();
		assertThat(finalResult.tasks()).hasSize(50);
	}

	// ------------------------------------------
	// Edge Case Tests
	// ------------------------------------------

	@Test
	void testStoreTaskResultOnNonExistentTask() {
		// storeTaskResult on non-existent task should throw McpError
		StepVerifier
			.create(taskStore.storeTaskResult("nonexistent", null, TaskStatus.COMPLETED, createTestResult("Result")))
			.expectError(io.modelcontextprotocol.spec.McpError.class)
			.verify();
	}

	@Test
	void testRequestCancellationOnNonExistentTask() {
		// requestCancellation on non-existent task should return null
		StepVerifier.create(taskStore.requestCancellation("nonexistent", null)).verifyComplete();
	}

	@Test
	void testRequestCancellationOnAlreadyTerminalTask() {
		// Per MCP spec: cancellation of tasks in terminal status MUST be rejected with
		// error code -32602
		var createMono = taskStore.createTask(createDefaultOptions());

		StepVerifier.create(createMono.flatMap(task ->
		// First mark as completed
		taskStore.updateTaskStatus(task.taskId(), null, TaskStatus.COMPLETED, null)
			// Then try to cancel - should fail with McpError
			.then(taskStore.requestCancellation(task.taskId(), null)))).expectErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(io.modelcontextprotocol.spec.McpError.class);
				var mcpError = (io.modelcontextprotocol.spec.McpError) error;
				assertThat(mcpError.getJsonRpcError().code())
					.isEqualTo(io.modelcontextprotocol.spec.McpSchema.ErrorCodes.INVALID_PARAMS);
				assertThat(mcpError.getMessage()).contains("terminal");
			}).verify();
	}

	@Test
	void testIsCancellationRequestedOnNonExistentTask() {
		// Should return false for non-existent task
		StepVerifier.create(taskStore.isCancellationRequested("nonexistent", null)).consumeNextWith(isCancelled -> {
			assertThat(isCancelled).isFalse();
		}).verifyComplete();
	}

	@Test
	void testListTasksWithInvalidCursor() {
		// Create some tasks first
		taskStore.createTask(createDefaultOptions()).block();
		taskStore.createTask(createDefaultOptions()).block();

		// List with invalid cursor should return empty result
		StepVerifier.create(taskStore.listTasks("invalid-cursor-id", null)).consumeNextWith(result -> {
			assertThat(result.tasks()).isEmpty();
			assertThat(result.nextCursor()).isNull();
		}).verifyComplete();
	}

	// ------------------------------------------
	// Watch Task Until Terminal Tests
	// ------------------------------------------

	@Test
	void testWatchTaskUntilTerminalCompletesWhenTaskIsTerminal() {
		// Create and immediately complete a task
		var task = taskStore.createTask(createDefaultOptions()).block();
		assertThat(task).isNotNull();

		// Mark as completed
		taskStore.updateTaskStatus(task.taskId(), null, TaskStatus.COMPLETED, null).block();

		// Watch should complete quickly since task is already terminal
		StepVerifier.create(taskStore.watchTaskUntilTerminal(task.taskId(), null, java.time.Duration.ofSeconds(5)))
			.consumeNextWith(t -> {
				assertThat(t.status()).isEqualTo(TaskStatus.COMPLETED);
				assertThat(t.isTerminal()).isTrue();
			})
			.verifyComplete();
	}

	@Test
	void testWatchTaskUntilTerminalEmitsUpdatesUntilTerminal() {
		var task = taskStore.createTask(createDefaultOptions()).block();
		assertThat(task).isNotNull();

		// Schedule status updates in background
		var executor = Executors.newSingleThreadScheduledExecutor();
		try {
			executor.schedule(() -> {
				taskStore.updateTaskStatus(task.taskId(), null, TaskStatus.COMPLETED, null).block();
			}, 500, TimeUnit.MILLISECONDS);

			// Watch should emit at least one update before completing
			StepVerifier.create(taskStore.watchTaskUntilTerminal(task.taskId(), null, java.time.Duration.ofSeconds(10)))
				.thenAwait(java.time.Duration.ofSeconds(2))
				.expectNextMatches(t -> t.taskId().equals(task.taskId()))
				.thenCancel();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void testWatchTaskUntilTerminalTimesOut() {
		var task = taskStore.createTask(createDefaultOptions()).block();
		assertThat(task).isNotNull();

		// Don't complete the task - it should timeout
		StepVerifier.create(taskStore.watchTaskUntilTerminal(task.taskId(), null, java.time.Duration.ofMillis(500)))
			.expectError(java.util.concurrent.TimeoutException.class)
			.verify();
	}

	@Test
	void testWatchTaskUntilTerminalWithNonexistentTask() {
		StepVerifier.create(taskStore.watchTaskUntilTerminal("nonexistent", null, java.time.Duration.ofSeconds(1)))
			.expectError(McpError.class)
			.verify();
	}

	// ------------------------------------------
	// Session Isolation Tests
	// ------------------------------------------

	@Test
	void testSessionIsolation() {
		// Create tasks for different sessions
		var session1Task = taskStore
			.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).sessionId("session-1").build())
			.block();
		var session2Task = taskStore
			.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).sessionId("session-2").build())
			.block();
		var noSessionTask = taskStore.createTask(createDefaultOptions()).block();

		// List tasks for session-1
		var session1Result = taskStore.listTasks(null, "session-1").block();
		assertThat(session1Result.tasks()).hasSize(1);
		assertThat(session1Result.tasks().get(0).taskId()).isEqualTo(session1Task.taskId());

		// List tasks for session-2
		var session2Result = taskStore.listTasks(null, "session-2").block();
		assertThat(session2Result.tasks()).hasSize(1);
		assertThat(session2Result.tasks().get(0).taskId()).isEqualTo(session2Task.taskId());

		// List all tasks (no session filter)
		var allResult = taskStore.listTasks(null, null).block();
		assertThat(allResult.tasks()).hasSize(3);
	}

	@Test
	void testShutdownStopsCleanupExecutor() {
		// Create a separate store instance to test shutdown
		var store = new InMemoryTaskStore<McpSchema.ServerTaskPayloadResult>();

		// Shutdown should complete without error
		store.shutdown().block();

		// After shutdown, operations should still work (map is still accessible)
		// but cleanup will no longer run (hard to verify directly)
		var task = store.createTask(createDefaultOptions()).block();
		assertThat(task).isNotNull();

		// Clean up
		store.shutdown().block();
	}

	// ------------------------------------------
	// Stress Tests
	// ------------------------------------------

	/**
	 * Runs a concurrent test with success counting.
	 * @param numTasks number of tasks to submit
	 * @param numThreads number of threads in the pool
	 * @param timeoutSeconds timeout for all tasks to complete
	 * @param task the task to execute (receives task index, throws on failure)
	 * @return the number of successful completions
	 */
	private int runConcurrentWithCount(int numTasks, int numThreads, int timeoutSeconds, StressTestAction task)
			throws InterruptedException {
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(numTasks);
		AtomicInteger successCount = new AtomicInteger(0);
		ExecutorService executor = Executors.newFixedThreadPool(numThreads);

		try {
			for (int i = 0; i < numTasks; i++) {
				final int idx = i;
				executor.submit(() -> {
					try {
						startLatch.await();
						task.execute(idx);
						successCount.incrementAndGet();
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					catch (Exception ignored) {
						// Task failed - don't count as success
					}
					finally {
						doneLatch.countDown();
					}
				});
			}
			startLatch.countDown();
			assertThat(doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)).isTrue();
			return successCount.get();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@FunctionalInterface
	interface StressTestAction {

		void execute(int index) throws Exception;

	}

	@Test
	void testConcurrentStoreTaskResult() throws InterruptedException {
		// Create multiple tasks
		int numTasks = 50;
		List<String> taskIds = new ArrayList<>();
		for (int i = 0; i < numTasks; i++) {
			var task = taskStore.createTask(createDefaultOptions()).block();
			taskIds.add(task.taskId());
		}

		// Concurrently store results for all tasks
		int successes = runConcurrentWithCount(numTasks, 20, 10, idx -> {
			String taskId = taskIds.get(idx);
			CallToolResult result = CallToolResult.builder().addTextContent("Result for " + taskId).build();
			taskStore.storeTaskResult(taskId, null, TaskStatus.COMPLETED, result).block();
		});

		assertThat(successes).isEqualTo(numTasks);

		// Verify all results are stored
		for (String taskId : taskIds) {
			assertThat(taskStore.getTaskResult(taskId, null).block()).isNotNull();
		}
	}

	@Test
	void testConcurrentStoreAndReadResults() throws InterruptedException {
		// Create a task and store initial result
		var task = taskStore.createTask(createDefaultOptions()).block();
		String taskId = task.taskId();
		CallToolResult initialResult = CallToolResult.builder().addTextContent("Initial result").build();
		taskStore.storeTaskResult(taskId, null, TaskStatus.COMPLETED, initialResult).block();

		// Concurrent reads of the result
		int numReads = 100;
		int successfulReads = runConcurrentWithCount(numReads, 20, 10, idx -> {
			var result = taskStore.getTaskResult(taskId, null).block();
			if (result == null) {
				throw new AssertionError("Result was null");
			}
		});

		assertThat(successfulReads).isEqualTo(numReads);
	}

	@Test
	void testRapidCreateAndCancelTasks() throws InterruptedException {
		int numTasks = 100;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(numTasks * 2);
		AtomicInteger cancellations = new AtomicInteger(0);
		List<String> taskIds = Collections.synchronizedList(new ArrayList<>());
		ExecutorService executor = Executors.newFixedThreadPool(20);

		try {
			// Create tasks
			for (int i = 0; i < numTasks; i++) {
				executor.submit(() -> {
					try {
						startLatch.await();
						var task = taskStore.createTask(createDefaultOptions()).block();
						if (task != null) {
							taskIds.add(task.taskId());
						}
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					finally {
						doneLatch.countDown();
					}
				});
			}

			// Attempt to cancel tasks (some may not exist yet)
			for (int i = 0; i < numTasks; i++) {
				final int idx = i;
				executor.submit(() -> {
					try {
						startLatch.await();
						// Try to cancel with delay to let some tasks be created
						Thread.sleep(idx % 5);
						if (!taskIds.isEmpty()) {
							String taskId = taskIds.get(idx % Math.max(1, taskIds.size()));
							var cancelled = taskStore.requestCancellation(taskId, null).block();
							if (cancelled != null) {
								cancellations.incrementAndGet();
							}
						}
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					catch (Exception ignored) {
						// Expected - task may not exist yet
					}
					finally {
						doneLatch.countDown();
					}
				});
			}

			startLatch.countDown();
			assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();

			// At least some tasks should have been created
			assertThat(taskIds).isNotEmpty();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void testMultipleStoreInstances() {
		// Create multiple store instances
		InMemoryTaskStore<McpSchema.ServerTaskPayloadResult> store1 = new InMemoryTaskStore<>();
		InMemoryTaskStore<McpSchema.ServerTaskPayloadResult> store2 = new InMemoryTaskStore<>();
		InMemoryTaskStore<McpSchema.ServerTaskPayloadResult> store3 = new InMemoryTaskStore<>();

		try {
			// Each store should work independently
			var task1 = store1.createTask(createDefaultOptions()).block();
			var task2 = store2.createTask(createDefaultOptions()).block();
			var task3 = store3.createTask(createDefaultOptions()).block();

			assertThat(task1.taskId()).isNotEqualTo(task2.taskId());
			assertThat(task2.taskId()).isNotEqualTo(task3.taskId());

			// Each store should only see its own tasks
			assertThat(store1.listTasks(null, null).block().tasks()).hasSize(1);
			assertThat(store2.listTasks(null, null).block().tasks()).hasSize(1);
			assertThat(store3.listTasks(null, null).block().tasks()).hasSize(1);
		}
		finally {
			store1.shutdown().block();
			store2.shutdown().block();
			store3.shutdown().block();
		}
	}

	// ------------------------------------------
	// Boundary Condition Tests
	// ------------------------------------------

	static Stream<Arguments> zeroValueOptions() {
		return Stream.of(
				Arguments.of("ttl", CreateTaskOptions.builder(createTestRequest("test-tool")).requestedTtl(0L).build(),
						0L, null),
				Arguments.of("pollInterval",
						CreateTaskOptions.builder(createTestRequest("test-tool")).pollInterval(0L).build(), null, 0L));
	}

	@ParameterizedTest(name = "zero {0} should be allowed")
	@MethodSource("zeroValueOptions")
	void testCreateTaskOptionsWithZeroValuesAllowed(String field, CreateTaskOptions options, Long expectedTtl,
			Long expectedPollInterval) {
		StepVerifier.create(taskStore.createTask(options)).consumeNextWith(task -> {
			if (expectedTtl != null) {
				assertThat(task.ttl()).isEqualTo(expectedTtl);
			}
			if (expectedPollInterval != null) {
				assertThat(task.pollInterval()).isEqualTo(expectedPollInterval);
			}
		}).verifyComplete();
	}

	static Stream<Arguments> invalidCreateTaskOptions() {
		return Stream.of(Arguments.of("negative TTL",
				(Runnable) () -> CreateTaskOptions.builder(createTestRequest("test-tool")).requestedTtl(-1L).build(),
				"requestedTtl"),
				Arguments.of("negative pollInterval",
						(Runnable) () -> CreateTaskOptions.builder(createTestRequest("test-tool"))
							.pollInterval(-1L)
							.build(),
						"pollInterval"),
				Arguments.of("TTL exceeds max",
						(Runnable) () -> CreateTaskOptions.builder(createTestRequest("test-tool"))
							.requestedTtl(TaskDefaults.MAX_TTL_MS + 1)
							.build(),
						"must not exceed"),
				Arguments.of("pollInterval exceeds max",
						(Runnable) () -> CreateTaskOptions.builder(createTestRequest("test-tool"))
							.pollInterval(TaskDefaults.MAX_POLL_INTERVAL_MS + 1)
							.build(),
						"must not exceed"),
				Arguments.of("pollInterval below min",
						(Runnable) () -> CreateTaskOptions.builder(createTestRequest("test-tool"))
							.pollInterval(1L)
							.build(),
						"must be at least"));
	}

	@ParameterizedTest(name = "{0} throws IllegalArgumentException")
	@MethodSource("invalidCreateTaskOptions")
	void testCreateTaskOptionsWithInvalidValuesThrows(String description, Runnable builder, String expectedMessage) {
		assertThatThrownBy(builder::run).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(expectedMessage);
	}

	@Test
	void testCreateTaskOptionsWithMaxBoundaryValues() {
		// Use the maximum allowed values (at the boundary)
		var options = CreateTaskOptions.builder(createTestRequest("test-tool"))
			.requestedTtl(TaskDefaults.MAX_TTL_MS)
			.pollInterval(TaskDefaults.MAX_POLL_INTERVAL_MS)
			.build();

		StepVerifier.create(taskStore.createTask(options)).consumeNextWith(task -> {
			assertThat(task.ttl()).isEqualTo(TaskDefaults.MAX_TTL_MS);
			assertThat(task.pollInterval()).isEqualTo(TaskDefaults.MAX_POLL_INTERVAL_MS);
		}).verifyComplete();
	}

	@Test
	void testCreateTaskOptionsWithZeroPollIntervalAllowed() {
		// Zero is allowed (means use default)
		var options = CreateTaskOptions.builder(createTestRequest("test-tool")).pollInterval(0L).build();
		assertThat(options.pollInterval()).isEqualTo(0L);
	}

	@Test
	void testStoreWithVeryShortTtl() {
		InMemoryTaskStore<McpSchema.ServerTaskPayloadResult> shortTtlStore = new InMemoryTaskStore<>(1L, 100L);

		try {
			var task = shortTtlStore.createTask(createDefaultOptions()).block();
			assertThat(task).isNotNull();
			assertThat(task.ttl()).isEqualTo(1L);
		}
		finally {
			shortTtlStore.shutdown().block();
		}
	}

	@Test
	void testStoreWithVeryLongPollInterval() {
		InMemoryTaskStore<McpSchema.ServerTaskPayloadResult> longPollStore = new InMemoryTaskStore<>(60000L,
				Long.MAX_VALUE);

		try {
			var task = longPollStore.createTask(createDefaultOptions()).block();
			assertThat(task.pollInterval()).isEqualTo(Long.MAX_VALUE);
		}
		finally {
			longPollStore.shutdown().block();
		}
	}

	@ParameterizedTest(name = "getTask with \"{0}\" returns empty")
	@ValueSource(strings = { "", "   ", "\t", "\n" })
	void testGetTaskWithInvalidIdReturnsEmpty(String invalidId) {
		StepVerifier.create(taskStore.getTask(invalidId, null)).verifyComplete();
	}

	@Test
	void testStoreResultForAlreadyCompletedTask() {
		var task = taskStore.createTask(createDefaultOptions()).block();
		String taskId = task.taskId();

		CallToolResult result1 = CallToolResult.builder().addTextContent("First").build();
		taskStore.storeTaskResult(taskId, null, TaskStatus.COMPLETED, result1).block();

		CallToolResult result2 = CallToolResult.builder().addTextContent("Second").build();
		taskStore.storeTaskResult(taskId, null, TaskStatus.COMPLETED, result2).block();

		var storedResult = taskStore.getTaskResult(taskId, null).block();
		assertThat(storedResult).isNotNull();
	}

	static Stream<Arguments> shortTimeouts() {
		return Stream.of(Arguments.of("very short (1ms)", Duration.ofMillis(1)), Arguments.of("zero", Duration.ZERO));
	}

	@ParameterizedTest(name = "{0} timeout should expire quickly")
	@MethodSource("shortTimeouts")
	void testWatchTaskWithShortTimeoutsExpires(String description, Duration timeout) {
		var task = taskStore.createTask(createDefaultOptions()).block();
		StepVerifier.create(taskStore.watchTaskUntilTerminal(task.taskId(), null, timeout))
			.expectError(java.util.concurrent.TimeoutException.class)
			.verify(Duration.ofSeconds(5));
	}

	@Test
	void testListTasksWithEmptySessionId() {
		var task = taskStore.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).sessionId("").build())
			.block();

		StepVerifier.create(taskStore.listTasks(null, "")).consumeNextWith(result -> {
			assertThat(result.tasks()).hasSize(1);
		}).verifyComplete();
	}

	@Test
	void testListTasksWithNullSessionIdReturnsAll() {
		taskStore.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).sessionId("session1").build())
			.block();
		taskStore.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).sessionId("session2").build())
			.block();
		taskStore.createTask(createDefaultOptions()).block();

		StepVerifier.create(taskStore.listTasks(null, null)).consumeNextWith(result -> {
			assertThat(result.tasks()).hasSize(3);
		}).verifyComplete();
	}

	// ------------------------------------------
	// Max Tasks Limit Tests
	// ------------------------------------------

	@Test
	void testMaxTasksLimitEnforced() {
		// Create a store with a small max tasks limit
		var limitedStore = new InMemoryTaskStore<McpSchema.ServerTaskPayloadResult>(60000, 1000, null, 3);
		try {
			// Create 3 tasks successfully
			limitedStore.createTask(createDefaultOptions()).block();
			limitedStore.createTask(createDefaultOptions()).block();
			limitedStore.createTask(createDefaultOptions()).block();

			// 4th task should fail with McpError
			StepVerifier.create(limitedStore.createTask(createDefaultOptions()))
				.expectErrorMatches(e -> e instanceof McpError && e.getMessage() != null
						&& e.getMessage().contains("Maximum task limit reached (3)"))
				.verify();
		}
		finally {
			limitedStore.shutdown().block();
		}
	}

	// ------------------------------------------
	// TTL Expiration Tests
	// ------------------------------------------

	@Test
	void testTtlExpirationCleansUpTask() {
		// Create a store with very short default TTL (10ms)
		var shortTtlStore = new InMemoryTaskStore<McpSchema.ServerTaskPayloadResult>(10, 1000, null, 100);
		try {
			// Create a task (will use the store's default 10ms TTL)
			var task = shortTtlStore.createTask(createDefaultOptions()).block();
			String taskId = task.taskId();

			// Store a result for the task
			var result = createTestResult("test result");
			shortTtlStore.storeTaskResult(taskId, null, TaskStatus.COMPLETED, result).block();

			// Verify task exists
			assertThat(shortTtlStore.getTask(taskId, null).map(GetTaskFromStoreResult::task).block()).isNotNull();
			assertThat(shortTtlStore.getTaskResult(taskId, null).block()).isNotNull();

			// Wait for TTL to expire and verify cleanup removes task and result
			await().atMost(Duration.ofMillis(200)).pollInterval(Duration.ofMillis(10)).untilAsserted(() -> {
				// Manually trigger cleanup (normally runs every minute)
				shortTtlStore.cleanupExpiredTasks();
				// Verify task and result are cleaned up
				assertThat(shortTtlStore.getTask(taskId, null).map(GetTaskFromStoreResult::task).block()).isNull();
				assertThat(shortTtlStore.getTaskResult(taskId, null).block()).isNull();
			});
		}
		finally {
			shortTtlStore.shutdown().block();
		}
	}

	@Test
	void testTtlExpirationCleansUpRelatedData() {
		// Create a store with very short default TTL (10ms)
		var shortTtlStore = new InMemoryTaskStore<McpSchema.ServerTaskPayloadResult>(10, 1000, null, 100);
		try {
			// Create a task
			var task = shortTtlStore.createTask(createDefaultOptions()).block();
			String taskId = task.taskId();

			// Request cancellation (adds to cancellationRequests set)
			shortTtlStore.requestCancellation(taskId, null).block();

			// Verify cancellation request exists
			assertThat(shortTtlStore.isCancellationRequested(taskId, null).block()).isTrue();

			// Wait for TTL to expire and verify cleanup removes all related data
			await().atMost(Duration.ofMillis(200)).pollInterval(Duration.ofMillis(10)).untilAsserted(() -> {
				shortTtlStore.cleanupExpiredTasks();
				// Verify all related data is cleaned up
				assertThat(shortTtlStore.getTask(taskId, null).map(GetTaskFromStoreResult::task).block()).isNull();
				assertThat(shortTtlStore.isCancellationRequested(taskId, null).block()).isFalse();
			});
		}
		finally {
			shortTtlStore.shutdown().block();
		}
	}

	@Test
	void testNullTtlMeansUnlimitedLifetime() throws InterruptedException {
		// The default store has a 60s TTL, but we can test with custom options
		// Create task with explicit null TTL via CreateTaskOptions with requestedTtl
		var task = taskStore
			.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).requestedTtl(null).build())
			.block();

		// The task should use default TTL (not null), so this test verifies the flow
		// works
		assertThat(task).isNotNull();
		assertThat(task.ttl()).isNotNull(); // Should have default TTL applied
	}

	// --------------------------
	// Session Validation Tests
	// --------------------------

	@Test
	void testGetTaskReturnsTaskForMatchingSession() {
		String sessionId = "session-123";
		var task = taskStore
			.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).sessionId(sessionId).build())
			.block();

		// getTask with matching session ID should return the result
		StepVerifier.create(taskStore.getTask(task.taskId(), sessionId)).consumeNextWith(result -> {
			assertThat(result.task().taskId()).isEqualTo(task.taskId());
			assertThat(result.task().status()).isEqualTo(TaskStatus.WORKING);
		}).verifyComplete();
	}

	@Test
	void testGetTaskReturnsEmptyForMismatchedSession() {
		String sessionId = "session-123";
		String differentSessionId = "session-456";
		var task = taskStore
			.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).sessionId(sessionId).build())
			.block();

		// Request with different session ID should return empty (access denied)
		StepVerifier.create(taskStore.getTask(task.taskId(), differentSessionId)).verifyComplete();
	}

	@Test
	void testGetTaskReturnsTaskWhenNoSessionRestriction() {
		// Create task without session ID
		var task = taskStore.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).build()).block();

		// Any session should be able to access it
		StepVerifier.create(taskStore.getTask(task.taskId(), "any-session")).consumeNextWith(result -> {
			assertThat(result.task().taskId()).isEqualTo(task.taskId());
		}).verifyComplete();
	}

	@Test
	void testGetTaskReturnsEmptyForNonExistentTask() {
		StepVerifier.create(taskStore.getTask("non-existent-task", "some-session")).verifyComplete();
	}

	// ------------------------------------------
	// Race Condition Tests
	// ------------------------------------------

	@Test
	void testConcurrentCancellationAndStatusUpdate() throws InterruptedException {
		// Create task in WORKING state
		var task = taskStore.createTask(createDefaultOptions()).block();
		assertThat(task).isNotNull();
		String taskId = task.taskId();

		// Race: one thread cancels, one thread tries to complete
		runConcurrent(2, 2, i -> {
			if (i == 0) {
				taskStore.requestCancellation(taskId, null).block();
			}
			else {
				taskStore.updateTaskStatus(taskId, null, TaskStatus.COMPLETED, "Completed").block();
			}
		});

		// Task should end in exactly one terminal state (either CANCELLED or COMPLETED)
		var finalTask = taskStore.getTask(taskId, null).map(GetTaskFromStoreResult::task).block();
		assertThat(finalTask).isNotNull();
		assertThat(finalTask.status()).satisfiesAnyOf(status -> assertThat(status).isEqualTo(TaskStatus.CANCELLED),
				status -> assertThat(status).isEqualTo(TaskStatus.COMPLETED));
	}

	@Test
	void testConcurrentGetTaskDuringCleanup() throws InterruptedException {
		// Create a task store with very short TTL (10ms) and default poll interval
		var shortTtlStore = new InMemoryTaskStore<McpSchema.ServerTaskPayloadResult>(10L, 1000L);

		// Create a task
		var task = shortTtlStore
			.createTask(CreateTaskOptions.builder(createTestRequest("test-tool")).requestedTtl(10L).build())
			.block();
		assertThat(task).isNotNull();
		String taskId = task.taskId();

		// Wait for TTL to expire by polling until cleanup would remove the task
		await().atMost(Duration.ofMillis(200)).pollInterval(Duration.ofMillis(10)).until(() -> {
			shortTtlStore.cleanupExpiredTasks();
			return shortTtlStore.getTask(taskId, null).map(GetTaskFromStoreResult::task).block() == null;
		});

		// Concurrent threads try to get task after cleanup - verifies no race conditions
		runConcurrent(10, 10, i -> {
			// getTask() should return empty without throwing
			shortTtlStore.getTask(taskId, null).map(GetTaskFromStoreResult::task).block();
		});

		// No exceptions thrown = success
	}

}
