/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;

/**
 * Configuration options for creating a {@link TaskManager}.
 *
 * <p>
 * Use the {@link #builder()} method to create instances:
 *
 * <pre>{@code
 * TaskManagerOptions options = TaskManagerOptions.builder()
 *     .store(new InMemoryTaskStore<>())
 *     .messageQueue(new InMemoryTaskMessageQueue())
 *     .defaultPollInterval(Duration.ofSeconds(1))
 *     .build();
 * }</pre>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see TaskManager
 * @see DefaultTaskManager
 */
public class TaskManagerOptions {

	private final TaskStore<?> taskStore;

	private final TaskMessageQueue messageQueue;

	private final Duration defaultPollInterval;

	private final Duration pollTimeout;

	private TaskManagerOptions(Builder builder) {
		this.taskStore = builder.taskStore;
		this.messageQueue = builder.messageQueue;
		this.defaultPollInterval = builder.defaultPollInterval != null ? builder.defaultPollInterval
				: Duration.ofMillis(TaskDefaults.DEFAULT_POLL_INTERVAL_MS);
		this.pollTimeout = builder.pollTimeout;
	}

	/**
	 * Returns the configured task store.
	 * @return the task store, or null if not configured
	 */
	public TaskStore<?> taskStore() {
		return this.taskStore;
	}

	/**
	 * Returns the configured message queue.
	 * @return the message queue, or null if not configured
	 */
	public TaskMessageQueue messageQueue() {
		return this.messageQueue;
	}

	/**
	 * Returns the default poll interval for task status checks.
	 * @return the default poll interval
	 */
	public Duration defaultPollInterval() {
		return this.defaultPollInterval;
	}

	/**
	 * Returns the poll timeout for task operations.
	 * @return the poll timeout, or null if not set
	 */
	public Duration pollTimeout() {
		return this.pollTimeout;
	}

	/**
	 * Creates a {@link TaskManager} from these options.
	 *
	 * <p>
	 * If no task store or message queue is configured, returns a {@link NullTaskManager}.
	 * Otherwise returns a {@link DefaultTaskManager}.
	 * @return a TaskManager instance
	 */
	public TaskManager createTaskManager() {
		if (this.taskStore == null && this.messageQueue == null) {
			return NullTaskManager.getInstance();
		}
		return new DefaultTaskManager(this);
	}

	/**
	 * Creates a new builder for TaskManagerOptions.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link TaskManagerOptions}.
	 */
	public static class Builder {

		private TaskStore<?> taskStore;

		private TaskMessageQueue messageQueue;

		private Duration defaultPollInterval;

		private Duration pollTimeout;

		private Builder() {
		}

		/**
		 * Sets the task store for persisting tasks.
		 * @param taskStore the task store
		 * @return this builder
		 */
		public Builder store(TaskStore<?> taskStore) {
			this.taskStore = taskStore;
			return this;
		}

		/**
		 * Sets the message queue for side-channel communication.
		 * @param messageQueue the message queue
		 * @return this builder
		 */
		public Builder messageQueue(TaskMessageQueue messageQueue) {
			this.messageQueue = messageQueue;
			return this;
		}

		/**
		 * Sets the default poll interval for task status checks.
		 * @param interval the poll interval
		 * @return this builder
		 */
		public Builder defaultPollInterval(Duration interval) {
			this.defaultPollInterval = interval;
			return this;
		}

		/**
		 * Sets the timeout for task polling operations.
		 * @param timeout the poll timeout
		 * @return this builder
		 */
		public Builder pollTimeout(Duration timeout) {
			this.pollTimeout = timeout;
			return this;
		}

		/**
		 * Builds the TaskManagerOptions.
		 * @return the built options
		 */
		public TaskManagerOptions build() {
			return new TaskManagerOptions(this);
		}

	}

}
