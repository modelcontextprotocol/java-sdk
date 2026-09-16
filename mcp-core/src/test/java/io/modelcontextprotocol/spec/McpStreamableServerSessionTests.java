/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpRequestHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link McpStreamableServerSession}.
 */
class McpStreamableServerSessionTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private McpStreamableServerSession session() {
		return session(Map.of());
	}

	private McpStreamableServerSession session(Map<String, McpRequestHandler<?>> requestHandlers) {
		return new McpStreamableServerSession("session-1", McpSchema.ClientCapabilities.builder().build(),
				new McpSchema.Implementation("test-client", "1.0.0"), TIMEOUT, requestHandlers, Map.of());
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

	@Test
	void closingTheSessionFailsThePendingRequestsOfReleasedStreams() {
		var session = session();
		session.listeningStream(new RecordingTransport());

		var pending = session.sendRequest("sampling/createMessage", null, new TypeRef<String>() {
		}).toFuture();

		// The stream the request was sent on is released, which leaves the request
		// pending: a reconnecting client can still answer it over a separate POST
		session.releaseListeningStream();
		assertThat(pending).isNotDone();

		// The client cannot respond on a closed session, the pending request must be
		// failed.
		session.close();

		assertThat(pending).failsWithin(TIMEOUT).withThrowableThat().havingCause().withMessage("Session closed");
	}

	@Test
	void closingTheSessionGracefullyFailsThePendingRequestsOfReleasedStreams() {
		var session = session();
		session.listeningStream(new RecordingTransport());

		var pending = session.sendRequest("sampling/createMessage", null, new TypeRef<String>() {
		}).toFuture();

		session.releaseListeningStream();
		assertThat(pending).isNotDone();

		session.closeGracefully().block(TIMEOUT);

		assertThat(pending).failsWithin(TIMEOUT).withThrowableThat().havingCause().withMessage("Session closed");
	}

	@Test
	void closingAStreamFailsOnlyItsOwnPendingRequests() {
		var session = session();
		var listeningTransport = new RecordingTransport();
		session.listeningStream(listeningTransport);

		var onListeningStream = session.sendRequest("sampling/createMessage", null, new TypeRef<String>() {
		}).toFuture();

		// A POST response stream carries its own server-initiated requests, which can be
		// closed independently
		var responseTransport = new RecordingTransport();
		var responseStream = session.new McpStreamableServerSessionStream(responseTransport);
		var onResponseStream = responseStream.sendRequest("elicitation/create", null, new TypeRef<String>() {
		}).toFuture();

		responseStream.close();

		assertThat(onResponseStream).failsWithin(TIMEOUT)
			.withThrowableThat()
			.havingCause()
			.withMessage("Stream closed");
		assertThat(onListeningStream).isNotDone();

		var requestId = ((McpSchema.JSONRPCRequest) listeningTransport.sent.peek()).id();
		session.accept(McpSchema.JSONRPCResponse.result(requestId, "response-value")).block(TIMEOUT);
		assertThat(onListeningStream).succeedsWithin(TIMEOUT).isEqualTo("response-value");
	}

	@Test
	void endOfTheConnectionCarryingAResponseStreamDetachesItFromTheSession() {
		// A request whose handler never completes, as seen when a client gives up and
		// disconnects while the server is still working on its tool call
		var session = session(Map.of("tools/call", (exchange, params) -> Mono.never()));
		var transport = new RecordingTransport();

		// The caller owns the stream, so it can detach it from the session once the
		// container tells it the connection carrying it is gone
		var stream = session.responseStream(transport);
		stream.handle(new McpSchema.JSONRPCRequest("tools/call", "request-1")).subscribe();
		assertThat(session.hasOpenStream()).isTrue();

		stream.releaseTransport();

		// The session must stop believing it holds a live connection: hasOpenStream() is
		// what tells the session sweeper that a client is still around, so a stream which
		// outlives its connection makes the session impossible to reclaim
		assertThat(session.hasOpenStream()).isFalse();
		assertThat(transport.closed).isTrue();
	}

	@Test
	void responseStreamIsDetachedFromTheSessionOnceItsRequestIsAnswered() {
		var session = session(Map.of("tools/call", (exchange, params) -> Mono.just("result")));
		var transport = new RecordingTransport();

		var stream = session.responseStream(transport);
		assertThat(session.hasOpenStream()).isTrue();

		stream.handle(new McpSchema.JSONRPCRequest("tools/call", "request-1")).block(TIMEOUT);

		assertThat(session.hasOpenStream()).isFalse();
	}

	@Test
	void responseStreamIsDetachedFromTheSessionWhenItsResponseCannotBeSent() {
		var session = session(Map.of("tools/call", (exchange, params) -> Mono.just("result")));

		var stream = session.responseStream(new FailingTransport());
		var handling = stream.handle(new McpSchema.JSONRPCRequest("tools/call", "request-1")).toFuture();

		// The connection which was to carry the response failed. The caller gets to see
		// it, and the stream must not be left attached to the session.
		assertThat(handling).failsWithin(TIMEOUT).withThrowableThat().havingCause().withMessage("connection gone");
		assertThat(session.hasOpenStream()).isFalse();
	}

	@Test
	void abandonedResponseStreamIsDetachedFromTheSession() {
		var session = session(Map.of("tools/call", (exchange, params) -> Mono.never()));

		var stream = session.responseStream(new RecordingTransport());
		var subscription = stream.handle(new McpSchema.JSONRPCRequest("tools/call", "request-1")).subscribe();
		assertThat(session.hasOpenStream()).isTrue();

		// The caller gives up on a request which would never terminate on its own, so
		// nothing sends the response the stream was created to carry
		subscription.dispose();

		assertThat(session.hasOpenStream()).isFalse();
	}

	/**
	 * A transport whose connection is gone, so that nothing can be written to it.
	 */
	static class FailingTransport extends RecordingTransport {

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
			return Mono.error(new RuntimeException("connection gone"));
		}

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
