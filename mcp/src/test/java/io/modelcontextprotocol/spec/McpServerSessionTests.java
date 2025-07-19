/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Test suite for {@link McpServerSession} focusing on ignorable JSON-RPC methods
 * functionality.
 *
 * @author Christian Tzolov
 */
class McpServerSessionTests {

	private McpServerTransport mockTransport;

	private McpServerSession.InitRequestHandler mockInitRequestHandler;

	private McpServerSession.InitNotificationHandler mockInitNotificationHandler;

	private Map<String, McpServerSession.RequestHandler<?>> requestHandlers;

	private Map<String, McpServerSession.NotificationHandler> notificationHandlers;

	@BeforeEach
	void setUp() {
		mockTransport = mock(McpServerTransport.class);
		mockInitRequestHandler = mock(McpServerSession.InitRequestHandler.class);
		mockInitNotificationHandler = mock(McpServerSession.InitNotificationHandler.class);
		requestHandlers = Map.of();
		notificationHandlers = Map.of();

		// Setup default mock behavior
		when(mockTransport.sendMessage(any())).thenReturn(Mono.empty());
		when(mockInitRequestHandler.handle(any()))
			.thenReturn(Mono.just(new McpSchema.InitializeResult(McpSchema.LATEST_PROTOCOL_VERSION,
					McpSchema.ServerCapabilities.builder().build(),
					new McpSchema.Implementation("test-server", "1.0.0"), null)));
		when(mockInitNotificationHandler.handle()).thenReturn(Mono.empty());
	}

	// ---------------------------------------
	// Ignorable Request Tests
	// ---------------------------------------

	@Test
	void testIgnorableRequestIsIgnored() {
		List<String> ignorableMethods = List.of("notifications/cancelled");

		var session = new McpServerSession("test-session", Duration.ofSeconds(10), mockTransport,
				mockInitRequestHandler, mockInitNotificationHandler, requestHandlers, notificationHandlers,
				ignorableMethods);

		var ignorableRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "notifications/cancelled",
				"req-1", null);

		StepVerifier.create(session.handle(ignorableRequest)).verifyComplete();

		// Verify no response was sent
		verify(mockTransport, never()).sendMessage(any());
	}

	@Test
	void testNonIgnorableRequestGeneratesError() {
		List<String> ignorableMethods = List.of("custom/method");

		var session = new McpServerSession("test-session", Duration.ofSeconds(10), mockTransport,
				mockInitRequestHandler, mockInitNotificationHandler, requestHandlers, notificationHandlers,
				ignorableMethods);

		var nonIgnorableRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "unknown/method", "req-1",
				null);

		StepVerifier.create(session.handle(nonIgnorableRequest)).verifyComplete();

		// Verify error response was sent
		ArgumentCaptor<McpSchema.JSONRPCMessage> messageCaptor = ArgumentCaptor
			.forClass(McpSchema.JSONRPCMessage.class);
		verify(mockTransport).sendMessage(messageCaptor.capture());

		McpSchema.JSONRPCMessage sentMessage = messageCaptor.getValue();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
		assertThat(response.error().message()).contains("Method not found: unknown/method");
	}

	// ---------------------------------------
	// Ignorable Notification Tests
	// ---------------------------------------

	@Test
	void testIgnorableNotificationIsIgnored() {
		List<String> ignorableMethods = List.of("notifications/cancelled");

		var session = new McpServerSession("test-session", Duration.ofSeconds(10), mockTransport,
				mockInitRequestHandler, mockInitNotificationHandler, requestHandlers, notificationHandlers,
				ignorableMethods);

		var ignorableNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/cancelled", null);

		StepVerifier.create(session.handle(ignorableNotification)).verifyComplete();

		// Verify no message was sent (notifications don't generate responses anyway)
		verify(mockTransport, never()).sendMessage(any());
	}

	// ---------------------------------------
	// Default Ignorable Methods Constants Tests
	// ---------------------------------------
	@Test
	void testDefaultIgnorableMethodsConstants() {
		List<String> defaults = Utils.DEFAULT_IGNORABLE_JSON_RPC_METHODS;

		assertThat(defaults).isNotNull();
		assertThat(defaults).contains("notifications/cancelled");
		assertThat(defaults).contains("notifications/stderr");
		assertThat(defaults).hasSize(2);
	}

}
