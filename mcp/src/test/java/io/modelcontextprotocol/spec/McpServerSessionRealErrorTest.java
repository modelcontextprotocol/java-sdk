/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpInitRequestHandler;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests to verify that error logging works in real McpServerSession
 * scenarios. These tests actually trigger the onErrorResume handlers we added logging to.
 */
class McpServerSessionRealErrorTest {

	@Mock
	private McpServerTransport mockTransport;

	@Mock
	private McpInitRequestHandler mockInitHandler;

	@Mock
	private McpRequestHandler<Object> mockRequestHandler;

	private McpServerSession session;

	private ListAppender<ILoggingEvent> logAppender;

	private Logger logger;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);

		// Set up log capture for McpServerSession
		logger = (Logger) LoggerFactory.getLogger(McpServerSession.class);
		logAppender = new ListAppender<>();
		logAppender.start();
		logger.addAppender(logAppender);
		logger.setLevel(Level.ERROR);

		// Set up request handlers
		Map<String, McpRequestHandler<?>> requestHandlers = new HashMap<>();
		requestHandlers.put("tools/call", mockRequestHandler);

		Map<String, McpNotificationHandler> notificationHandlers = new HashMap<>();

		// Create session
		session = new McpServerSession("test-session", Duration.ofSeconds(30), mockTransport, mockInitHandler,
				requestHandlers, notificationHandlers);

		// Set up basic transport mocking - successful by default
		when(mockTransport.sendMessage(any())).thenReturn(Mono.empty());
		when(mockTransport.unmarshalFrom(any(), any(TypeReference.class))).thenAnswer(invocation -> {
			Object firstArg = invocation.getArgument(0);
			if (firstArg instanceof McpSchema.InitializeRequest) {
				return firstArg;
			}
			return new Object(); // Default return for other unmarshaling
		});
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(logAppender);
	}

	@Test
	void testHandleIncomingRequestErrorLogging() {
		// Arrange: Initialize the session properly
		initializeSession();

		// Set up request handler to throw an exception - this should trigger our logging
		// in handleIncomingRequest
		RuntimeException handlerException = new RuntimeException("Tool handler failed unexpectedly");
		when(mockRequestHandler.handle(any(McpAsyncServerExchange.class), any()))
			.thenReturn(Mono.error(handlerException));

		// Create a valid tools/call request
		var toolCallRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/call", "req-123",
				Map.of("name", "test-tool", "arguments", Map.of()));

		// Act: Handle the request - this should trigger the onErrorResume in
		// handleIncomingRequest
		StepVerifier.create(session.handle(toolCallRequest)).expectComplete().verify();

		// Assert: Verify our error logging was triggered
		List<ILoggingEvent> errorLogs = logAppender.list.stream()
			.filter(event -> event.getLevel() == Level.ERROR)
			.filter(event -> event.getMessage().contains("Error processing request"))
			.toList();

		assertThat(errorLogs).hasSize(1);

		ILoggingEvent logEvent = errorLogs.get(0);
		assertThat(logEvent.getMessage()).isEqualTo("Error processing request {}: {}");
		assertThat(logEvent.getArgumentArray()[0]).isEqualTo("tools/call");
		assertThat(logEvent.getArgumentArray()[1]).isEqualTo("Tool handler failed unexpectedly");

		// Verify the actual exception is captured for stack trace
		assertThat(logEvent.getThrowableProxy()).isNotNull();
		assertThat(logEvent.getThrowableProxy().getMessage()).isEqualTo("Tool handler failed unexpectedly");
	}

	@Test
	void testTransportErrorLogging() {
		// Arrange: Set up transport to fail when sending error response
		RuntimeException transportError = new RuntimeException("Network connection lost");

		// Mock transport to fail on sendMessage for error responses
		when(mockTransport.sendMessage(any(McpSchema.JSONRPCResponse.class))).thenReturn(Mono.error(transportError));

		// Create a request for an unknown method - this will create an error response
		var unknownRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "unknown/method", "req-456",
				Map.of());

		// Act: Handle the request - this should trigger the onErrorResume in handle()
		// method
		// Note: Some transport errors may bubble up instead of being swallowed
		StepVerifier.create(session.handle(unknownRequest)).expectErrorSatisfies(error -> {
			// We expect the transport error to bubble up, but our logging should still
			// occur
			assertThat(error).hasMessage("Network connection lost");
		}).verify();

		// Assert: Verify our transport error logging was triggered
		List<ILoggingEvent> errorLogs = logAppender.list.stream()
			.filter(event -> event.getLevel() == Level.ERROR)
			.filter(event -> event.getMessage().contains("Error handling request"))
			.toList();

		assertThat(errorLogs).hasSize(1);

		ILoggingEvent logEvent = errorLogs.get(0);
		assertThat(logEvent.getMessage()).isEqualTo("Error handling request: {}");
		assertThat(logEvent.getArgumentArray()[0]).isEqualTo("Network connection lost");

		// Verify the actual exception is captured for stack trace
		assertThat(logEvent.getThrowableProxy()).isNotNull();
		assertThat(logEvent.getThrowableProxy().getMessage()).isEqualTo("Network connection lost");
	}

	@Test
	void testInitHandlerErrorLogging() {
		// Arrange: Set up init handler to throw an exception
		RuntimeException initError = new RuntimeException("Server initialization failed");
		when(mockInitHandler.handle(any())).thenReturn(Mono.error(initError));

		// Set up unmarshal to return a proper InitializeRequest
		var initRequest = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
				new McpSchema.ClientCapabilities(null, null, null, null),
				new McpSchema.Implementation("test-client", "1.0.0"));

		when(mockTransport.unmarshalFrom(any(), any(TypeReference.class))).thenReturn(initRequest);

		// Create an initialize request
		var jsonRpcInitRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE,
				"init-789", initRequest);

		// Act: Handle the initialization request
		StepVerifier.create(session.handle(jsonRpcInitRequest)).expectComplete().verify();

		// Assert: Verify error logging for initialization failure
		List<ILoggingEvent> errorLogs = logAppender.list.stream()
			.filter(event -> event.getLevel() == Level.ERROR)
			.filter(event -> event.getMessage().contains("Error processing request"))
			.toList();

		assertThat(errorLogs).hasSize(1);

		ILoggingEvent logEvent = errorLogs.get(0);
		assertThat(logEvent.getMessage()).isEqualTo("Error processing request {}: {}");
		assertThat(logEvent.getArgumentArray()[0]).isEqualTo("initialize");
		assertThat(logEvent.getArgumentArray()[1]).isEqualTo("Server initialization failed");

		// Verify the actual exception is captured
		assertThat(logEvent.getThrowableProxy()).isNotNull();
		assertThat(logEvent.getThrowableProxy().getMessage()).isEqualTo("Server initialization failed");
	}

	@Test
	void testBothErrorPathsInSequence() {
		// This test verifies that both error logging paths can be triggered and work
		// correctly

		// First: Trigger handleIncomingRequest error
		initializeSession();

		RuntimeException handlerError = new RuntimeException("First error");
		when(mockRequestHandler.handle(any(McpAsyncServerExchange.class), any())).thenReturn(Mono.error(handlerError));

		var request1 = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/call", "req-1", Map.of());

		StepVerifier.create(session.handle(request1)).expectComplete().verify();

		// Second: Trigger transport error
		RuntimeException transportError = new RuntimeException("Second error");
		when(mockTransport.sendMessage(any(McpSchema.JSONRPCResponse.class))).thenReturn(Mono.error(transportError));

		var request2 = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "unknown/method", "req-2", Map.of());

		StepVerifier.create(session.handle(request2))
			.expectErrorSatisfies(error -> assertThat(error).hasMessage("Second error"))
			.verify();

		// Assert: We should have both types of error logs
		List<ILoggingEvent> errorLogs = logAppender.list.stream()
			.filter(event -> event.getLevel() == Level.ERROR)
			.toList();

		assertThat(errorLogs).hasSize(2);

		// Check first error (handleIncomingRequest)
		ILoggingEvent firstError = errorLogs.stream()
			.filter(event -> event.getMessage().contains("Error processing request"))
			.findFirst()
			.orElseThrow();
		assertThat(firstError.getArgumentArray()[1]).isEqualTo("First error");

		// Check second error (transport)
		ILoggingEvent secondError = errorLogs.stream()
			.filter(event -> event.getMessage().contains("Error handling request"))
			.findFirst()
			.orElseThrow();
		assertThat(secondError.getArgumentArray()[0]).isEqualTo("Second error");
	}

	private void initializeSession() {
		// Properly initialize the session so request handlers can be called
		var clientCaps = new McpSchema.ClientCapabilities(null, null, null, null);
		var clientInfo = new McpSchema.Implementation("test-client", "1.0.0");
		session.init(clientCaps, clientInfo);

		// Manually trigger the initialization flow that sets up the exchange sink
		// This simulates what happens when an "initialized" notification is received
		var exchange = new McpAsyncServerExchange("test-session", session, clientCaps, clientInfo,
				McpTransportContext.EMPTY);

		// Access the private exchange sink via reflection to simulate proper
		// initialization
		try {
			var exchangeSinkField = McpServerSession.class.getDeclaredField("exchangeSink");
			exchangeSinkField.setAccessible(true);
			@SuppressWarnings("unchecked")
			Sinks.One<McpAsyncServerExchange> exchangeSink = (Sinks.One<McpAsyncServerExchange>) exchangeSinkField
				.get(session);
			exchangeSink.tryEmitValue(exchange);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to initialize session", e);
		}
	}

}