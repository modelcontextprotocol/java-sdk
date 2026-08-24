/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import reactor.core.publisher.Mono;

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

	private final Function<String, Mono<Void>> onClose;

	private final JsonSchemaValidator jsonSchemaValidator;

	/**
	 * Constructs an instance.
	 * @param requestTimeout timeout for requests
	 * @param initRequestHandler initialization request handler
	 * @param requestHandlers map of MCP request handlers keyed by method name
	 * @param notificationHandlers map of MCP notification handlers keyed by method name
	 * @param onClose reactive callback invoked with the session ID when a session is
	 * closed
	 * @param jsonSchemaValidator optional validator threaded to sessions user-provided
	 * schema validation
	 */
	public DefaultMcpStreamableServerSessionFactory(Duration requestTimeout,
			McpStreamableServerSession.InitRequestHandler initRequestHandler,
			Map<String, McpRequestHandler<?>> requestHandlers, Map<String, McpNotificationHandler> notificationHandlers,
			Function<String, Mono<Void>> onClose, JsonSchemaValidator jsonSchemaValidator) {
		this.requestTimeout = requestTimeout;
		this.initRequestHandler = initRequestHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
		this.onClose = onClose;
		this.jsonSchemaValidator = jsonSchemaValidator;
	}

	/**
	 * Constructs an instance.
	 * @param requestTimeout timeout for requests
	 * @param initRequestHandler initialization request handler
	 * @param requestHandlers map of MCP request handlers keyed by method name
	 * @param notificationHandlers map of MCP notification handlers keyed by method name
	 * @param onClose reactive callback invoked with the session ID when a session is
	 * closed
	 */
	public DefaultMcpStreamableServerSessionFactory(Duration requestTimeout,
			McpStreamableServerSession.InitRequestHandler initRequestHandler,
			Map<String, McpRequestHandler<?>> requestHandlers, Map<String, McpNotificationHandler> notificationHandlers,
			Function<String, Mono<Void>> onClose) {
		this(requestTimeout, initRequestHandler, requestHandlers, notificationHandlers, onClose, null);
	}

	/**
	 * Constructs an instance.
	 * @param requestTimeout timeout for requests
	 * @param initRequestHandler initialization request handler
	 * @param requestHandlers map of MCP request handlers keyed by method name
	 * @param notificationHandlers map of MCP notification handlers keyed by method name
	 * @deprecated Use
	 * {@link #DefaultMcpStreamableServerSessionFactory(Duration, McpStreamableServerSession.InitRequestHandler, Map, Map, Function)}
	 * instead
	 */
	@Deprecated
	public DefaultMcpStreamableServerSessionFactory(Duration requestTimeout,
			McpStreamableServerSession.InitRequestHandler initRequestHandler,
			Map<String, McpRequestHandler<?>> requestHandlers,
			Map<String, McpNotificationHandler> notificationHandlers) {
		this(requestTimeout, initRequestHandler, requestHandlers, notificationHandlers, sessionId -> Mono.empty());
	}

	@Override
	public McpStreamableServerSession.McpStreamableServerSessionInit startSession(
			McpSchema.InitializeRequest initializeRequest) {
		String sessionId = UUID.randomUUID().toString();
		return new McpStreamableServerSession.McpStreamableServerSessionInit(
				new McpStreamableServerSession(sessionId, initializeRequest.capabilities(),
						initializeRequest.clientInfo(), requestTimeout, requestHandlers, notificationHandlers,
						() -> this.onClose.apply(sessionId), this.jsonSchemaValidator),
				this.initRequestHandler.handle(initializeRequest));
	}

}
