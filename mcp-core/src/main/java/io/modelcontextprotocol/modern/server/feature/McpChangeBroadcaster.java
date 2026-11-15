/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

/**
 * The sink side of server changes: pushes a {@link ServerChange} to the subscribers of
 * the matching {@link McpChangePublisher}.
 *
 * @author Dariusz Jędrzejczyk
 * @see McpChangeFeed
 */
public interface McpChangeBroadcaster {

	void broadcast(ServerChange change);

}
