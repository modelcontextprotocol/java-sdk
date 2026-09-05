/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpServletStreamableServerTransportProviderTests {

	private final McpJsonMapper jsonMapper = new GsonMcpJsonMapper();

	@Test
	void getListenerDisconnectClosesListeningStream() throws Exception {
		HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(this.jsonMapper)
			.mcpEndpoint("/mcp")
			.build();
		String sessionId = "session-get";
		McpStreamableServerSession session = createSession(sessionId, Map.of(), Map.of());
		provider.setSessionFactory(request -> new McpStreamableServerSession.McpStreamableServerSessionInit(session,
				Mono.just(testInitializeResult())));
		initializeSession(provider, sessionId);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		AsyncContext asyncContext = mock(AsyncContext.class);
		StringWriter output = new StringWriter();
		PrintWriter writer = new PrintWriter(output, true);

		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader("Accept")).thenReturn("text/event-stream");
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(request.getHeader(HttpHeaders.LAST_EVENT_ID)).thenReturn(null);
		when(request.getHeaderNames()).thenReturn(java.util.Collections.emptyEnumeration());
		when(request.startAsync()).thenReturn(asyncContext);
		when(response.getWriter()).thenReturn(writer);

		provider.doGet(request, response);

		provider.notifyClient(sessionId, "server/notification", Map.of("connected", true)).block();
		assertThat(output.toString()).contains("server/notification");

		ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
		verify(asyncContext).addListener(listenerCaptor.capture());

		listenerCaptor.getValue().onError(new AsyncEvent(asyncContext));

		verify(asyncContext).complete();
		output.getBuffer().setLength(0);

		assertThatThrownBy(() -> provider.notifyClient(sessionId, "server/notification", Map.of("after", true)).block())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(sessionId);
		assertThat(output.toString()).isEmpty();
	}

	@Test
	void getReplayRequestRegistersAsyncListenerAndClosesTransportOnDisconnect() throws Exception {
		HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(this.jsonMapper)
			.mcpEndpoint("/mcp")
			.build();
		String sessionId = "session-replay";
		McpStreamableServerSession session = createSession(sessionId, Map.of(), Map.of());
		provider.setSessionFactory(request -> new McpStreamableServerSession.McpStreamableServerSessionInit(session,
				Mono.just(testInitializeResult())));
		initializeSession(provider, sessionId);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		AsyncContext asyncContext = mock(AsyncContext.class);
		StringWriter output = new StringWriter();
		PrintWriter writer = new PrintWriter(output, true);

		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader("Accept")).thenReturn("text/event-stream");
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(request.getHeader(HttpHeaders.LAST_EVENT_ID)).thenReturn("last-1");
		when(request.getHeaderNames()).thenReturn(java.util.Collections.emptyEnumeration());
		when(request.startAsync()).thenReturn(asyncContext);
		when(response.getWriter()).thenReturn(writer);

		provider.doGet(request, response);

		ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
		verify(asyncContext).addListener(listenerCaptor.capture());

		listenerCaptor.getValue().onError(new AsyncEvent(asyncContext));

		verify(asyncContext).complete();
		assertThat(output.toString()).isEmpty();
	}

	@Test
	void postStreamingRequestRegistersAsyncListenerAndClosesTransportOnDisconnect() throws Exception {
		HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(this.jsonMapper)
			.mcpEndpoint("/mcp")
			.build();

		String sessionId = "session-post";
		McpRequestHandler<Object> echoHandler = (exchange, params) -> Mono.just(params);
		McpStreamableServerSession session = createSession(sessionId, Map.of("echo", echoHandler), Map.of());
		provider.setSessionFactory(request -> new McpStreamableServerSession.McpStreamableServerSessionInit(session,
				Mono.just(testInitializeResult())));

		initializeSession(provider, sessionId);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		AsyncContext asyncContext = mock(AsyncContext.class);
		StringWriter output = new StringWriter();
		PrintWriter writer = new PrintWriter(output, true);

		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader("Accept")).thenReturn("text/event-stream, application/json");
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(request.getHeaderNames()).thenReturn(java.util.Collections.emptyEnumeration());
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(this.jsonMapper
			.writeValueAsString(new McpSchema.JSONRPCRequest("echo", "request-1", Map.of("value", "ok"))))));
		when(request.startAsync()).thenReturn(asyncContext);
		when(response.getWriter()).thenReturn(writer);

		provider.doPost(request, response);

		assertThat(output.toString()).contains("\"result\":{\"value\":\"ok\"}");

		ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
		verify(asyncContext).addListener(listenerCaptor.capture());

		listenerCaptor.getValue().onError(new AsyncEvent(asyncContext));

		verify(asyncContext).complete();
	}

	@Test
	void sendFailureClosesOnlyCurrentTransportWithoutRemovingSession() throws Exception {
		HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(this.jsonMapper)
			.mcpEndpoint("/mcp")
			.build();
		String sessionId = "session-send-failure";
		McpStreamableServerSession session = createSession(sessionId, Map.of(), Map.of());
		provider.setSessionFactory(request -> new McpStreamableServerSession.McpStreamableServerSessionInit(session,
				Mono.just(testInitializeResult())));
		initializeSession(provider, sessionId);

		HttpServletRequest getRequest = mock(HttpServletRequest.class);
		HttpServletResponse getResponse = mock(HttpServletResponse.class);
		AsyncContext getAsyncContext = mock(AsyncContext.class);
		PrintWriter failingWriter = new PrintWriter(new FailingServletOutputStream(), true);

		when(getRequest.getRequestURI()).thenReturn("/mcp");
		when(getRequest.getHeader("Accept")).thenReturn("text/event-stream");
		when(getRequest.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(getRequest.getHeader(HttpHeaders.LAST_EVENT_ID)).thenReturn(null);
		when(getRequest.getHeaderNames()).thenReturn(java.util.Collections.emptyEnumeration());
		when(getRequest.startAsync()).thenReturn(getAsyncContext);
		when(getResponse.getWriter()).thenReturn(failingWriter);

		provider.doGet(getRequest, getResponse);
		provider.notifyClient(sessionId, "server/notification", Map.of("boom", true)).block();

		verify(getAsyncContext).complete();

		HttpServletRequest deleteRequest = mock(HttpServletRequest.class);
		HttpServletResponse deleteResponse = mock(HttpServletResponse.class);
		when(deleteRequest.getRequestURI()).thenReturn("/mcp");
		when(deleteRequest.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(deleteRequest.getHeaderNames()).thenReturn(java.util.Collections.emptyEnumeration());

		provider.doDelete(deleteRequest, deleteResponse);

		verify(deleteResponse).setStatus(HttpServletResponse.SC_OK);
	}

	private void initializeSession(HttpServletStreamableServerTransportProvider provider, String expectedSessionId)
			throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter output = new StringWriter();
		PrintWriter writer = new PrintWriter(output, true);
		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader("Accept")).thenReturn("text/event-stream, application/json");
		when(request.getHeaderNames()).thenReturn(java.util.Collections.emptyEnumeration());
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(this.jsonMapper.writeValueAsString(
				new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "init-1", testInitializeRequest())))));
		when(response.getWriter()).thenReturn(writer);

		doAnswer(invocation -> {
			assertThat(invocation.getArgument(1, String.class)).isEqualTo(expectedSessionId);
			return null;
		}).when(response).setHeader(eq(HttpHeaders.MCP_SESSION_ID), any(String.class));

		provider.doPost(request, response);
		verify(response).setStatus(HttpServletResponse.SC_OK);
	}

	private McpStreamableServerSession createSession(String sessionId,
			Map<String, McpRequestHandler<?>> requestHandlers,
			Map<String, McpNotificationHandler> notificationHandlers) {
		return new McpStreamableServerSession(sessionId, testInitializeRequest().capabilities(),
				testInitializeRequest().clientInfo(), Duration.ofSeconds(2), requestHandlers, notificationHandlers);
	}

	private McpSchema.InitializeRequest testInitializeRequest() {
		return McpSchema.InitializeRequest
			.builder("2025-11-25", new McpSchema.ClientCapabilities(null, null, null, null),
					new McpSchema.Implementation("test-client", "1.0.0"))
			.build();
	}

	private McpSchema.InitializeResult testInitializeResult() {
		return McpSchema.InitializeResult
			.builder("2025-11-25", new McpSchema.ServerCapabilities(null, null, null, null, null, null),
					new McpSchema.Implementation("test-server", "1.0.0"))
			.build();
	}

	private static final class FailingServletOutputStream extends ServletOutputStream {

		private final AtomicReference<IOException> failure = new AtomicReference<>(
				new IOException("Client disconnected"));

		@Override
		public void write(int b) throws IOException {
			throw this.failure.get();
		}

		@Override
		public boolean isReady() {
			return false;
		}

		@Override
		public void setWriteListener(WriteListener writeListener) {
		}

	}

}
