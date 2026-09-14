/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link McpStreamableServerSession}.
 */
class McpStreamableServerSessionTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private McpStreamableServerSession session() {
		return new McpStreamableServerSession("session-1", McpSchema.ClientCapabilities.builder().build(),
				new McpSchema.Implementation("test-client", "1.0.0"), TIMEOUT, Map.of(), Map.of());
	}

	@Test
	void replacedListeningStreamHasItsConnectionReleased() {
		var session = session();
		var firstTransport = new RecordingTransport();

		session.listeningStream(firstTransport);
		assertThat(firstTransport.closed).isFalse();

		session.listeningStream(new RecordingTransport());
		assertThat(firstTransport.closed).isTrue();
	}

	@Test
	void replacingListeningStreamKeepsItsPendingRequestsResolvable() {
		var session = session();
		var firstTransport = new RecordingTransport();
		session.listeningStream(firstTransport);

		// Server-initiated requests (sampling, elicitation, roots/list) are sent on the
		// listening SSE stream, but the client answers them with a separate HTTP POST
		// which outlives that stream.
		var pending = session.sendRequest("sampling/createMessage", null, new TypeRef<String>() {
		}).toFuture();
		assertThat(firstTransport.sent).hasSize(1);
		var requestId = ((McpSchema.JSONRPCRequest) firstTransport.sent.peek()).id();

		// The client reconnects with a Last-Event-ID header, replacing the listening
		// stream. The request sent on the replaced stream must stay pending.
		session.listeningStream(new RecordingTransport());
		assertThat(pending).isNotDone();

		session.accept(McpSchema.JSONRPCResponse.result(requestId, "response-value")).block(TIMEOUT);

		assertThat(pending).succeedsWithin(TIMEOUT).isEqualTo("response-value");
	}

	@Test
	void closingTheSessionReleasesTheConnectionOfItsStreams() {
		var session = session();
		var transport = new RecordingTransport();
		session.listeningStream(transport);

		session.close();

		assertThat(transport.closed).isTrue();
	}

	static class RecordingTransport implements McpStreamableServerTransport {

		final Queue<McpSchema.JSONRPCMessage> sent = new ConcurrentLinkedQueue<>();

		volatile boolean closed;

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
			return Mono.fromRunnable(() -> this.sent.add(message));
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return sendMessage(message, null);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> this.closed = true);
		}

		@Override
		public void close() {
			this.closed = true;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return (T) data;
		}

	}

}
