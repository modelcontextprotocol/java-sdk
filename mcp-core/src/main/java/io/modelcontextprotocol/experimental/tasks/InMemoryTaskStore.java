/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Task;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of {@link TaskStore}.
 *
 * <p>
 * This implementation stores tasks in memory using thread-safe concurrent data
 * structures. It supports TTL-based cleanup of expired tasks.
 *
 * <p>
 * The type parameter {@code R} specifies the result type that this store handles:
 * <ul>
 * <li>For server-side stores (handling tool calls), use
 * {@link McpSchema.ServerTaskPayloadResult}
 * <li>For client-side stores (handling sampling/elicitation), use
 * {@link McpSchema.ClientTaskPayloadResult}
 * <li>For stores that can handle any result type, use {@link McpSchema.Result}
 * </ul>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @param <R> the type of result this store handles
 */
public class InMemoryTaskStore<R extends McpSchema.Result> implements TaskStore<R> {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryTaskStore.class);

	// Use centralized defaults from TaskDefaults
	private static final long DEFAULT_TTL_MS = TaskDefaults.DEFAULT_TTL_MS;

	private static final long DEFAULT_POLL_INTERVAL_MS = TaskDefaults.DEFAULT_POLL_INTERVAL_MS;

	private static final int DEFAULT_PAGE_SIZE = TaskDefaults.DEFAULT_PAGE_SIZE;

	// Use centralized max tasks default from TaskDefaults
	private static final int DEFAULT_MAX_TASKS = TaskDefaults.DEFAULT_MAX_TASKS;

	// Use ConcurrentSkipListMap for O(log n) sorted access and efficient
	// cursor-based pagination via tailMap()
	private final NavigableMap<String, TaskEntry> tasks = new ConcurrentSkipListMap<>();

	private final Map<String, R> results = new ConcurrentHashMap<>();

	private final Set<String> cancellationRequests = ConcurrentHashMap.newKeySet();

	private final ScheduledExecutorService cleanupExecutor;

	private final long defaultTtl;

	private final long defaultPollInterval;

	// Counter for unique instance IDs to distinguish multiple stores in thread names
	private static final AtomicLong INSTANCE_COUNTER = new AtomicLong(0);

	private final long instanceId;

	// Optional message queue for coordinated cleanup
	private final TaskMessageQueue messageQueue;

	// Maximum number of concurrent tasks
	private final int maxTasks;

	/**
	 * Creates a new InMemoryTaskStore with default settings.
	 */
	public InMemoryTaskStore() {
		this(DEFAULT_TTL_MS, DEFAULT_POLL_INTERVAL_MS, null, DEFAULT_MAX_TASKS);
	}

	/**
	 * Creates a new InMemoryTaskStore with custom TTL and poll interval.
	 * @param defaultTtl the default TTL in milliseconds
	 * @param defaultPollInterval the default poll interval in milliseconds
	 */
	public InMemoryTaskStore(long defaultTtl, long defaultPollInterval) {
		this(defaultTtl, defaultPollInterval, null, DEFAULT_MAX_TASKS);
	}

	/**
	 * Creates a new InMemoryTaskStore with custom settings and optional message queue for
	 * coordinated cleanup.
	 * @param defaultTtl the default TTL in milliseconds
	 * @param defaultPollInterval the default poll interval in milliseconds
	 * @param messageQueue optional message queue to clean up when tasks expire (may be
	 * null)
	 */
	public InMemoryTaskStore(long defaultTtl, long defaultPollInterval, TaskMessageQueue messageQueue) {
		this(defaultTtl, defaultPollInterval, messageQueue, DEFAULT_MAX_TASKS);
	}

	/**
	 * Creates a new InMemoryTaskStore with custom settings, optional message queue, and
	 * maximum task limit.
	 * @param defaultTtl the default TTL in milliseconds (must be positive)
	 * @param defaultPollInterval the default poll interval in milliseconds (must be
	 * positive)
	 * @param messageQueue optional message queue to clean up when tasks expire (may be
	 * null)
	 * @param maxTasks maximum number of concurrent tasks (must be positive)
	 * @throws IllegalArgumentException if defaultTtl, defaultPollInterval, or maxTasks is
	 * not positive
	 */
	public InMemoryTaskStore(long defaultTtl, long defaultPollInterval, TaskMessageQueue messageQueue, int maxTasks) {
		if (defaultTtl <= 0) {
			throw new IllegalArgumentException("defaultTtl must be positive");
		}
		if (defaultPollInterval <= 0) {
			throw new IllegalArgumentException("defaultPollInterval must be positive");
		}
		if (maxTasks <= 0) {
			throw new IllegalArgumentException("maxTasks must be positive");
		}
		this.instanceId = INSTANCE_COUNTER.incrementAndGet();
		this.defaultTtl = defaultTtl;
		this.defaultPollInterval = defaultPollInterval;
		this.messageQueue = messageQueue;
		this.maxTasks = maxTasks;
		this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
			// Include instance ID in thread name for debugging with multiple stores
			Thread t = new Thread(r, "mcp-task-cleanup-" + instanceId);
			t.setDaemon(true);
			return t;
		});
		this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredTasks, 1, 1, TimeUnit.MINUTES);
	}

	/**
	 * Creates a new builder for InMemoryTaskStore with default settings.
	 *
	 * <p>
	 * The builder provides a fluent API for configuring the store:
	 *
	 * <pre>{@code
	 * InMemoryTaskStore<ServerTaskPayloadResult> store = InMemoryTaskStore.<ServerTaskPayloadResult>builder()
	 *     .defaultTtl(Duration.ofMinutes(30))
	 *     .defaultPollInterval(Duration.ofSeconds(2))
	 *     .maxTasks(5000)
	 *     .messageQueue(messageQueue)
	 *     .build();
	 * }</pre>
	 * @param <R> the result type for this store
	 * @return a new builder instance
	 */
	public static <R extends McpSchema.Result> Builder<R> builder() {
		return new Builder<>();
	}

	/**
	 * Builder for creating {@link InMemoryTaskStore} instances with custom configuration.
	 *
	 * <p>
	 * All parameters are optional; defaults are used for any unset values:
	 * <ul>
	 * <li>{@code defaultTtl}: {@link TaskDefaults#DEFAULT_TTL_MS} (1 minute)</li>
	 * <li>{@code defaultPollInterval}: {@link TaskDefaults#DEFAULT_POLL_INTERVAL_MS} (1
	 * second)</li>
	 * <li>{@code maxTasks}: {@link TaskDefaults#DEFAULT_MAX_TASKS} (10,000)</li>
	 * <li>{@code messageQueue}: null (no coordinated cleanup)</li>
	 * </ul>
	 *
	 * @param <R> the result type for stores created by this builder
	 */
	public static class Builder<R extends McpSchema.Result> {

		private long defaultTtl = DEFAULT_TTL_MS;

		private long defaultPollInterval = DEFAULT_POLL_INTERVAL_MS;

		private TaskMessageQueue messageQueue = null;

		private int maxTasks = DEFAULT_MAX_TASKS;

		/**
		 * Sets the default TTL for tasks when not specified in CreateTaskOptions.
		 * @param ttl the default TTL duration (must be positive)
		 * @return this builder for chaining
		 */
		public Builder<R> defaultTtl(Duration ttl) {
			this.defaultTtl = ttl.toMillis();
			return this;
		}

		/**
		 * Sets the default TTL for tasks in milliseconds.
		 * @param ttlMs the default TTL in milliseconds (must be positive)
		 * @return this builder for chaining
		 */
		public Builder<R> defaultTtlMs(long ttlMs) {
			this.defaultTtl = ttlMs;
			return this;
		}

		/**
		 * Sets the default poll interval for task status checking.
		 * @param interval the default poll interval duration (must be positive)
		 * @return this builder for chaining
		 */
		public Builder<R> defaultPollInterval(Duration interval) {
			this.defaultPollInterval = interval.toMillis();
			return this;
		}

		/**
		 * Sets the default poll interval in milliseconds.
		 * @param intervalMs the default poll interval in milliseconds (must be positive)
		 * @return this builder for chaining
		 */
		public Builder<R> defaultPollIntervalMs(long intervalMs) {
			this.defaultPollInterval = intervalMs;
			return this;
		}

		/**
		 * Sets the message queue for coordinated cleanup of task messages.
		 * @param queue the message queue (may be null)
		 * @return this builder for chaining
		 */
		public Builder<R> messageQueue(TaskMessageQueue queue) {
			this.messageQueue = queue;
			return this;
		}

		/**
		 * Sets the maximum number of concurrent tasks.
		 * @param max the maximum task count (must be positive)
		 * @return this builder for chaining
		 */
		public Builder<R> maxTasks(int max) {
			this.maxTasks = max;
			return this;
		}

		/**
		 * Builds the InMemoryTaskStore with the configured settings.
		 * @return a new InMemoryTaskStore instance
		 * @throws IllegalArgumentException if any configured value is invalid
		 */
		public InMemoryTaskStore<R> build() {
			return new InMemoryTaskStore<>(defaultTtl, defaultPollInterval, messageQueue, maxTasks);
		}

	}

	// Lock object for task creation to prevent race condition in max task check
	private final Object createTaskLock = new Object();

	/**
	 * Validates session access for a task entry.
	 *
	 * <p>
	 * Session validation rules:
	 * <ul>
	 * <li>If requestSessionId is null, access is allowed (single-tenant mode)</li>
	 * <li>If task has no session (created with null sessionId), access is allowed from
	 * any session</li>
	 * <li>If both task and request have session IDs, they must match for access</li>
	 * </ul>
	 * @param entry the task entry to validate
	 * @param requestSessionId the session ID from the request, or null for single-tenant
	 * @return true if access is allowed, false otherwise
	 */
	private boolean isSessionValid(TaskEntry entry, String requestSessionId) {
		// Null request session = single-tenant mode, allow all
		if (requestSessionId == null) {
			return true;
		}
		// Task has no session = allow access from any session
		String taskSessionId = entry.sessionId();
		if (taskSessionId == null || taskSessionId.isEmpty()) {
			return true;
		}
		// Both have session IDs, they must match
		return requestSessionId.equals(taskSessionId);
	}

	@Override
	public Mono<Task> createTask(CreateTaskOptions options) {
		return Mono.fromCallable(() -> {
			// Synchronize to make max task check and task creation atomic
			// This prevents multiple concurrent calls from exceeding maxTasks
			synchronized (createTaskLock) {
				if (tasks.size() >= maxTasks) {
					throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
						.message("Maximum task limit reached (" + maxTasks + ")")
						.build();
				}

				// Use provided taskId if present, otherwise generate one
				String taskId = options.taskId() != null ? options.taskId() : UUID.randomUUID().toString();
				String now = Instant.now().toString();

				Long ttl = options.requestedTtl() != null ? options.requestedTtl() : defaultTtl;

				Long pollInterval = options.pollInterval() != null ? options.pollInterval() : defaultPollInterval;

				String sessionId = options.sessionId();

				Task task = Task.builder()
					.taskId(taskId)
					.status(TaskStatus.WORKING)
					.createdAt(now)
					.lastUpdatedAt(now)
					.ttl(ttl)
					.pollInterval(pollInterval)
					.build();

				tasks.put(taskId, new TaskEntry(task, options.originatingRequest(), options.context(), sessionId));

				return task;
			}
		});
	}

	@Override
	public Mono<GetTaskFromStoreResult> getTask(String taskId, String sessionId) {
		return Mono.fromCallable(() -> {
			TaskEntry entry = tasks.get(taskId);
			if (entry == null) {
				return null;
			}
			// Validate session access atomically
			if (!isSessionValid(entry, sessionId)) {
				return null;
			}
			return new GetTaskFromStoreResult(entry.task(), entry.originatingRequest());
		});
	}

	@Override
	public Mono<Void> updateTaskStatus(String taskId, String sessionId, TaskStatus status, String statusMessage) {
		return Mono.fromRunnable(() -> {
			tasks.computeIfPresent(taskId, (id, entry) -> {
				// Validate session access
				if (!isSessionValid(entry, sessionId)) {
					return entry; // Silently ignore session mismatch
				}
				Task oldTask = entry.task();
				// Skip update if task is already in terminal state
				if (oldTask.isTerminal()) {
					return entry;
				}
				String now = Instant.now().toString();
				Task newTask = Task.builder()
					.taskId(oldTask.taskId())
					.status(status)
					.statusMessage(statusMessage)
					.createdAt(oldTask.createdAt())
					.lastUpdatedAt(now)
					.ttl(oldTask.ttl())
					.pollInterval(oldTask.pollInterval())
					.build();
				return new TaskEntry(newTask, entry.originatingRequest(), entry.context(), entry.sessionId());
			});
		});
	}

	@Override
	public Mono<Void> storeTaskResult(String taskId, String sessionId, TaskStatus status, R result) {
		return Mono.fromRunnable(() -> {
			// Update task to terminal status, only storing result if task exists
			// Throws McpError if task not found or session mismatch to avoid silent data
			// loss
			AtomicBoolean taskFound = new AtomicBoolean(false);
			AtomicBoolean sessionValid = new AtomicBoolean(true);
			AtomicBoolean wasTerminal = new AtomicBoolean(false);

			tasks.computeIfPresent(taskId, (id, entry) -> {
				taskFound.set(true);

				// Validate session access
				if (!isSessionValid(entry, sessionId)) {
					sessionValid.set(false);
					return entry;
				}

				Task oldTask = entry.task();

				// Don't overwrite if task is already in terminal state
				if (oldTask.isTerminal()) {
					wasTerminal.set(true);
					return entry;
				}

				results.put(taskId, result);
				String now = Instant.now().toString();
				Task newTask = Task.builder()
					.taskId(oldTask.taskId())
					.status(status)
					.createdAt(oldTask.createdAt())
					.lastUpdatedAt(now)
					.ttl(oldTask.ttl())
					.pollInterval(oldTask.pollInterval())
					.build();
				return new TaskEntry(newTask, entry.originatingRequest(), entry.context(), entry.sessionId());
			});

			if (!taskFound.get()) {
				throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
					.message("Task not found (may have expired after TTL): " + taskId)
					.build();
			}
			if (!sessionValid.get()) {
				throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
					.message("Task not found (may have expired after TTL): " + taskId)
					.build();
			}
			// Log if we skipped storing because task was already terminal
			if (wasTerminal.get()) {
				logger.debug("Skipped storing result for task {} - already in terminal state", taskId);
			}
		});
	}

	@Override
	public Mono<R> getTaskResult(String taskId, String sessionId) {
		return Mono.fromCallable(() -> {
			// First validate session access
			TaskEntry entry = tasks.get(taskId);
			if (entry == null || !isSessionValid(entry, sessionId)) {
				return null;
			}
			return results.get(taskId);
		});
	}

	@Override
	public Mono<McpSchema.ListTasksResult> listTasks(String cursor, String sessionId) {
		return Mono.fromCallable(() -> {
			List<Task> taskList = new ArrayList<>();
			String nextCursor = null;

			// Use tailMap for O(log n) cursor lookup instead of O(n) indexOf()
			// tailMap gracefully handles missing cursors (e.g., expired tasks) by
			// returning entries that come after where the cursor would be
			// lexicographically
			NavigableMap<String, TaskEntry> view;
			if (cursor != null) {
				// Get entries strictly after cursor (exclusive)
				// If cursor doesn't exist (e.g., task expired), this returns entries
				// after where the cursor would be, providing graceful degradation
				view = tasks.tailMap(cursor, false);
			}
			else {
				view = tasks;
			}

			// Iterate through the view, collecting up to PAGE_SIZE entries
			// Filter by sessionId if provided
			// Note: When filtering by sessionId, pages may contain fewer than
			// DEFAULT_PAGE_SIZE entries. This is intentional - it ensures consistent
			// cursor behavior while allowing session-scoped views of the task list.
			Iterator<Map.Entry<String, TaskEntry>> iterator = view.entrySet().iterator();
			int count = 0;
			String lastKey = null;

			while (iterator.hasNext() && count < DEFAULT_PAGE_SIZE) {
				Map.Entry<String, TaskEntry> entry = iterator.next();
				TaskEntry taskEntry = entry.getValue();

				// Filter by session if sessionId is provided
				if (sessionId != null && !sessionId.equals(taskEntry.sessionId())) {
					continue;
				}

				taskList.add(taskEntry.task());
				lastKey = entry.getKey();
				count++;
			}

			// Check if there are more entries (that match the session filter)
			while (iterator.hasNext()) {
				Map.Entry<String, TaskEntry> entry = iterator.next();
				if (sessionId == null || sessionId.equals(entry.getValue().sessionId())) {
					nextCursor = lastKey;
					break;
				}
			}

			return McpSchema.ListTasksResult.builder().tasks(taskList).nextCursor(nextCursor).build();
		});
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * Per the MCP specification, cancellation of tasks in terminal status MUST be
	 * rejected with error code {@code -32602} (Invalid params). This implementation
	 * throws {@link McpError} with the appropriate error code when attempting to cancel a
	 * task that is already in COMPLETED, FAILED, or CANCELLED status.
	 *
	 * <p>
	 * <strong>Return Value:</strong> This method returns the updated Task (now with
	 * status CANCELLED) rather than {@code Mono<Void>} to allow callers to immediately
	 * verify the cancellation was applied and obtain the updated task state without
	 * making a separate {@code getTask()} call. This is especially useful for returning
	 * the cancelled task in the {@code tasks/cancel} response.
	 * @throws McpError with code {@link McpSchema.ErrorCodes#INVALID_PARAMS} if the task
	 * is in a terminal state
	 */
	@Override
	public Mono<Task> requestCancellation(String taskId, String sessionId) {
		return Mono.fromCallable(() -> {
			AtomicReference<Task> resultRef = new AtomicReference<>();
			AtomicReference<TaskStatus> terminalStatusRef = new AtomicReference<>();
			AtomicBoolean sessionValid = new AtomicBoolean(true);

			// Use computeIfPresent for atomic update to avoid race condition
			tasks.computeIfPresent(taskId, (id, entry) -> {
				// Validate session access
				if (!isSessionValid(entry, sessionId)) {
					sessionValid.set(false);
					return entry;
				}

				Task oldTask = entry.task();
				// Per MCP spec: MUST reject cancellation of tasks in terminal status
				if (oldTask.isTerminal()) {
					terminalStatusRef.set(oldTask.status());
					resultRef.set(oldTask);
					return entry;
				}
				cancellationRequests.add(taskId);
				String now = Instant.now().toString();
				Task newTask = Task.builder()
					.taskId(oldTask.taskId())
					.status(TaskStatus.CANCELLED)
					.statusMessage("Cancellation requested")
					.createdAt(oldTask.createdAt())
					.lastUpdatedAt(now)
					.ttl(oldTask.ttl())
					.pollInterval(oldTask.pollInterval())
					.build();
				resultRef.set(newTask);
				return new TaskEntry(newTask, entry.originatingRequest(), entry.context(), entry.sessionId());
			});

			// Session mismatch returns empty (task not found from caller's perspective)
			if (!sessionValid.get()) {
				return null;
			}

			// Check if we encountered a terminal task and throw appropriate error
			TaskStatus terminalStatus = terminalStatusRef.get();
			if (terminalStatus != null) {
				throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
					.message("Cannot cancel task: already in terminal status '" + terminalStatus + "'")
					.data("taskId: " + taskId)
					.build();
			}

			return resultRef.get();
		});
	}

	@Override
	public Mono<Boolean> isCancellationRequested(String taskId, String sessionId) {
		return Mono.fromCallable(() -> {
			// Validate session access first
			TaskEntry entry = tasks.get(taskId);
			if (entry == null || !isSessionValid(entry, sessionId)) {
				return false;
			}
			return cancellationRequests.contains(taskId);
		});
	}

	@Override
	public Flux<Task> watchTaskUntilTerminal(String taskId, String sessionId, Duration timeout) {
		// Use the task's poll interval if available, otherwise default
		return getTask(taskId, sessionId).map(GetTaskFromStoreResult::task).flatMapMany(initialTask -> {
			long pollInterval = initialTask != null && initialTask.pollInterval() != null ? initialTask.pollInterval()
					: DEFAULT_POLL_INTERVAL_MS;

			return Flux.interval(Duration.ofMillis(pollInterval))
				.concatMap(tick -> getTask(taskId, sessionId).map(GetTaskFromStoreResult::task))
				.filter(task -> task != null)
				.takeUntil(Task::isTerminal)
				.timeout(timeout);
		})
			.switchIfEmpty(Flux.error(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
				.message("Task not found (may have expired after TTL): " + taskId)
				.build()));
	}

	/**
	 * Cleans up expired tasks based on TTL. Package-private for testing.
	 */
	void cleanupExpiredTasks() {
		Instant now = Instant.now();
		List<String> expiredTaskIds = new ArrayList<>();

		// Collect expired tasks and remove from maps (non-blocking)
		tasks.entrySet().removeIf(entry -> {
			Task task = entry.getValue().task();
			if (task.ttl() == null) {
				return false; // Null TTL means unlimited lifetime
			}
			Instant createdAt = Instant.parse(task.createdAt());
			Instant expiresAt = createdAt.plusMillis(task.ttl());
			if (now.isAfter(expiresAt)) {
				String taskId = entry.getKey();
				// Clean up related data BEFORE removing task entry for atomicity
				results.remove(taskId);
				cancellationRequests.remove(taskId);
				// Collect for parallel message queue cleanup
				expiredTaskIds.add(taskId);
				return true;
			}
			return false;
		});

		// Clean up message queues asynchronously to avoid blocking the cleanup thread
		if (messageQueue != null && !expiredTaskIds.isEmpty()) {
			Flux.fromIterable(expiredTaskIds)
				.flatMap(taskId -> messageQueue.clearTask(taskId).timeout(Duration.ofSeconds(1)).onErrorResume(e -> {
					logger.warn("Failed to clear task queue for {}", taskId, e);
					return Mono.empty();
				}))
				.subscribe(null, // no per-item handling needed
						error -> logger.warn("Error during message queue cleanup", error),
						() -> logger.debug("Completed cleanup of {} message queues", expiredTaskIds.size()));
		}
	}

	/**
	 * Shuts down the cleanup executor. Call this when the store is no longer needed.
	 */
	@Override
	public Mono<Void> shutdown() {
		return Mono.fromRunnable(() -> {
			cleanupExecutor.shutdown();
			try {
				if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
					cleanupExecutor.shutdownNow();
				}
			}
			catch (InterruptedException e) {
				cleanupExecutor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		});
	}

	/**
	 * Internal entry holding task data, originating request, optional context, and
	 * session ID.
	 */
	private record TaskEntry(Task task, McpSchema.Request originatingRequest, Object context, String sessionId) {
	}

}
