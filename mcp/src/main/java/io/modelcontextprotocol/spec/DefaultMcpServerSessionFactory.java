package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.server.McpInitRequestHandler;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;

import java.time.Duration;
import java.util.Map;

/**
 * The default implementation of {@link McpServerSession.Factory}.
 *
 * @author He-Pin
 */
public class DefaultMcpServerSessionFactory implements McpServerSession.Factory {

	Duration requestTimeout;

	McpInitRequestHandler initHandler;

	Map<String, McpRequestHandler<?>> requestHandlers;

	Map<String, McpNotificationHandler> notificationHandlers;

	public DefaultMcpServerSessionFactory(final Duration requestTimeout, final McpInitRequestHandler initHandler,
			final Map<String, McpRequestHandler<?>> requestHandlers,
			final Map<String, McpNotificationHandler> notificationHandlers) {
		this.requestTimeout = requestTimeout;
		this.initHandler = initHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	@Override
	public McpServerSession create(final McpServerTransport sessionTransport) {
		final String sessionId = generateSessionId(sessionTransport);
		return new McpServerSession(sessionId, requestTimeout, sessionTransport, initHandler, requestHandlers,
				notificationHandlers);
	}

	/**
	 * Generate a unique session ID for the given transport.
	 * @param sessionTransport the transport
	 * @return unique session ID
	 */
	protected String generateSessionId(final McpServerTransport sessionTransport) {
		return java.util.UUID.randomUUID().toString();
	}

}
