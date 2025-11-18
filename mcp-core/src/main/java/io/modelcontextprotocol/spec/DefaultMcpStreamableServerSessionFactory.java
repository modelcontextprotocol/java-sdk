/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.common.McpTransportContext;
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
			final McpSchema.InitializeRequest initializeRequest) {
		final String sessionId = generateSessionId(null, initializeRequest);
		return new McpStreamableServerSession.McpStreamableServerSessionInit(
				new McpStreamableServerSession(sessionId, initializeRequest.capabilities(),
						initializeRequest.clientInfo(), requestTimeout, requestHandlers, notificationHandlers),
				this.initRequestHandler.handle(initializeRequest));
	}

	@Override
	public McpStreamableServerSession.McpStreamableServerSessionInit startSession(
			final McpTransportContext mcpTransportContext, final McpSchema.InitializeRequest initializeRequest) {
		final String sessionId = generateSessionId(mcpTransportContext, initializeRequest);
		return new McpStreamableServerSession.McpStreamableServerSessionInit(
				new McpStreamableServerSession(sessionId, initializeRequest.capabilities(),
						initializeRequest.clientInfo(), requestTimeout, requestHandlers, notificationHandlers),
				this.initRequestHandler.handle(initializeRequest));
	}

	/**
	 * An extensibility point to generate session IDs differently.
	 * @param mcpTransportContext transport context
	 * @param initializeRequest initialization request
	 * @return generated session ID
	 */
	protected String generateSessionId(McpTransportContext mcpTransportContext,
			McpSchema.InitializeRequest initializeRequest) {
		return UUID.randomUUID().toString();
	}

}
