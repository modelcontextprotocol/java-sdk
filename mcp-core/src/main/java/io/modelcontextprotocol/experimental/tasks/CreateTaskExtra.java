/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.function.Consumer;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Context passed to {@link CreateTaskHandler} providing access to task infrastructure and
 * request metadata.
 * <p />
 * Example usage:
 *
 * <pre>{@code
 * CreateTaskHandler handler = (args, extra) -> {
 *     // Decide TTL based on request or use a default
 *     long ttl = extra.requestTtl() != null
 *         ? Math.min(extra.requestTtl(), Duration.ofMinutes(30).toMillis())
 *         : Duration.ofMinutes(5).toMillis();
 *
 *     return extra.taskStore()
 *         .createTask(CreateTaskOptions.builder()
 *             .requestedTtl(ttl)
 *             .sessionId(extra.sessionId())
 *             .build())
 *         .flatMap(task -> {
 *             // Use exchange for client communication
 *             startBackgroundWork(task.taskId(), args, extra.exchange()).subscribe();
 *             return Mono.just(new McpSchema.CreateTaskResult(task, null));
 *         });
 * };
 * }</pre>
 *
 * <p>
 * <strong>Design Note:</strong> This interface mirrors {@link SyncCreateTaskExtra} for
 * the synchronous API. The duplication is intentional because async methods return
 * {@link Mono} while sync methods return values directly. This separation allows for
 * proper reactive and blocking semantics without forcing one paradigm on the other.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see CreateTaskHandler
 * @see SyncCreateTaskExtra
 * @see TaskStore
 * @see TaskMessageQueue
 */
public interface CreateTaskExtra {

	/**
	 * The task store for creating and managing tasks.
	 *
	 * <p>
	 * Tools use this to create tasks with their desired configuration:
	 *
	 * <pre>{@code
	 * extra.taskStore().createTask(CreateTaskOptions.builder()
	 *     .requestedTtl(Duration.ofMinutes(5).toMillis())
	 *     .pollInterval(Duration.ofSeconds(1).toMillis())
	 *     .sessionId(extra.sessionId())
	 *     .build());
	 * }</pre>
	 * @return the TaskStore instance
	 */
	TaskStore<McpSchema.ServerTaskPayloadResult> taskStore();

	/**
	 * The message queue for task communication during INPUT_REQUIRED state.
	 *
	 * <p>
	 * Use this for interactive tasks that need to communicate with the client during
	 * execution.
	 * @return the TaskMessageQueue instance, or null if not configured
	 */
	TaskMessageQueue taskMessageQueue();

	/**
	 * The server exchange for client interaction.
	 *
	 * <p>
	 * Provides access to session-scoped operations like sending notifications to the
	 * client.
	 * @return the McpAsyncServerExchange instance
	 */
	McpAsyncServerExchange exchange();

	/**
	 * Session ID for task isolation.
	 *
	 * <p>
	 * Tasks created with this session ID will only be visible to the same session,
	 * enabling proper multi-client isolation.
	 * @return the session ID string
	 */
	String sessionId();

	/**
	 * Request-specified TTL from client (may be null).
	 *
	 * <p>
	 * If the client specified a TTL in the task metadata of their request, it will be
	 * available here. Tools can use this to implement client-controlled TTL policies:
	 *
	 * <pre>{@code
	 * // Client can lower but not raise TTL
	 * long maxTtl = Duration.ofMinutes(30).toMillis();
	 * long ttl = extra.requestTtl() != null
	 *     ? Math.min(extra.requestTtl(), maxTtl)
	 *     : maxTtl;
	 * }</pre>
	 * @return the TTL in milliseconds from the client request, or null if not specified
	 */
	Long requestTtl();

	/**
	 * The original MCP request that triggered this task creation.
	 *
	 * <p>
	 * For tool calls, this will be a {@link McpSchema.CallToolRequest} containing the
	 * tool name, arguments, and any task metadata. This request is stored alongside the
	 * task and can be retrieved later via the task store, eliminating the need for
	 * separate task-to-tool mapping.
	 * @return the original request that triggered task creation
	 */
	McpSchema.Request originatingRequest();

	// --------------------------
	// Convenience Methods
	// --------------------------

	/**
	 * Convenience method to create a task with default options derived from this context.
	 *
	 * <p>
	 * This method automatically uses {@link #originatingRequest()}, {@link #sessionId()},
	 * and {@link #requestTtl()} from this context, eliminating common boilerplate:
	 *
	 * <pre>{@code
	 * // Instead of:
	 * extra.taskStore().createTask(CreateTaskOptions.builder(extra.originatingRequest())
	 *     .sessionId(extra.sessionId())
	 *     .requestedTtl(extra.requestTtl())
	 *     .build())
	 *
	 * // You can simply use:
	 * extra.createTask()
	 * }</pre>
	 * @return Mono that completes with the created Task
	 */
	default Mono<McpSchema.Task> createTask() {
		return taskStore().createTask(CreateTaskOptions.builder(originatingRequest())
			.sessionId(sessionId())
			.requestedTtl(requestTtl())
			.build());
	}

	/**
	 * Convenience method to create a task with custom options, but inheriting session
	 * context.
	 *
	 * <p>
	 * This method pre-populates the builder with {@link #originatingRequest()},
	 * {@link #sessionId()}, and {@link #requestTtl()}, then allows customization:
	 *
	 * <pre>{@code
	 * // Create a task with custom poll interval:
	 * extra.createTask(opts -> opts.pollInterval(500L))
	 *
	 * // Create a task with custom TTL (ignoring client request):
	 * extra.createTask(opts -> opts.requestedTtl(Duration.ofMinutes(10).toMillis()))
	 * }</pre>
	 * @param customizer function to customize options beyond the defaults
	 * @return Mono that completes with the created Task
	 */
	default Mono<McpSchema.Task> createTask(Consumer<CreateTaskOptions.Builder> customizer) {
		CreateTaskOptions.Builder builder = CreateTaskOptions.builder(originatingRequest())
			.sessionId(sessionId())
			.requestedTtl(requestTtl());
		customizer.accept(builder);
		return taskStore().createTask(builder.build());
	}

	/**
	 * Create a TaskContext for managing the given task's lifecycle.
	 *
	 * <p>
	 * This convenience method creates a TaskContext that uses this extra's task store and
	 * message queue, reducing boilerplate in task handlers:
	 *
	 * <pre>{@code
	 * extra.createTask()
	 *     .map(task -> extra.createTaskContext(task))
	 *     .flatMap(ctx -> {
	 *         // Use ctx to update status, send messages, etc.
	 *         return ctx.complete(result);
	 *     });
	 * }</pre>
	 * @param task the task to create a context for
	 * @return a TaskContext bound to the given task and this extra's infrastructure
	 */
	default TaskContext createTaskContext(McpSchema.Task task) {
		return new DefaultTaskContext<>(task.taskId(), sessionId(), taskStore(), taskMessageQueue());
	}

}
