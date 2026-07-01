/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for keep-alive failure eviction in
 * {@link HttpServletStreamableServerTransportProvider}.
 */
class HttpServletStreamableServerTransportProviderTests {

	@Test
	void firstKeepAliveFailureDoesNotEvictSession() throws Exception {
		HttpServletStreamableServerTransportProvider provider = createProvider();
		TrackingStreamableSession session = createSession("session-1");
		putSession(provider, session);

		provider.handleKeepAliveFailure(session, new RuntimeException("ping failed"));

		assertThat(sessions(provider)).containsEntry("session-1", session);
		assertThat(keepAliveFailureCounts(provider)).containsEntry("session-1", 1);
		assertThat(session.closeCount()).isZero();
	}

	@Test
	void repeatedKeepAliveFailuresEvictSession() throws Exception {
		HttpServletStreamableServerTransportProvider provider = createProvider();
		TrackingStreamableSession session = createSession("session-1");
		putSession(provider, session);

		provider.handleKeepAliveFailure(session, new RuntimeException("first failure"));
		provider.handleKeepAliveFailure(session, new RuntimeException("second failure"));
		provider.handleKeepAliveFailure(session, new RuntimeException("third failure"));

		assertThat(sessions(provider)).doesNotContainKey("session-1");
		assertThat(keepAliveFailureCounts(provider)).doesNotContainKey("session-1");
		assertThat(session.closeCount()).isOne();
	}

	@Test
	void successfulKeepAliveResetsFailureCount() throws Exception {
		HttpServletStreamableServerTransportProvider provider = createProvider();
		TrackingStreamableSession session = createSession("session-1");
		putSession(provider, session);

		provider.handleKeepAliveFailure(session, new RuntimeException("first failure"));
		provider.handleKeepAliveFailure(session, new RuntimeException("second failure"));
		provider.resetKeepAliveFailures(session);
		provider.handleKeepAliveFailure(session, new RuntimeException("failure after success"));

		assertThat(sessions(provider)).containsEntry("session-1", session);
		assertThat(keepAliveFailureCounts(provider)).containsEntry("session-1", 1);
		assertThat(session.closeCount()).isZero();
	}

	@Test
	void successfulKeepAliveFromReplacedSessionDoesNotResetReplacementFailureCount() throws Exception {
		HttpServletStreamableServerTransportProvider provider = createProvider();
		TrackingStreamableSession oldSession = createSession("session-1");
		TrackingStreamableSession replacementSession = createSession("session-1");
		putSession(provider, replacementSession);

		provider.handleKeepAliveFailure(replacementSession, new RuntimeException("replacement failure"));
		provider.resetKeepAliveFailures(oldSession);

		assertThat(sessions(provider)).containsEntry("session-1", replacementSession);
		assertThat(keepAliveFailureCounts(provider)).containsEntry("session-1", 1);
		assertThat(oldSession.closeCount()).isZero();
		assertThat(replacementSession.closeCount()).isZero();
	}

	@Test
	void keepAliveFailureDoesNotCloseReplacedSession() throws Exception {
		HttpServletStreamableServerTransportProvider provider = createProvider();
		TrackingStreamableSession oldSession = createSession("session-1");
		TrackingStreamableSession replacementSession = createSession("session-1");
		putSession(provider, replacementSession);

		provider.handleKeepAliveFailure(oldSession, new RuntimeException("first failure"));
		provider.handleKeepAliveFailure(oldSession, new RuntimeException("second failure"));
		provider.handleKeepAliveFailure(oldSession, new RuntimeException("third failure"));

		assertThat(sessions(provider)).containsEntry("session-1", replacementSession);
		assertThat(keepAliveFailureCounts(provider)).doesNotContainKey("session-1");
		assertThat(oldSession.closeCount()).isZero();
		assertThat(replacementSession.closeCount()).isZero();
	}

	private HttpServletStreamableServerTransportProvider createProvider() {
		return HttpServletStreamableServerTransportProvider.builder().jsonMapper(new GsonMcpJsonMapper()).build();
	}

	private TrackingStreamableSession createSession(String sessionId) {
		return new TrackingStreamableSession(sessionId);
	}

	private void putSession(HttpServletStreamableServerTransportProvider provider, TrackingStreamableSession session)
			throws Exception {
		sessions(provider).put(session.getId(), session);
	}

	@SuppressWarnings("unchecked")
	private Map<String, McpStreamableServerSession> sessions(HttpServletStreamableServerTransportProvider provider)
			throws Exception {
		Field field = HttpServletStreamableServerTransportProvider.class.getDeclaredField("sessions");
		field.setAccessible(true);
		return (Map<String, McpStreamableServerSession>) field.get(provider);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Integer> keepAliveFailureCounts(HttpServletStreamableServerTransportProvider provider)
			throws Exception {
		Field field = HttpServletStreamableServerTransportProvider.class.getDeclaredField("keepAliveFailureCounts");
		field.setAccessible(true);
		return (Map<String, Integer>) field.get(provider);
	}

	private static class TrackingStreamableSession extends McpStreamableServerSession {

		private final AtomicInteger closeCount = new AtomicInteger();

		TrackingStreamableSession(String id) {
			super(id, McpSchema.ClientCapabilities.builder().build(),
					new McpSchema.Implementation("test-client", "1.0.0"), Duration.ofSeconds(5), Map.of(), Map.of(),
					Mono::empty);
		}

		@Override
		public void close() {
			this.closeCount.incrementAndGet();
		}

		int closeCount() {
			return this.closeCount.get();
		}

	}

}
