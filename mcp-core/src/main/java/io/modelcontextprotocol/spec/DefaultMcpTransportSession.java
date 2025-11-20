/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

/**
 * Default implementation of {@link McpTransportSession} which manages the open
 * connections using tye {@link Disposable} type and allows to perform clean up using the
 * {@link Disposable#dispose()} method.
 *
 * @author Dariusz Jędrzejczyk
 */
public class DefaultMcpTransportSession implements McpTransportSession<Disposable> {

	private static final Logger logger = LoggerFactory.getLogger(DefaultMcpTransportSession.class);

	private final Disposable.Composite openConnections = Disposables.composite();

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private final AtomicReference<String> sessionId = new AtomicReference<>();

	private final Function<String, Publisher<Void>> onClose;

	public DefaultMcpTransportSession(Function<String, Publisher<Void>> onClose) {
		this.onClose = onClose;
	}

	@Override
	public Optional<String> sessionId() {
		return Optional.ofNullable(this.sessionId.get());
	}

	@Override
	public boolean markInitialized(String sessionId) {
		boolean flipped = this.initialized.compareAndSet(false, true);
		if (flipped) {
			this.sessionId.set(sessionId);
			logger.debug("Established session with id {}", sessionId);
		}
		else {
			if (sessionId != null && !sessionId.equals(this.sessionId.get())) {
				logger.warn("Different session id provided in response. Expecting {} but server returned {}",
						this.sessionId.get(), sessionId);
			}
		}
		return flipped;
	}

	@Override
	public void addConnection(Disposable connection) {
		this.openConnections.add(connection);
	}

	@Override
	public void removeConnection(Disposable connection) {
		this.openConnections.remove(connection);
	}

	@Override
	public void close() {
		this.closeGracefully().subscribe();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.from(this.onClose.apply(this.sessionId.get()))
			.then(Mono.fromRunnable(this.openConnections::dispose));
	}

}
