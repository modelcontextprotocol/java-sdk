/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that server-initiated requests and notifications tolerate a not-yet registered
 * listening stream: they are retried until the client opens the GET /mcp stream and fail
 * after the grace period if it never appears. This is the race behind intermittent
 * {@code Stream unavailable for session} failures right after {@code initialize}.
 */
class McpStreamableServerSessionMissingStreamTests {

	private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<Map<String, Object>>() {
	};

	private final RecordingTransport transport = new RecordingTransport();

	@Test
	void notificationIsRetriedUntilListeningStreamRegisters() throws Exception {
		var session = newSession(Map.of(), Map.of());
		var delivered = new CountDownLatch(1);

		var disposable = session.sendNotification("notifications/test", Map.of()).subscribe(v -> {
		}, e -> delivered.countDown(), delivered::countDown);
		try {
			assertThat(this.transport.sent).isEmpty();
			assertThat(delivered.getCount()).isEqualTo(1);

			session.listeningStream(this.transport);
			assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			disposable.dispose();
		}

		assertThat(this.transport.sent).hasSize(1);
	}

	@Test
	void requestIsRetriedAndResponseCorrelatedAfterRegistration() throws Exception {
		var session = newSession(Map.of(), Map.of());
		var response = new AtomicReference<Map<String, Object>>();
		var error = new AtomicReference<Throwable>();
		var completed = new CountDownLatch(1);

		var disposable = session.sendRequest("sampling/createMessage", Map.of("messages", List.of()), MAP_TYPE)
			.subscribe(response::set, e -> {
				error.set(e);
				completed.countDown();
			}, completed::countDown);
		try {
			assertThat(this.transport.sent).isEmpty();
			session.listeningStream(this.transport);
			assertThat(this.transport.firstMessage.await(2, TimeUnit.SECONDS)).isTrue();

			var request = (JSONRPCRequest) this.transport.sent.get(0);
			session.accept(JSONRPCResponse.result(request.id(), Map.of("stopReason", "endTurn")))
				.block(Duration.ofSeconds(1));

			assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
			if (error.get() != null) {
				throw new AssertionError("Request did not complete successfully", error.get());
			}
		}
		finally {
			disposable.dispose();
		}

		assertThat(response.get()).containsEntry("stopReason", "endTurn");
	}

	@Test
	void sendRequestFailsAfterGracePeriodWithoutListeningStream() {
		var session = newSession(Map.of(), Map.of());

		StepVerifier.withVirtualTime(() -> session.sendRequest("sampling/createMessage", Map.of(), MAP_TYPE))
			.thenAwait(Duration.ofSeconds(6))
			.expectErrorSatisfies(e -> assertThat(e).hasRootCauseMessage("Stream unavailable for session test-session"))
			.verify(Duration.ofSeconds(2));
	}

	@Test
	void notificationHandlerCanPushRequestBeforeStreamRegistered() throws Exception {
		var delivered = new AtomicReference<McpSchema.ListRootsResult>();
		McpNotificationHandler initializedHandler = (exchange,
				params) -> exchange.listRoots().doOnNext(delivered::set).then();

		var session = newSession(Map.of(), Map.of("notifications/initialized", initializedHandler));
		var handled = new CountDownLatch(1);
		var error = new AtomicReference<Throwable>();

		var disposable = session.accept(new McpSchema.JSONRPCNotification("notifications/initialized", Map.of()))
			.subscribe(v -> {
			}, e -> {
				error.set(e);
				handled.countDown();
			}, handled::countDown);
		try {
			assertThat(this.transport.sent).isEmpty();
			session.listeningStream(this.transport);
			assertThat(this.transport.firstMessage.await(2, TimeUnit.SECONDS)).isTrue();

			var request = this.transport.sent.stream()
				.filter(JSONRPCRequest.class::isInstance)
				.map(JSONRPCRequest.class::cast)
				.findFirst()
				.orElseThrow();
			session
				.accept(JSONRPCResponse.result(request.id(),
						Map.of("roots", List.of(Map.of("name", "workspace", "uri", "file:///ws")))))
				.block(Duration.ofSeconds(1));

			assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();
			if (error.get() != null) {
				throw new AssertionError("Handler did not complete successfully", error.get());
			}
		}
		finally {
			disposable.dispose();
		}

		assertThat(delivered.get().roots()).singleElement().satisfies(root -> {
			assertThat(root.name()).isEqualTo("workspace");
			assertThat(root.uri()).isEqualTo("file:///ws");
		});
	}

	private McpStreamableServerSession newSession(Map<String, McpRequestHandler<?>> requests,
			Map<String, McpNotificationHandler> notifications) {
		return new McpStreamableServerSession("test-session", McpSchema.ClientCapabilities.builder().build(),
				new McpSchema.Implementation("test-client", "1.0"), Duration.ofSeconds(10), requests, notifications);
	}

	private static final class RecordingTransport implements McpStreamableServerTransport {

		final CopyOnWriteArrayList<JSONRPCMessage> sent = new CopyOnWriteArrayList<>();

		final CountDownLatch firstMessage = new CountDownLatch(1);

		@Override
		public Mono<Void> sendMessage(JSONRPCMessage message, String messageId) {
			this.sent.add(message);
			this.firstMessage.countDown();
			return Mono.empty();
		}

		@Override
		public Mono<Void> sendMessage(JSONRPCMessage message) {
			return sendMessage(message, "ignored");
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			// Minimal hand-rolled conversion: mcp-core tests run without a JSON binding
			// module on the classpath, so McpJsonDefaults is unavailable here.
			if (typeRef.getType() == McpSchema.ListRootsResult.class) {
				var map = (Map<String, Object>) data;
				var roots = ((List<Map<String, Object>>) map.get("roots")).stream()
					.map(root -> new McpSchema.Root((String) root.get("uri"), (String) root.get("name"), null))
					.toList();
				return (T) new McpSchema.ListRootsResult(roots, (String) map.get("nextCursor"), null);
			}
			return (T) data;
		}

	}

}
