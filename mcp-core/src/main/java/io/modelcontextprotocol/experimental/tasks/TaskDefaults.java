/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Default constants for task-related operations.
 *
 * <p>
 * This class centralizes task-related default values to ensure consistency across the
 * SDK. All task-related components (stores, clients, servers) should reference these
 * constants instead of defining their own.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 */
public final class TaskDefaults {

	private TaskDefaults() {
		// Utility class - no instantiation
	}

	/**
	 * Default poll interval in milliseconds for task status polling. Clients will poll
	 * the server for task status updates at this interval unless the task specifies a
	 * different interval.
	 */
	public static final long DEFAULT_POLL_INTERVAL_MS = 1_000L;

	/**
	 * Default time-to-live in milliseconds for tasks. Tasks that exceed this TTL may be
	 * cleaned up by the task store.
	 */
	public static final long DEFAULT_TTL_MS = 60_000L;

	/**
	 * Default page size for task listing operations.
	 */
	public static final int DEFAULT_PAGE_SIZE = 100;

	/**
	 * Maximum allowed TTL for tasks (24 hours). Setting a TTL higher than this will be
	 * rejected to prevent tasks from lingering indefinitely.
	 */
	public static final long MAX_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

	/**
	 * Minimum allowed poll interval (100ms). Setting an interval lower than this will be
	 * rejected to prevent excessive polling.
	 */
	public static final long MIN_POLL_INTERVAL_MS = 100L;

	/**
	 * Maximum allowed poll interval (1 hour). Setting an interval higher than this will
	 * be rejected.
	 */
	public static final long MAX_POLL_INTERVAL_MS = 60 * 60 * 1000L; // 1 hour

	/**
	 * Default timeout for automatic task polling when a task-enabled tool is called
	 * without task metadata. The server will poll the task until it completes or this
	 * timeout is reached. Default is 30 minutes.
	 */
	public static final long DEFAULT_AUTOMATIC_POLLING_TIMEOUT_MS = 30 * 60 * 1000L; // 30
																						// minutes

	/**
	 * Default timeout in minutes for side-channel operations (elicitation/sampling during
	 * tasks). When a tool calls createElicitation() or createMessage() within a task
	 * context, it will wait for the response via side-channeling for up to this duration.
	 * Default is 5 minutes.
	 */
	public static final long DEFAULT_SIDE_CHANNEL_TIMEOUT_MINUTES = 5L;

	/**
	 * Default maximum number of concurrent tasks for in-memory task stores. This provides
	 * protection against resource exhaustion while being generous enough for typical use
	 * cases.
	 */
	public static final int DEFAULT_MAX_TASKS = 10_000;

	/**
	 * Interval in minutes between expired-task cleanup runs in the in-memory task store.
	 */
	public static final long CLEANUP_INTERVAL_MINUTES = 1L;

	/**
	 * Timeout in milliseconds for clearing a single task's message queue during
	 * expired-task cleanup. If the queue cleanup takes longer than this, it is abandoned
	 * to avoid blocking the cleanup thread.
	 */
	public static final long MESSAGE_QUEUE_CLEANUP_TIMEOUT_MS = 1_000L;

	/**
	 * Interval in milliseconds for internal polling when waiting for a queued response
	 * message in the in-memory message queue. This is a tight polling loop used within
	 * {@code InMemoryTaskMessageQueue} to detect response arrival.
	 */
	public static final long RESPONSE_POLL_INTERVAL_MS = 50L;

	/**
	 * Timeout in seconds for task store shutdown. Used when blocking on task store
	 * shutdown during client/server close, and when awaiting termination of the cleanup
	 * executor in the in-memory task store.
	 */
	public static final long TASK_STORE_SHUTDOWN_TIMEOUT_SECONDS = 5L;

	/**
	 * Default number of maximum poll attempts before timing out. Used with
	 * {@link #DEFAULT_POLL_INTERVAL_MS} to calculate dynamic timeouts.
	 */
	public static final int DEFAULT_MAX_POLL_ATTEMPTS = 60;

	/**
	 * Maximum timeout in milliseconds (1 hour). This prevents unbounded timeouts when
	 * tasks specify very large poll intervals.
	 */
	public static final long MAX_TIMEOUT_MS = 3_600_000L;

	/**
	 * Calculates timeout based on poll interval. This provides reasonable timeouts that
	 * scale with the polling frequency: 500ms poll interval = 30s timeout, 5000ms = 5 min
	 * timeout. The result is capped at {@link #MAX_TIMEOUT_MS} to prevent unbounded
	 * timeouts.
	 * @param pollInterval the poll interval in milliseconds (null defaults to
	 * {@link #DEFAULT_POLL_INTERVAL_MS})
	 * @return the calculated timeout duration, capped at 1 hour
	 */
	public static Duration calculateTimeout(Long pollInterval) {
		long interval = pollInterval != null ? pollInterval : DEFAULT_POLL_INTERVAL_MS;
		long calculatedMs = interval * DEFAULT_MAX_POLL_ATTEMPTS;
		return Duration.ofMillis(Math.min(calculatedMs, MAX_TIMEOUT_MS));
	}

	/**
	 * Validates task configuration. Task-aware tools require a TaskStore to be configured
	 * for task lifecycle management.
	 *
	 * <p>
	 * Having a TaskStore without task tools is allowed (for future dynamic registration).
	 * @param hasTaskTools whether task-aware tools have been registered
	 * @param hasTaskStore whether a TaskStore has been configured
	 * @throws IllegalStateException if task-aware tools are registered without a
	 * TaskStore
	 */
	public static void validateTaskConfiguration(boolean hasTaskTools, boolean hasTaskStore) {
		if (hasTaskTools && !hasTaskStore) {
			throw new IllegalStateException("Task-aware tools registered but no TaskStore configured. "
					+ "Add a TaskStore via .taskStore(store) or remove task tools.");
		}
	}

	/**
	 * Empty JSON schema representing an object with no properties. Used as the default
	 * input schema for task-aware tools that don't require input parameters.
	 */
	public static final JsonSchema EMPTY_INPUT_SCHEMA = new JsonSchema("object", null, null, null, null, null);

}
