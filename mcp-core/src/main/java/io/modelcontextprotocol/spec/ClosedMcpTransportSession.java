/*
 * Copyright 2025-2025 the original author or authors.
 */
package io.modelcontextprotocol.spec;

import java.util.Optional;

import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

/**
 * Represents a closed MCP session, which may not be reused.
 *
 * @author Daniel Garnier-Moiroux
 * @author Dariusz Jędrzejczyk
 */
public final class ClosedMcpTransportSession implements McpTransportSession<Disposable> {

	public static final ClosedMcpTransportSession INSTANCE = new ClosedMcpTransportSession();

	private ClosedMcpTransportSession() {
	}

	@Override
	public Optional<String> sessionId() {
		return Optional.empty();
	}

	@Override
	public boolean markInitialized(String sessionId) {
		throw new IllegalStateException("MCP Session is already closed");
	}

	@Override
	public void addConnection(Disposable connection) {
		throw new IllegalStateException("MCP Session is already closed");
	}

	@Override
	public void removeConnection(Disposable connection) {
		throw new IllegalStateException("MCP Session is already closed");
	}

	@Override
	public void close() {

	}

	@Override
	public Publisher<Void> closeGracefully() {
		return Mono.empty();
	}

}
