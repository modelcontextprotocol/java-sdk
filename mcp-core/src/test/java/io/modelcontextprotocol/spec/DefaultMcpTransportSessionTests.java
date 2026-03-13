/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DefaultMcpTransportSession}.
 */
class DefaultMcpTransportSessionTests {

	@Test
	void shouldDisposeConnectionsEvenIfOnCloseErrors() {
		// Given
		AtomicBoolean disposed = new AtomicBoolean(false);
		Disposable connection = () -> disposed.set(true);

		DefaultMcpTransportSession session = new DefaultMcpTransportSession(
				sessionId -> Mono.error(new RuntimeException("onClose failure")));
		session.addConnection(connection);

		// When
		StepVerifier.create(session.closeGracefully())
			.expectErrorMatches(t -> t instanceof RuntimeException && "onClose failure".equals(t.getMessage()))
			.verify();

		// Then
		assertThat(disposed.get()).as("Connection should be disposed even if onClose fails").isTrue();
	}

	@Test
	void shouldDisposeConnectionsOnSuccessfulClose() {
		// Given
		AtomicBoolean disposed = new AtomicBoolean(false);
		Disposable connection = () -> disposed.set(true);

		DefaultMcpTransportSession session = new DefaultMcpTransportSession(sessionId -> Mono.empty());
		session.addConnection(connection);

		// When
		StepVerifier.create(session.closeGracefully()).verifyComplete();

		// Then
		assertThat(disposed.get()).as("Connection should be disposed on successful close").isTrue();
	}

}
