/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.ServerTaskCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TaskSupportMode;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolExecution;

/**
 * Testing utilities for MCP tasks in mcp-core module.
 *
 * <p>
 * This is a minimal version of task test utilities containing only what's needed for
 * mcp-core unit tests. The full TaskTestUtils with polling helpers and test constants is
 * available in the mcp-test module.
 *
 */
public final class TaskTestUtils {

	private TaskTestUtils() {
		// Utility class - no instantiation
	}

	/**
	 * Default server capabilities with tasks enabled for tests.
	 */
	public static final ServerCapabilities DEFAULT_TASK_CAPABILITIES = ServerCapabilities.builder()
		.tasks(ServerTaskCapabilities.builder().list().cancel().toolsCall().build())
		.tools(true)
		.build();

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
	public static McpSchema.CallToolRequest createTestRequest(String toolName) {
		return new McpSchema.CallToolRequest(toolName, null);
	}

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
	 * @throws InterruptedException if the current thread is interrupted while waiting
	 * @throws RuntimeException if operations don't complete within 10 seconds
	 */
	public static void runConcurrent(int numOperations, int numThreads, IntConsumer operation)
			throws InterruptedException {
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
			if (!completed) {
				throw new RuntimeException("Operations did not complete within 10 seconds");
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

}
