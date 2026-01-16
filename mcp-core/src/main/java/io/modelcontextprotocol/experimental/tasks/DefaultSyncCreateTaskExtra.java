/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;

/**
 * Default implementation of {@link SyncCreateTaskExtra}.
 *
 * <p>
 * This implementation is created by {@link io.modelcontextprotocol.server.McpSyncServer}
 * when delegating to a tool's {@link SyncCreateTaskHandler}.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see SyncCreateTaskExtra
 * @see SyncCreateTaskHandler
 */
public class DefaultSyncCreateTaskExtra implements SyncCreateTaskExtra {

	private final TaskStore<McpSchema.ServerTaskPayloadResult> taskStore;

	private final TaskMessageQueue taskMessageQueue;

	private final McpSyncServerExchange exchange;

	private final String sessionId;

	private final Long requestTtl;

	private final McpSchema.Request originatingRequest;

	/**
	 * Creates a new DefaultSyncCreateTaskExtra instance.
	 * @param taskStore the task store for creating tasks (required)
	 * @param taskMessageQueue the message queue for task communication (may be null)
	 * @param exchange the server exchange for client interaction (required)
	 * @param sessionId the session ID for task isolation (required)
	 * @param requestTtl the TTL from the client request (may be null)
	 * @param originatingRequest the original MCP request that triggered task creation
	 * (required)
	 */
	public DefaultSyncCreateTaskExtra(TaskStore<McpSchema.ServerTaskPayloadResult> taskStore,
			TaskMessageQueue taskMessageQueue, McpSyncServerExchange exchange, String sessionId, Long requestTtl,
			McpSchema.Request originatingRequest) {
		Assert.notNull(taskStore, "taskStore must not be null");
		Assert.notNull(exchange, "exchange must not be null");
		Assert.notNull(sessionId, "sessionId must not be null");
		Assert.notNull(originatingRequest, "originatingRequest must not be null");

		this.taskStore = taskStore;
		this.taskMessageQueue = taskMessageQueue;
		this.exchange = exchange;
		this.sessionId = sessionId;
		this.requestTtl = requestTtl;
		this.originatingRequest = originatingRequest;
	}

	@Override
	public TaskStore<McpSchema.ServerTaskPayloadResult> taskStore() {
		return this.taskStore;
	}

	@Override
	public TaskMessageQueue taskMessageQueue() {
		return this.taskMessageQueue;
	}

	@Override
	public McpSyncServerExchange exchange() {
		return this.exchange;
	}

	@Override
	public String sessionId() {
		return this.sessionId;
	}

	@Override
	public Long requestTtl() {
		return this.requestTtl;
	}

	@Override
	public McpSchema.Request originatingRequest() {
		return this.originatingRequest;
	}

}
