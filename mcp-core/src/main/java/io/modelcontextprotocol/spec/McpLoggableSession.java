/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import org.springframework.security.core.Authentication;

/**
 * An {@link McpSession} which is capable of processing logging notifications and keeping
 * track of a min logging level.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpLoggableSession extends McpSession {

	/**
	 * Set the minimum logging level for the client. Messages below this level will be
	 * filtered out.
	 * @param minLoggingLevel The minimum logging level
	 */
	void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel);

	/**
	 * Allows checking whether a particular logging level is allowed.
	 * @param loggingLevel the level to check
	 * @return whether the logging at the specified level is permitted.
	 */
	boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel loggingLevel);

	/**
	 * Veoci customization: retrieve the Spring Security {@link Authentication} captured
	 * for this session by Spring Security filters when the session was established. This
	 * lets tool handlers, which run on threads where the {@code SecurityContextHolder}
	 * thread-local is empty, recover the caller identity from the session/exchange.
	 *
	 * <p>
	 * Implemented by both the SSE ({@link McpServerSession}) and Streamable HTTP
	 * ({@link McpStreamableServerSession}) server sessions. Defaults to {@code null} for
	 * sessions that do not carry authentication.
	 * @return the captured authentication, or {@code null} if none was captured
	 */
	default Authentication getAuthentication() {
		return null;
	}

}
