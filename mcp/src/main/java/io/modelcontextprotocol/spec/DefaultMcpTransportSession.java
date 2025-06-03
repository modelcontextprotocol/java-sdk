package io.modelcontextprotocol.spec;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultMcpTransportSession implements McpTransportSession<Disposable> {

	private final Disposable.Composite openConnections = Disposables.composite();

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private final AtomicReference<String> sessionId = new AtomicReference<>();

	public DefaultMcpTransportSession() {
	}

	@Override
	public Optional<String> sessionId() {
		return Optional.ofNullable(this.sessionId.get());
	}

	@Override
	public void setSessionId(String sessionId) {
		this.sessionId.set(sessionId);
	}

	@Override
	public boolean markInitialized() {
		return this.initialized.compareAndSet(false, true);
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
		return Mono.fromRunnable(this.openConnections::dispose);
	}

}
