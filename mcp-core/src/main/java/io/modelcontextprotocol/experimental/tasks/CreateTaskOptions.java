/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Options for creating a new task.
 *
 * <p>
 * <strong>Recommended Usage:</strong> The {@link #builder(McpSchema.Request)} method
 * provides the preferred way to create options, offering a fluent API that's more
 * readable and maintainable than the record constructor:
 *
 * <pre>{@code
 * CreateTaskOptions options = CreateTaskOptions.builder(callToolRequest)
 *     .requestedTtl(60000L)
 *     .pollInterval(1000L)
 *     .sessionId(sessionId)
 *     .build();
 * }</pre>
 *
 * <p>
 * The {@code originatingRequest} field is required and stores the original MCP request
 * that triggered task creation. This allows the task store to provide full context when
 * retrieving tasks, eliminating the need for separate task-to-tool mapping.
 *
 * This is an experimental API that may change in future releases.
 *
 * @see Builder
 */
public record CreateTaskOptions(McpSchema.Request originatingRequest, String taskId, Long requestedTtl,
		Long pollInterval, Object context, String sessionId) {

	/**
	 * Compact constructor that validates options.
	 *
	 * <p>
	 * Validation rules:
	 * <ul>
	 * <li>originatingRequest: required (must not be null)</li>
	 * <li>TTL: 0 to {@link TaskDefaults#MAX_TTL_MS} (24 hours)</li>
	 * <li>Poll interval: 0 or {@link TaskDefaults#MIN_POLL_INTERVAL_MS} to
	 * {@link TaskDefaults#MAX_POLL_INTERVAL_MS}</li>
	 * </ul>
	 * @throws IllegalArgumentException if originatingRequest is null, or TTL or
	 * pollInterval is out of bounds
	 */
	public CreateTaskOptions {
		if (originatingRequest == null) {
			throw new IllegalArgumentException("originatingRequest must not be null");
		}
		if (requestedTtl != null) {
			if (requestedTtl < 0) {
				throw new IllegalArgumentException("requestedTtl must be non-negative, got: " + requestedTtl);
			}
			if (requestedTtl > TaskDefaults.MAX_TTL_MS) {
				throw new IllegalArgumentException("requestedTtl must not exceed " + TaskDefaults.MAX_TTL_MS
						+ "ms (24 hours), got: " + requestedTtl);
			}
		}
		if (pollInterval != null) {
			if (pollInterval < 0) {
				throw new IllegalArgumentException("pollInterval must be non-negative, got: " + pollInterval);
			}
			if (pollInterval > 0 && pollInterval < TaskDefaults.MIN_POLL_INTERVAL_MS) {
				throw new IllegalArgumentException("pollInterval must be at least " + TaskDefaults.MIN_POLL_INTERVAL_MS
						+ "ms when non-zero, got: " + pollInterval);
			}
			if (pollInterval > TaskDefaults.MAX_POLL_INTERVAL_MS) {
				throw new IllegalArgumentException("pollInterval must not exceed " + TaskDefaults.MAX_POLL_INTERVAL_MS
						+ "ms (1 hour), got: " + pollInterval);
			}
		}
	}

	/**
	 * Creates a new builder for CreateTaskOptions.
	 * @param originatingRequest the original MCP request that triggered task creation
	 * (required)
	 * @return a new builder instance
	 * @throws IllegalArgumentException if originatingRequest is null
	 */
	public static Builder builder(McpSchema.Request originatingRequest) {
		return new Builder(originatingRequest);
	}

	public static class Builder {

		private final McpSchema.Request originatingRequest;

		private String taskId;

		private Long requestedTtl;

		private Long pollInterval;

		private Object context;

		private String sessionId;

		/**
		 * Creates a new builder with the required originating request.
		 * @param originatingRequest the original MCP request that triggered task creation
		 * @throws IllegalArgumentException if originatingRequest is null
		 */
		Builder(McpSchema.Request originatingRequest) {
			if (originatingRequest == null) {
				throw new IllegalArgumentException("originatingRequest must not be null");
			}
			this.originatingRequest = originatingRequest;
		}

		/**
		 * Sets a custom task ID. If null or not called, the TaskStore will auto-generate
		 * a unique task ID.
		 *
		 * <p>
		 * Custom task IDs are useful for correlating MCP tasks with external systems
		 * (e.g., job queues, workflow engines). When wrapping external async APIs that
		 * have their own job IDs, you can use the external ID directly as the MCP task
		 * ID.
		 * @param taskId custom task ID, or null for auto-generation
		 * @return this builder
		 */
		public Builder taskId(String taskId) {
			this.taskId = taskId;
			return this;
		}

		/**
		 * Sets the requested TTL in milliseconds.
		 * @param requestedTtl the requested TTL
		 * @return this builder
		 */
		public Builder requestedTtl(Long requestedTtl) {
			this.requestedTtl = requestedTtl;
			return this;
		}

		/**
		 * Sets the suggested poll interval in milliseconds.
		 * @param pollInterval the poll interval
		 * @return this builder
		 */
		public Builder pollInterval(Long pollInterval) {
			this.pollInterval = pollInterval;
			return this;
		}

		/**
		 * Sets optional context data to associate with the task.
		 * @param context the context data
		 * @return this builder
		 */
		public Builder context(Object context) {
			this.context = context;
			return this;
		}

		/**
		 * Sets the session ID for session-scoped task isolation.
		 * @param sessionId the session ID
		 * @return this builder
		 */
		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		/**
		 * Builds the {@link CreateTaskOptions} instance.
		 *
		 * <p>
		 * Validation is performed by the record's compact constructor.
		 * @return the built CreateTaskOptions
		 * @throws IllegalArgumentException if TTL or pollInterval is out of bounds
		 * @see CreateTaskOptions#CreateTaskOptions(McpSchema.Request, String, Long, Long,
		 * Object, String)
		 */
		public CreateTaskOptions build() {
			// Validation is handled by the compact constructor
			return new CreateTaskOptions(originatingRequest, taskId, requestedTtl, pollInterval, context, sessionId);
		}

	}

}
