/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

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
	 * Empty JSON schema representing an object with no properties. Used as the default
	 * input schema for task-aware tools that don't require input parameters.
	 */
	public static final JsonSchema EMPTY_INPUT_SCHEMA = new JsonSchema("object", null, null, null, null, null);

}
