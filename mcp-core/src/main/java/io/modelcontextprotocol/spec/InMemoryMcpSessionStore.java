/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory implementation of {@link McpSessionStore} backed by a
 * {@link ConcurrentHashMap}. This implementation is suitable for single-instance
 * deployments where session state does not need to be shared across multiple server
 * instances.
 *
 * <p>
 * This is the default session store used by
 * {@link io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider}
 * when no custom {@link McpSessionStore} is provided.
 *
 * @author WeiLin Wang
 * @see McpSessionStore
 */
public class InMemoryMcpSessionStore implements McpSessionStore {

	private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

	@Override
	public void save(String sessionId, McpStreamableServerSession session) {
		this.sessions.put(sessionId, session);
	}

	@Override
	public McpStreamableServerSession get(String sessionId) {
		return this.sessions.get(sessionId);
	}

	@Override
	public McpStreamableServerSession remove(String sessionId) {
		return this.sessions.remove(sessionId);
	}

	@Override
	public Collection<McpStreamableServerSession> values() {
		return this.sessions.values();
	}

	@Override
	public boolean isEmpty() {
		return this.sessions.isEmpty();
	}

	@Override
	public int size() {
		return this.sessions.size();
	}

	@Override
	public void clear() {
		this.sessions.clear();
	}

}
