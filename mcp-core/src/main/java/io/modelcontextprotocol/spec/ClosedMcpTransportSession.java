/*
 * Copyright 2025-2025 the original author or authors.
 */
package io.modelcontextprotocol.spec;

import java.util.Optional;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

/**
 * Represents a closed MCP session, which may not be reused. All calls will throw a
 * {@link McpTransportSessionClosedException}.
 *
 * @param <CONNECTION> the resource representing the connection that the transport
 * manages.
 * @author Daniel Garnier-Moiroux
 */
public class ClosedMcpTransportSession<CONNECTION> implements McpTransportSession<CONNECTION> {

	private final String sessionId;

	public ClosedMcpTransportSession(@Nullable String sessionId) {
		this.sessionId = sessionId;
	}

	@Override
	public Optional<String> sessionId() {
		throw new McpTransportSessionClosedException(sessionId);
	}

	@Override
	public boolean markInitialized(String sessionId) {
		throw new McpTransportSessionClosedException(sessionId);
	}

	@Override
	public void addConnection(CONNECTION connection) {
		throw new McpTransportSessionClosedException(sessionId);
	}

	@Override
	public void removeConnection(CONNECTION connection) {
		throw new McpTransportSessionClosedException(sessionId);
	}

	@Override
	public void close() {

	}

	@Override
	public Publisher<Void> closeGracefully() {
		return Mono.empty();
	}

}
