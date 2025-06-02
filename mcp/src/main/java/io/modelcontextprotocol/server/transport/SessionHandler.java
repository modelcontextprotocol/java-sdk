/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handler interface for session lifecycle and runtime events in the Streamable HTTP
 * transport.
 *
 * <p>
 * This interface provides hooks for monitoring and responding to various session-related
 * events that occur during the operation of the HTTP-based MCP server transport.
 * Implementations can use these callbacks to:
 * <ul>
 * <li>Log session activities</li>
 * <li>Implement custom session management logic</li>
 * <li>Handle error conditions</li>
 * <li>Perform cleanup operations</li>
 * </ul>
 *
 * @author Zachary German
 */
public interface SessionHandler {

	/**
	 * Called when a new session is created.
	 * @param sessionId The ID of the newly created session
	 * @param context Additional context information (may be null)
	 */
	void onSessionCreate(String sessionId, Object context);

	/**
	 * Called when a session is closed.
	 * @param sessionId The ID of the closed session
	 */
	void onSessionClose(String sessionId);

	/**
	 * Called when a session is not found for a given session ID.
	 * @param sessionId The session ID that was not found
	 * @param request The HTTP request that referenced the missing session
	 * @param response The HTTP response that will be sent to the client
	 */
	void onSessionNotFound(String sessionId, HttpServletRequest request, HttpServletResponse response);

	/**
	 * Called when an error occurs while sending a notification to a session.
	 * @param sessionId The ID of the session where the error occurred
	 * @param error The error that occurred
	 */
	void onSendNotificationError(String sessionId, Throwable error);

}