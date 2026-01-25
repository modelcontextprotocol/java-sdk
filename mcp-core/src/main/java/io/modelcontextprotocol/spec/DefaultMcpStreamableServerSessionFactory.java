/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.experimental.tasks.TaskMessageQueue;
import io.modelcontextprotocol.experimental.tasks.TaskStore;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * A default implementation of {@link McpStreamableServerSession.Factory}.
 *
 * @author Dariusz Jędrzejczyk
 */
public class DefaultMcpStreamableServerSessionFactory implements McpStreamableServerSession.Factory {

	Duration requestTimeout;

	McpStreamableServerSession.InitRequestHandler initRequestHandler;

	Map<String, McpRequestHandler<?>> requestHandlers;

	Map<String, McpNotificationHandler> notificationHandlers;

	TaskMessageQueue taskMessageQueue;

	TaskStore<?> taskStore;

	/**
	 * Constructs an instance
	 * @param requestTimeout timeout for requests
	 * @param initRequestHandler initialization request handler
	 * @param requestHandlers map of MCP request handlers keyed by method name
	 * @param notificationHandlers map of MCP notification handlers keyed by method name
	 */
	public DefaultMcpStreamableServerSessionFactory(Duration requestTimeout,
			McpStreamableServerSession.InitRequestHandler initRequestHandler,
			Map<String, McpRequestHandler<?>> requestHandlers,
			Map<String, McpNotificationHandler> notificationHandlers) {
		this(requestTimeout, initRequestHandler, requestHandlers, notificationHandlers, null, null);
	}

	/**
	 * Constructs an instance with task infrastructure for side-channeling support.
	 * @param requestTimeout timeout for requests
	 * @param initRequestHandler initialization request handler
	 * @param requestHandlers map of MCP request handlers keyed by method name
	 * @param notificationHandlers map of MCP notification handlers keyed by method name
	 * @param taskMessageQueue optional task message queue for side-channeling support
	 * @param taskStore optional task store for side-channeling support
	 */
	public DefaultMcpStreamableServerSessionFactory(Duration requestTimeout,
			McpStreamableServerSession.InitRequestHandler initRequestHandler,
			Map<String, McpRequestHandler<?>> requestHandlers, Map<String, McpNotificationHandler> notificationHandlers,
			TaskMessageQueue taskMessageQueue, TaskStore<?> taskStore) {
		this.requestTimeout = requestTimeout;
		this.initRequestHandler = initRequestHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
		this.taskMessageQueue = taskMessageQueue;
		this.taskStore = taskStore;
	}

	@Override
	public McpStreamableServerSession.McpStreamableServerSessionInit startSession(
			McpSchema.InitializeRequest initializeRequest) {
		return new McpStreamableServerSession.McpStreamableServerSessionInit(
				new McpStreamableServerSession(UUID.randomUUID().toString(), initializeRequest.capabilities(),
						initializeRequest.clientInfo(), requestTimeout, requestHandlers, notificationHandlers,
						taskMessageQueue, taskStore),
				this.initRequestHandler.handle(initializeRequest));
	}

}
