/*
 * Copyright 2024-2026 the original author or authors.
 */

/**
 * Experimental support for MCP Tasks (SEP-1686).
 *
 * <h2>Core Types</h2>
 * <ul>
 * <li>{@link io.modelcontextprotocol.experimental.tasks.TaskStore} - Interface for task
 * state persistence</li>
 * <li>{@link io.modelcontextprotocol.experimental.tasks.InMemoryTaskStore} - In-memory
 * TaskStore implementation</li>
 * <li>{@link io.modelcontextprotocol.experimental.tasks.TaskMessageQueue} - Queue for
 * INPUT_REQUIRED state communication</li>
 * </ul>
 *
 * <h2>Handler Interfaces</h2>
 *
 * <p>
 * Task-aware tools use three handler interfaces for different lifecycle phases:
 *
 * <h3>CreateTaskHandler / SyncCreateTaskHandler (Required)</h3>
 * <p>
 * Invoked when a task-augmented request is received. Responsible for:
 * <ul>
 * <li>Creating the task in the TaskStore</li>
 * <li>Starting any background work</li>
 * <li>Returning a {@link io.modelcontextprotocol.spec.McpSchema.CreateTaskResult
 * CreateTaskResult} with the task details</li>
 * </ul>
 *
 * <h3>GetTaskHandler / SyncGetTaskHandler (Optional)</h3>
 * <p>
 * Custom handler for {@code tasks/get} requests. Use when:
 * <ul>
 * <li>Mapping external job IDs to MCP task status</li>
 * <li>Implementing custom status derivation</li>
 * </ul>
 * <p>
 * If not provided, the default implementation uses
 * {@link io.modelcontextprotocol.experimental.tasks.TaskStore#getTask(String, String)
 * TaskStore.getTask()}.
 *
 * <h3>GetTaskResultHandler / SyncGetTaskResultHandler (Optional)</h3>
 * <p>
 * Custom handler for {@code tasks/result} requests. Use when:
 * <ul>
 * <li>Fetching results from external systems</li>
 * <li>Transforming stored results before returning</li>
 * </ul>
 * <p>
 * If not provided, the default implementation uses
 * {@link io.modelcontextprotocol.experimental.tasks.TaskStore#getTaskResult(String, String)
 * TaskStore.getTaskResult()}.
 *
 * <h2>Automatic Polling Behavior</h2>
 *
 * <p>
 * When a task-aware tool with
 * {@link io.modelcontextprotocol.spec.McpSchema.TaskSupportMode#OPTIONAL OPTIONAL} mode
 * is called <strong>without</strong> task metadata, the server automatically handles the
 * task lifecycle:
 *
 * <ol>
 * <li>Creates an internal task via the tool's createTask handler</li>
 * <li>Polls the task status using the configured poll interval</li>
 * <li>When the task reaches a terminal state (COMPLETED, FAILED, CANCELLED), retrieves
 * the result</li>
 * <li>Returns the result to the caller as if it were a synchronous operation</li>
 * </ol>
 *
 * <p>
 * This behavior provides backward compatibility for clients that don't support the tasks
 * protocol extension.
 *
 * <p>
 * <strong>Note:</strong> Automatic polling does NOT work for tasks that enter
 * {@link io.modelcontextprotocol.spec.McpSchema.TaskStatus#INPUT_REQUIRED INPUT_REQUIRED}
 * state, as this requires explicit client interaction. Such tasks will timeout or fail.
 *
 * <p>
 * For tools where automatic polling is not appropriate (e.g., very long operations, tasks
 * requiring user input), use
 * {@link io.modelcontextprotocol.spec.McpSchema.TaskSupportMode#REQUIRED REQUIRED} mode
 * instead.
 *
 * <h2>Tool Specifications</h2>
 * <ul>
 * <li>{@link io.modelcontextprotocol.experimental.tasks.TaskAwareAsyncToolSpecification}
 * - Async task-aware tool definition</li>
 * <li>{@link io.modelcontextprotocol.experimental.tasks.TaskAwareSyncToolSpecification} -
 * Sync task-aware tool definition</li>
 * </ul>
 *
 * <h2>Context Types</h2>
 * <ul>
 * <li>{@link io.modelcontextprotocol.experimental.tasks.CreateTaskExtra} /
 * {@link io.modelcontextprotocol.experimental.tasks.SyncCreateTaskExtra} - Handler
 * context</li>
 * <li>{@link io.modelcontextprotocol.experimental.tasks.CreateTaskOptions} - Task
 * creation configuration</li>
 * </ul>
 *
 * <h2>Utilities</h2>
 * <ul>
 * <li>{@link io.modelcontextprotocol.experimental.tasks.TaskDefaults} - Default
 * constants</li>
 * <li>{@link io.modelcontextprotocol.experimental.tasks.TaskHelper} - State transition
 * utilities</li>
 * </ul>
 *
 * <p>
 * <strong>WARNING:</strong> This is an experimental API that may change in future
 * releases.
 */
package io.modelcontextprotocol.experimental.tasks;
