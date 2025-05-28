package io.modelcontextprotocol.spec;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class McpTransportSession {

	private final Disposable.Composite openConnections = Disposables.composite();

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private final AtomicReference<String> sessionId = new AtomicReference<>();

	public McpTransportSession() {
	}

	public String sessionId() {
		return this.sessionId.get();
	}

	public void setSessionId(String sessionId) {
		this.sessionId.set(sessionId);
	}

	public boolean markInitialized() {
		return this.initialized.compareAndSet(false, true);
	}

	public void addConnection(Disposable connection) {
		this.openConnections.add(connection);
	}

	public void removeConnection(Disposable connection) {
		this.openConnections.remove(connection);
	}

	public void close() {
		this.closeGracefully().subscribe();
	}

	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(this.openConnections::dispose);
	}

}
