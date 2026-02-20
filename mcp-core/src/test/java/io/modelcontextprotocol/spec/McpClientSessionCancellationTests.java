/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.modelcontextprotocol.MockMcpClientTransport;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for cancellation support in {@link McpClientSession}.
 */
class McpClientSessionCancellationTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String ECHO_METHOD = "echo";

	TypeRef<String> responseType = new TypeRef<>() {
	};

	// ------------------------------------------------------------------
	// sendRequestWithId
	// ------------------------------------------------------------------

	@Test
	void sendRequestWithIdReturnsRequestIdAndResponseMono() {
		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		McpClientSession.RequestMono<String> rm = session.sendRequestWithId("test.method", "param", responseType);

		assertThat(rm.requestId()).isNotNull();
		assertThat(rm.requestId()).isNotEmpty();
		assertThat(rm.response()).isNotNull();

		StepVerifier.create(rm.response()).then(() -> {
			McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			assertThat(request.id()).isEqualTo(rm.requestId());
			assertThat(request.method()).isEqualTo("test.method");
			transport.simulateIncomingMessage(
					new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), "response-data", null));
		}).expectNext("response-data").verifyComplete();

		session.close();
	}

	@Test
	void sendRequestWithIdHandlesErrorResponse() {
		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		McpClientSession.RequestMono<String> rm = session.sendRequestWithId("test.method", "param", responseType);

		StepVerifier.create(rm.response()).then(() -> {
			McpSchema.JSONRPCResponse.JSONRPCError error = new McpSchema.JSONRPCResponse.JSONRPCError(
					McpSchema.ErrorCodes.INTERNAL_ERROR, "Server error", null);
			transport.simulateIncomingMessage(
					new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, rm.requestId(), null, error));
		}).expectError(McpError.class).verify();

		session.close();
	}

	@Test
	void sendRequestWithIdGeneratesUniqueIds() {
		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		McpClientSession.RequestMono<String> rm1 = session.sendRequestWithId("m1", "p1", responseType);
		McpClientSession.RequestMono<String> rm2 = session.sendRequestWithId("m2", "p2", responseType);

		assertThat(rm1.requestId()).isNotEqualTo(rm2.requestId());

		session.close();
	}

	// ------------------------------------------------------------------
	// sendCancellation – outbound cancellation (client cancels own request)
	// ------------------------------------------------------------------

	@Test
	void sendCancellationErrorsPendingResponseAndSendsNotification() {
		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		McpClientSession.RequestMono<String> rm = session.sendRequestWithId("test.method", "param", responseType);

		StepVerifier.create(rm.response()).then(() -> {
			session.sendCancellation(rm.requestId(), "user aborted").block(Duration.ofSeconds(2));
		}).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class);
			McpError mcpError = (McpError) error;
			assertThat(mcpError.getJsonRpcError()).isNotNull();
			assertThat(mcpError.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.REQUEST_CANCELLED);
		}).verify(TIMEOUT);

		McpSchema.JSONRPCMessage lastSent = transport.getLastSentMessage();
		assertThat(lastSent).isInstanceOf(McpSchema.JSONRPCNotification.class);
		McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) lastSent;
		assertThat(notification.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_CANCELLED);

		session.close();
	}

	@Test
	void sendCancellationForUnknownRequestStillSendsNotification() {
		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		StepVerifier.create(session.sendCancellation("non-existent-id", "cleanup")).verifyComplete();

		McpSchema.JSONRPCMessage lastSent = transport.getLastSentMessage();
		assertThat(lastSent).isInstanceOf(McpSchema.JSONRPCNotification.class);
		McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) lastSent;
		assertThat(notification.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_CANCELLED);

		session.close();
	}

	@Test
	void sendCancellationWithNullReasonFormatsMessageCorrectly() {
		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		McpClientSession.RequestMono<String> rm = session.sendRequestWithId("test.method", "param", responseType);

		StepVerifier.create(rm.response()).then(() -> {
			session.sendCancellation(rm.requestId(), null).block(Duration.ofSeconds(2));
		}).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class);
			McpError mcpError = (McpError) error;
			assertThat(mcpError.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.REQUEST_CANCELLED);
			assertThat(mcpError.getMessage()).doesNotContain("null");
		}).verify(TIMEOUT);

		session.close();
	}

	// ------------------------------------------------------------------
	// Inbound cancellation – server cancels a request it sent to us
	// ------------------------------------------------------------------

	@Test
	void inboundCancellationDisposesInProgressRequest() throws InterruptedException {
		CountDownLatch handlerStarted = new CountDownLatch(1);
		AtomicBoolean handlerCompleted = new AtomicBoolean(false);

		Map<String, McpClientSession.RequestHandler<?>> requestHandlers = Map.of(ECHO_METHOD, params -> {
			handlerStarted.countDown();
			return Mono.delay(Duration.ofSeconds(10)).map(l -> {
				handlerCompleted.set(true);
				return params;
			});
		});

		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, requestHandlers, Map.of(), Function.identity());

		McpSchema.JSONRPCRequest incomingRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, ECHO_METHOD,
				"server-req-1", "hello");
		transport.simulateIncomingMessage(incomingRequest);

		assertThat(handlerStarted.await(2, TimeUnit.SECONDS)).isTrue();

		McpSchema.CancelledNotification cancelledNotification = new McpSchema.CancelledNotification("server-req-1",
				"server timeout");
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_CANCELLED, cancelledNotification);
		transport.simulateIncomingMessage(notification);

		Thread.sleep(200);
		assertThat(handlerCompleted.get()).isFalse();

		session.close();
	}

	@Test
	void inboundCancellationForNonExistentRequestDoesNotThrow() {
		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		McpSchema.CancelledNotification cancelledNotification = new McpSchema.CancelledNotification("unknown-id",
				"cleanup");
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_CANCELLED, cancelledNotification);

		transport.simulateIncomingMessage(notification);

		session.close();
	}

	@Test
	void inboundCancellationWithNullDeserialization() {
		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_CANCELLED, null);

		transport.simulateIncomingMessage(notification);

		session.close();
	}

	// ------------------------------------------------------------------
	// RequestMono record
	// ------------------------------------------------------------------

	@Test
	void requestMonoRecordFieldAccess() {
		Mono<String> mono = Mono.just("test");
		McpClientSession.RequestMono<String> rm = new McpClientSession.RequestMono<>("req-123", mono);

		assertThat(rm.requestId()).isEqualTo("req-123");
		assertThat(rm.response()).isSameAs(mono);
	}

	@Test
	void requestMonoRecordEquality() {
		Mono<String> mono = Mono.just("test");
		McpClientSession.RequestMono<String> rm1 = new McpClientSession.RequestMono<>("req-1", mono);
		McpClientSession.RequestMono<String> rm2 = new McpClientSession.RequestMono<>("req-1", mono);
		McpClientSession.RequestMono<String> rm3 = new McpClientSession.RequestMono<>("req-2", mono);

		assertThat(rm1).isEqualTo(rm2);
		assertThat(rm1).isNotEqualTo(rm3);
	}

	// ------------------------------------------------------------------
	// doOnSubscribe / doFinally lifecycle tracking
	// ------------------------------------------------------------------

	@Test
	void completedRequestIsClearedFromTracking() throws InterruptedException {
		Sinks.One<Object> responseSent = Sinks.one();

		Map<String, McpClientSession.RequestHandler<?>> requestHandlers = Map.of(ECHO_METHOD,
				params -> Mono.just(params));
		var transport = new MockMcpClientTransport();
		var session = new McpClientSession(TIMEOUT, transport, requestHandlers, Map.of(), Function.identity());

		McpSchema.JSONRPCRequest incomingRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, ECHO_METHOD,
				"req-track-1", "data");
		transport.simulateIncomingMessage(incomingRequest);

		Thread.sleep(200);

		McpSchema.CancelledNotification cancelledNotification = new McpSchema.CancelledNotification("req-track-1",
				"late cancel");
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_CANCELLED, cancelledNotification);
		transport.simulateIncomingMessage(notification);

		session.close();
	}

}
