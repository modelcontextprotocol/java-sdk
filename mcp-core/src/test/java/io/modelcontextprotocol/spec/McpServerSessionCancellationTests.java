/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpInitRequestHandler;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for cancellation support in {@link McpServerSession}.
 */
class McpServerSessionCancellationTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Mock
	private McpServerTransport mockTransport;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		when(mockTransport.sendMessage(any())).thenReturn(Mono.empty());
		when(mockTransport.closeGracefully()).thenReturn(Mono.empty());
		when(mockTransport.unmarshalFrom(any(), any())).thenAnswer(inv -> inv.getArgument(0));
	}

	private McpServerSession createSession(Map<String, McpRequestHandler<?>> requestHandlers,
			Map<String, McpNotificationHandler> notificationHandlers) {
		McpInitRequestHandler initHandler = initializeRequest -> Mono.just(new McpSchema.InitializeResult(
				McpSchema.LATEST_PROTOCOL_VERSION, McpSchema.ServerCapabilities.builder().build(),
				new McpSchema.Implementation("test-server", "1.0.0"), null));

		return new McpServerSession("test-session", TIMEOUT, mockTransport, initHandler, requestHandlers,
				notificationHandlers);
	}

	private void performInitialization(McpServerSession session) {
		McpSchema.InitializeRequest initReq = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
				McpSchema.ClientCapabilities.builder().build(), new McpSchema.Implementation("client", "1.0"));
		McpSchema.JSONRPCRequest initRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_INITIALIZE, "init-1", initReq);
		session.handle(initRequest).block(Duration.ofSeconds(2));

		McpSchema.JSONRPCNotification initializedNotification = new McpSchema.JSONRPCNotification(
				McpSchema.JSONRPC_VERSION, McpSchema.METHOD_NOTIFICATION_INITIALIZED, null);
		session.handle(initializedNotification).block(Duration.ofSeconds(2));
	}

	// ------------------------------------------------------------------
	// Inbound cancellation – client cancels a request it sent to the server
	// ------------------------------------------------------------------

	@Test
	void cancellationNotificationIsAcceptedBySession() {
		McpServerSession session = createSession(new HashMap<>(), new HashMap<>());

		McpSchema.CancelledNotification cancelledNotification = new McpSchema.CancelledNotification("req-1",
				"user cancelled");
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_CANCELLED, cancelledNotification);

		StepVerifier.create(session.handle(notification)).verifyComplete();
	}

	@Test
	void preEmptiveCancellationIsIgnoredAndResponseStillSent() throws InterruptedException {
		Map<String, McpRequestHandler<?>> requestHandlers = new HashMap<>();
		requestHandlers.put("fast.method", (exchange, params) -> Mono.just("result"));

		McpServerSession session = createSession(requestHandlers, new HashMap<>());
		performInitialization(session);

		McpSchema.CancelledNotification cancelledNotification = new McpSchema.CancelledNotification("req-1",
				"pre-emptive cancel");
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_CANCELLED, cancelledNotification);
		session.handle(notification).block(Duration.ofSeconds(2));

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "fast.method",
				"req-1", "param");
		session.handle(request).block(Duration.ofSeconds(2));

		Thread.sleep(100);

		ArgumentCaptor<McpSchema.JSONRPCMessage> captor = ArgumentCaptor.forClass(McpSchema.JSONRPCMessage.class);
		verify(mockTransport, atLeastOnce()).sendMessage(captor.capture());

		boolean responseSent = captor.getAllValues().stream().anyMatch(msg -> {
			if (msg instanceof McpSchema.JSONRPCResponse r) {
				return "req-1".equals(r.id()) && r.error() == null;
			}
			return false;
		});
		assertThat(responseSent)
			.as("Pre-emptive cancellation for non-existent request is ignored per MCP spec; response should still be sent")
			.isTrue();
	}

	@Test
	void cancelledRequestWithNullDeserialization() {
		McpServerSession session = createSession(new HashMap<>(), new HashMap<>());

		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_CANCELLED, null);

		StepVerifier.create(session.handle(notification)).verifyComplete();
	}

	@Test
	void cancellationForNonExistentRequestDoesNotError() {
		McpServerSession session = createSession(new HashMap<>(), new HashMap<>());

		McpSchema.CancelledNotification cancelledNotification = new McpSchema.CancelledNotification("non-existent",
				"cleanup");
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_CANCELLED, cancelledNotification);

		StepVerifier.create(session.handle(notification)).verifyComplete();
	}

	// ------------------------------------------------------------------
	// sendCancellation – server cancels its own outbound request
	// ------------------------------------------------------------------

	@Test
	void sendCancellationSendsNotificationToClient() {
		McpServerSession session = createSession(new HashMap<>(), new HashMap<>());

		StepVerifier.create(session.sendCancellation("outbound-req-1", "timeout")).verifyComplete();

		ArgumentCaptor<McpSchema.JSONRPCMessage> captor = ArgumentCaptor.forClass(McpSchema.JSONRPCMessage.class);
		verify(mockTransport, atLeastOnce()).sendMessage(captor.capture());

		boolean foundCancellation = captor.getAllValues().stream().anyMatch(msg -> {
			if (msg instanceof McpSchema.JSONRPCNotification n) {
				return McpSchema.METHOD_NOTIFICATION_CANCELLED.equals(n.method());
			}
			return false;
		});
		assertThat(foundCancellation).isTrue();
	}

	@Test
	void sendCancellationErrorsPendingOutboundResponse() {
		McpServerSession session = createSession(new HashMap<>(), new HashMap<>());

		Mono<Object> outboundResponse = session.sendRequest("sampling/createMessage", Map.of("prompt", "test"),
				new TypeRef<>() {
				});

		StepVerifier.create(outboundResponse).then(() -> {
			session.sendCancellation("test-session-0", "no longer needed").block(Duration.ofSeconds(2));
		}).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class);
			McpError mcpError = (McpError) error;
			assertThat(mcpError.getJsonRpcError()).isNotNull();
			assertThat(mcpError.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.REQUEST_CANCELLED);
		}).verify(TIMEOUT);
	}

	@Test
	void sendCancellationWithNullReason() {
		McpServerSession session = createSession(new HashMap<>(), new HashMap<>());

		StepVerifier.create(session.sendCancellation("some-id", null)).verifyComplete();

		ArgumentCaptor<McpSchema.JSONRPCMessage> captor = ArgumentCaptor.forClass(McpSchema.JSONRPCMessage.class);
		verify(mockTransport, atLeastOnce()).sendMessage(captor.capture());

		boolean foundCancellation = captor.getAllValues().stream().anyMatch(msg -> {
			if (msg instanceof McpSchema.JSONRPCNotification n) {
				return McpSchema.METHOD_NOTIFICATION_CANCELLED.equals(n.method());
			}
			return false;
		});
		assertThat(foundCancellation).isTrue();
	}

	@Test
	void sendCancellationForUnknownIdStillSendsNotification() {
		McpServerSession session = createSession(new HashMap<>(), new HashMap<>());

		StepVerifier.create(session.sendCancellation("non-existent-outbound", "cleanup")).verifyComplete();

		ArgumentCaptor<McpSchema.JSONRPCMessage> captor = ArgumentCaptor.forClass(McpSchema.JSONRPCMessage.class);
		verify(mockTransport, atLeastOnce()).sendMessage(captor.capture());

		boolean foundCancellation = captor.getAllValues().stream().anyMatch(msg -> {
			if (msg instanceof McpSchema.JSONRPCNotification n) {
				return McpSchema.METHOD_NOTIFICATION_CANCELLED.equals(n.method());
			}
			return false;
		});
		assertThat(foundCancellation).isTrue();
	}

	// ------------------------------------------------------------------
	// Notification handler delegation
	// ------------------------------------------------------------------

	@Test
	void cancellationNotificationStillInvokesRegisteredHandler() {
		ConcurrentHashMap<String, Boolean> handlerCalled = new ConcurrentHashMap<>();
		Map<String, McpNotificationHandler> notificationHandlers = new HashMap<>();
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_CANCELLED, (exchange, params) -> {
			handlerCalled.put("called", true);
			return Mono.empty();
		});

		McpServerSession session = createSession(new HashMap<>(), notificationHandlers);
		performInitialization(session);

		McpSchema.CancelledNotification cancelledNotification = new McpSchema.CancelledNotification("req-42",
				"test reason");
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_CANCELLED, cancelledNotification);

		StepVerifier.create(session.handle(notification)).verifyComplete();

		assertThat(handlerCalled).containsKey("called");
	}

}
