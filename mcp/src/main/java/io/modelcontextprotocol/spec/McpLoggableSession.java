package io.modelcontextprotocol.spec;

public interface McpLoggableSession extends McpSession {

	/**
	 * Set the minimum logging level for the client. Messages below this level will be
	 * filtered out.
	 * @param minLoggingLevel The minimum logging level
	 */
	void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel);

	boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel loggingLevel);

}
