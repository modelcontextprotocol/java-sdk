/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.Collection;

/**
 * Strategy interface for storing and retrieving MCP server sessions. This abstraction
 * allows the session storage mechanism to be customized, enabling implementations such as
 * in-memory (default), Redis-backed, JDBC-backed, or any distributed store.
 *
 * <p>
 * The default implementation {@link InMemoryMcpSessionStore} uses a
 * {@link java.util.concurrent.ConcurrentHashMap} which is suitable for single-instance
 * deployments. For distributed or multi-instance deployments, a custom implementation
 * backed by a distributed data store should be used.
 *
 * <p>
 * Note: {@link McpStreamableServerSession} objects contain active transport connections
 * (SSE streams) that are inherently tied to the JVM instance. A distributed session store
 * therefore stores the session reference per-node and coordinates session lifecycle
 * across nodes (e.g., detecting when a session was created on a different node).
 *
 * @author WeiLin Wang
 * @see InMemoryMcpSessionStore
 * @see McpStreamableServerSession
 */
public interface McpSessionStore {

	/**
	 * Stores a session with the given ID. If a session with the same ID already exists,
	 * it will be replaced.
	 * @param sessionId the unique session identifier
	 * @param session the session to store
	 */
	void save(String sessionId, McpStreamableServerSession session);

	/**
	 * Retrieves a session by its ID.
	 * @param sessionId the unique session identifier
	 * @return the session associated with the given ID, or {@code null} if not found
	 */
	McpStreamableServerSession get(String sessionId);

	/**
	 * Removes a session by its ID.
	 * @param sessionId the unique session identifier
	 * @return the previously stored session, or {@code null} if no session was stored
	 * with the given ID
	 */
	McpStreamableServerSession remove(String sessionId);

	/**
	 * Returns all currently stored sessions.
	 * @return a collection of all stored sessions; never {@code null}
	 */
	Collection<McpStreamableServerSession> values();

	/**
	 * Returns whether there are any sessions stored.
	 * @return {@code true} if no sessions are stored, {@code false} otherwise
	 */
	boolean isEmpty();

	/**
	 * Returns the number of stored sessions.
	 * @return the session count
	 */
	int size();

	/**
	 * Removes all stored sessions.
	 */
	void clear();

}
