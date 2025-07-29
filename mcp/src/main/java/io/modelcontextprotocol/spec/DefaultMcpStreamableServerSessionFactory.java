package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.event.AsyncEventStore;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public class DefaultMcpStreamableServerSessionFactory implements McpStreamableServerSession.Factory {

	Duration requestTimeout;

	McpStreamableServerSession.InitRequestHandler initRequestHandler;

	Map<String, McpRequestHandler<?>> requestHandlers;

	Map<String, McpNotificationHandler> notificationHandlers;

	private AsyncEventStore eventStore;

	public DefaultMcpStreamableServerSessionFactory(Duration requestTimeout,
			McpStreamableServerSession.InitRequestHandler initRequestHandler,
			Map<String, McpRequestHandler<?>> requestHandlers, Map<String, McpNotificationHandler> notificationHandlers,
			AsyncEventStore eventStore) {
		this.requestTimeout = requestTimeout;
		this.initRequestHandler = initRequestHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
		this.eventStore = eventStore;
	}

	@Override
	public McpStreamableServerSession.McpStreamableServerSessionInit startSession(
			McpSchema.InitializeRequest initializeRequest) {
		return new McpStreamableServerSession.McpStreamableServerSessionInit(
				new McpStreamableServerSession(UUID.randomUUID().toString(), initializeRequest.capabilities(),
						initializeRequest.clientInfo(), requestTimeout, requestHandlers, notificationHandlers,
						eventStore),
				this.initRequestHandler.handle(initializeRequest));
	}

}
