/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.ProtocolVersions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Last-Event-ID replay handling of
 * {@link HttpServletStreamableServerTransportProvider}.
 */
@Timeout(5)
class HttpServletStreamableServerTransportProviderReplayTests {

	private static final String SESSION_ID = "session-1";

	private final HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider
		.builder()
		.build();

	private final McpStreamableServerSession session = mock(McpStreamableServerSession.class);

	private final HttpServletRequest request = mock(HttpServletRequest.class);

	private final HttpServletResponse response = mock(HttpServletResponse.class);

	private final AsyncContext asyncContext = mock(AsyncContext.class);

	private final StringWriter responseBody = new StringWriter();

	@BeforeEach
	void setUp() throws Exception {
		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(SESSION_ID);
		when(request.getHeader(HttpHeaders.LAST_EVENT_ID)).thenReturn("last-event-id");
		when(request.getHeader("Accept")).thenReturn("text/event-stream");
		when(request.startAsync()).thenReturn(asyncContext);
		when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
		when(session.getId()).thenReturn(SESSION_ID);

		McpStreamableServerSession.Factory sessionFactory = mock(McpStreamableServerSession.Factory.class);
		McpStreamableServerSession.McpStreamableServerSessionInit init = new McpStreamableServerSession.McpStreamableServerSessionInit(
				session, Mono.just(initializeResult()));
		when(sessionFactory.startSession(any(McpSchema.InitializeRequest.class))).thenReturn(init);
		transport.setSessionFactory(sessionFactory);
	}

	@Test
	void getWithLastEventIdAndEmptyReplayCompletesAsyncContext() throws Exception {
		registerSession();

		when(session.replay("last-event-id")).thenReturn(Flux.empty());

		transport.doGet(request, response);

		verify(asyncContext).complete();
		verify(response).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}

	@Test
	void getWithLastEventIdAndReplayMessagesKeepsConnectionOpen() throws Exception {
		registerSession();

		McpSchema.JSONRPCNotification message = new McpSchema.JSONRPCNotification(
				McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED);
		when(session.replay("last-event-id")).thenReturn(Flux.just(message));

		transport.doGet(request, response);

		verify(asyncContext, never()).complete();
		verify(response, never()).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		assertThat(responseBody.toString()).contains(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED);
	}

	private void registerSession() throws Exception {
		McpSchema.InitializeRequest initializeRequest = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().build(),
					McpSchema.Implementation.builder("test-client", "1.0.0").build())
			.build();
		McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "init-1",
				initializeRequest);
		when(request.getReader()).thenReturn(
				new BufferedReader(new StringReader(McpJsonDefaults.getMapper().writeValueAsString(jsonrpcRequest))));

		transport.doPost(request, response);
	}

	private static McpSchema.InitializeResult initializeResult() {
		return McpSchema.InitializeResult.builder(ProtocolVersions.MCP_2025_11_25,
				McpSchema.ServerCapabilities.builder().build(),
				McpSchema.Implementation.builder("test-server", "1.0.0").build())
			.build();
	}

}
