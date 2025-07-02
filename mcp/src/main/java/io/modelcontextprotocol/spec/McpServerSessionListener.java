package io.modelcontextprotocol.spec;

/**
 * Listener for McpServerSession events.
 *
 * @param <T> The type of request object used in the session.
 * @author Allen Hu
 */
public interface McpServerSessionListener<T> {

	/**
	 * Called when a new session is connected.
	 * @param session The session that was connected.
	 * @param request The request object used in the session.
	 */
	void onConnection(McpServerSession session, T request);

	/**
	 * Called when a message is received.
	 * @param session The session that received the message.
	 * @param request The request object used in the session.
	 */
	void onMessage(McpServerSession session, T request);

}
