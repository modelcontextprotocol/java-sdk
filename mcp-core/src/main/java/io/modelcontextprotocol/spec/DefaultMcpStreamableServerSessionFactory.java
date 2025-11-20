/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;

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
		this.requestTimeout = requestTimeout;
		this.initRequestHandler = initRequestHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	@Override
	public McpStreamableServerSession.McpStreamableServerSessionInit startSession(
			McpSchema.InitializeRequest initializeRequest) {
		return new McpStreamableServerSession.McpStreamableServerSessionInit(
				new McpStreamableServerSession(UUID.randomUUID().toString(), initializeRequest.capabilities(),
						initializeRequest.clientInfo(), requestTimeout, requestHandlers, notificationHandlers),
				this.initRequestHandler.handle(initializeRequest));
	}

}
