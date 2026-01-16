/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntConsumer;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.ServerTaskCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Task;
import io.modelcontextprotocol.spec.McpSchema.TaskMetadata;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;
import io.modelcontextprotocol.spec.McpSchema.TaskSupportMode;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolExecution;
import reactor.core.publisher.Mono;

/**
 * Testing utilities for MCP tasks.
 *
 * <p>
 * This class provides helper methods for testing task-based operations, including polling
 * for task status changes and waiting for specific task states.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 */
public final class TaskTestUtils {

	private TaskTestUtils() {
		// Utility class - no instantiation
	}

	/**
	 * Default timeout for waiting for task status changes.
	 */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

	/**
	 * Default poll interval for checking task status.
	 */
	public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);

	/**
	 * Waits for a task to reach a specific status.
	 *
	 * <p>
	 * This method repeatedly calls the provided getTask function at regular intervals
	 * until the task reaches the desired status or the timeout is exceeded.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * Task completedTask = TaskTestUtils.waitForTaskStatus(
	 *     taskId -> client.getTask(taskId),
	 *     "task-123",
	 *     TaskStatus.COMPLETED
	 * ).block();
	 * }</pre>
	 * @param getTask function that retrieves a task by ID (returns Mono.empty() if not
	 * found)
	 * @param taskId the task identifier to poll
	 * @param desiredStatus the status to wait for
	 * @return a Mono emitting the Task once it reaches the desired status
	 * @throws java.util.concurrent.TimeoutException if the task doesn't reach the desired
	 * status within the default timeout (30 seconds)
	 */
	public static Mono<Task> waitForTaskStatus(Function<String, Mono<Task>> getTask, String taskId,
			TaskStatus desiredStatus) {
		return waitForTaskStatus(getTask, taskId, desiredStatus, DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL);
	}

	/**
	 * Waits for a task to reach a specific status with custom timeout.
	 *
	 * <p>
	 * This method repeatedly calls the provided getTask function at regular intervals
	 * until the task reaches the desired status or the timeout is exceeded.
	 * @param getTask function that retrieves a task by ID (returns Mono.empty() if not
	 * found)
	 * @param taskId the task identifier to poll
	 * @param desiredStatus the status to wait for
	 * @param timeout maximum time to wait for the desired status
	 * @return a Mono emitting the Task once it reaches the desired status
	 * @throws java.util.concurrent.TimeoutException if the task doesn't reach the desired
	 * status within the timeout
	 */
	public static Mono<Task> waitForTaskStatus(Function<String, Mono<Task>> getTask, String taskId,
			TaskStatus desiredStatus, Duration timeout) {
		return waitForTaskStatus(getTask, taskId, desiredStatus, timeout, DEFAULT_POLL_INTERVAL);
	}

	/**
	 * Waits for a task to reach a specific status with custom timeout and poll interval.
	 *
	 * <p>
	 * This method repeatedly calls the provided getTask function at regular intervals
	 * until the task reaches the desired status or the timeout is exceeded.
	 * @param getTask function that retrieves a task by ID (returns Mono.empty() if not
	 * found)
	 * @param taskId the task identifier to poll
	 * @param desiredStatus the status to wait for
	 * @param timeout maximum time to wait for the desired status
	 * @param pollInterval interval between status checks
	 * @return a Mono emitting the Task once it reaches the desired status
	 * @throws java.util.concurrent.TimeoutException if the task doesn't reach the desired
	 * status within the timeout
	 */
	public static Mono<Task> waitForTaskStatus(Function<String, Mono<Task>> getTask, String taskId,
			TaskStatus desiredStatus, Duration timeout, Duration pollInterval) {
		return reactor.core.publisher.Flux.interval(pollInterval)
			.flatMap(tick -> getTask.apply(taskId))
			.filter(task -> task != null && task.status() == desiredStatus)
			.next()
			.timeout(timeout);
	}

	/**
	 * Waits for a task to reach any terminal status (COMPLETED, FAILED, or CANCELLED).
	 *
	 * <p>
	 * This is useful when you want to wait for a task to finish, regardless of whether it
	 * succeeds or fails.
	 * @param getTask function that retrieves a task by ID
	 * @param taskId the task identifier to poll
	 * @return a Mono emitting the Task once it reaches a terminal status
	 * @throws java.util.concurrent.TimeoutException if the task doesn't reach a terminal
	 * status within the default timeout (30 seconds)
	 */
	public static Mono<Task> waitForTerminal(Function<String, Mono<Task>> getTask, String taskId) {
		return waitForTerminal(getTask, taskId, DEFAULT_TIMEOUT);
	}

	/**
	 * Waits for a task to reach any terminal status with custom timeout.
	 * @param getTask function that retrieves a task by ID
	 * @param taskId the task identifier to poll
	 * @param timeout maximum time to wait
	 * @return a Mono emitting the Task once it reaches a terminal status
	 * @throws java.util.concurrent.TimeoutException if the task doesn't reach a terminal
	 * status within the timeout
	 */
	public static Mono<Task> waitForTerminal(Function<String, Mono<Task>> getTask, String taskId, Duration timeout) {
		return reactor.core.publisher.Flux.interval(DEFAULT_POLL_INTERVAL)
			.flatMap(tick -> getTask.apply(taskId))
			.filter(task -> task != null && task.isTerminal())
			.next()
			.timeout(timeout);
	}

	// ------------------------------------------
	// Shared Test Constants
	// ------------------------------------------

	/**
	 * An empty JSON schema for tools that don't require input parameters.
	 */
	public static final JsonSchema EMPTY_JSON_SCHEMA = new JsonSchema("object", Collections.emptyMap(), null, null,
			null, null);

	/**
	 * Default task metadata with 60-second TTL for tests.
	 */
	public static final TaskMetadata DEFAULT_TASK_METADATA = TaskMetadata.builder()
		.ttl(Duration.ofMillis(60000L))
		.build();

	/**
	 * Default server capabilities with tasks enabled for tests.
	 */
	public static final ServerCapabilities DEFAULT_TASK_CAPABILITIES = ServerCapabilities.builder()
		.tasks(ServerTaskCapabilities.builder().list().cancel().toolsCall().build())
		.tools(true)
		.build();

	/**
	 * Default tool name for task-supported tools in tests.
	 */
	public static final String DEFAULT_TASK_TOOL_NAME = "slow-operation";

	/**
	 * Default tool arguments for task-supported tools in tests.
	 */
	public static final Map<String, Object> DEFAULT_TASK_TOOL_ARGS = Map.of("message", "Test message from Java SDK");

	// ------------------------------------------
	// Shared Test Helpers
	// ------------------------------------------

	/**
	 * Creates a task-augmented CallToolRequest using the default tool and arguments.
	 * @return a CallToolRequest with default task metadata
	 */
	public static CallToolRequest createDefaultTaskRequest() {
		return new CallToolRequest(DEFAULT_TASK_TOOL_NAME, DEFAULT_TASK_TOOL_ARGS, DEFAULT_TASK_METADATA, null);
	}

	/**
	 * Creates a task-augmented CallToolRequest with custom tool name and arguments.
	 * @param toolName the tool name
	 * @param args the tool arguments
	 * @return a CallToolRequest with default task metadata
	 */
	public static CallToolRequest createTaskRequest(String toolName, Map<String, Object> args) {
		return new CallToolRequest(toolName, args, DEFAULT_TASK_METADATA, null);
	}

	/**
	 * Creates a tool with the given name and task support mode for tests.
	 * @param name the tool name
	 * @param title the tool title
	 * @param mode the task support mode
	 * @return a Tool with task execution support
	 */
	public static Tool createTaskTool(String name, String title, TaskSupportMode mode) {
		return McpSchema.Tool.builder()
			.name(name)
			.title(title)
			.inputSchema(EMPTY_JSON_SCHEMA)
			.execution(ToolExecution.builder().taskSupport(mode).build())
			.build();
	}

	/**
	 * Creates a test CallToolRequest for use in CreateTaskOptions. This is the standard
	 * originating request type used in tests.
	 * @param toolName the name of the tool for the request
	 * @return a CallToolRequest with the given tool name and null arguments
	 */
	public static CallToolRequest createTestRequest(String toolName) {
		return new CallToolRequest(toolName, null);
	}

	// ------------------------------------------
	// Concurrency Test Helpers
	// ------------------------------------------

	/**
	 * Runs concurrent operations with proper synchronization.
	 *
	 * <p>
	 * This method executes the given operation across multiple threads, ensuring all
	 * threads start at approximately the same time using a latch. This is useful for
	 * testing thread safety and concurrent access patterns.
	 *
	 * <p>
	 * Example usage:
	 *
	 * <pre>{@code
	 * TaskTestUtils.runConcurrent(100, 10, i -> {
	 *     taskStore.createTask(null).block();
	 * });
	 * }</pre>
	 * @param numOperations total number of operations to execute
	 * @param numThreads number of threads in the thread pool
	 * @param operation the operation to run, receiving the operation index (0 to
	 * numOperations-1)
	 * @param assertionProvider provides the assertion method to use (to avoid test
	 * dependency in main sources)
	 * @throws InterruptedException if the current thread is interrupted while waiting
	 * @throws RuntimeException if operations don't complete within 10 seconds
	 */
	public static void runConcurrent(int numOperations, int numThreads, IntConsumer operation,
			java.util.function.BiConsumer<Boolean, String> assertionProvider) throws InterruptedException {
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(numOperations);
		ExecutorService executor = Executors.newFixedThreadPool(numThreads);
		try {
			for (int i = 0; i < numOperations; i++) {
				final int index = i;
				executor.submit(() -> {
					try {
						startLatch.await();
						operation.accept(index);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					finally {
						doneLatch.countDown();
					}
				});
			}
			startLatch.countDown();
			boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
			assertionProvider.accept(completed, "Operations did not complete within 10 seconds");
		}
		finally {
			executor.shutdownNow();
		}
	}

	/**
	 * Runs concurrent operations with proper synchronization (simplified version that
	 * throws on timeout).
	 *
	 * <p>
	 * This method executes the given operation across multiple threads, ensuring all
	 * threads start at approximately the same time using a latch.
	 * @param numOperations total number of operations to execute
	 * @param numThreads number of threads in the thread pool
	 * @param operation the operation to run, receiving the operation index (0 to
	 * numOperations-1)
	 * @throws InterruptedException if the current thread is interrupted while waiting
	 * @throws RuntimeException if operations don't complete within 10 seconds
	 */
	public static void runConcurrent(int numOperations, int numThreads, IntConsumer operation)
			throws InterruptedException {
		runConcurrent(numOperations, numThreads, operation, (result, message) -> {
			if (!result) {
				throw new RuntimeException(message);
			}
		});
	}

}
