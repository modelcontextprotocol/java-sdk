/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpServletStreamableServerDuplicateInitializeTests {

	private static final String SESSION_ID = "active-session";

	private final McpJsonMapper jsonMapper = new GsonMcpJsonMapper();

	@Test
	void rejectsDuplicateInitializeForActiveSession() throws Exception {
		HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(this.jsonMapper)
			.build();
		McpStreamableServerSession session = mock(McpStreamableServerSession.class);
		when(session.getId()).thenReturn(SESSION_ID);
		AtomicInteger sessionStarts = new AtomicInteger();
		provider.setSessionFactory(request -> {
			sessionStarts.incrementAndGet();
			return new McpStreamableServerSession.McpStreamableServerSessionInit(session,
					Mono.just(testInitializeResult()));
		});

		Exchange initial = initializeExchange(null, "init-1");
		provider.doPost(initial.request(), initial.response());

		verify(initial.response()).setStatus(HttpServletResponse.SC_OK);
		verify(initial.response()).setHeader(HttpHeaders.MCP_SESSION_ID, SESSION_ID);

		Exchange duplicate = initializeExchange(SESSION_ID, "init-2");
		provider.doPost(duplicate.request(), duplicate.response());

		verify(duplicate.response()).setStatus(HttpServletResponse.SC_BAD_REQUEST);
		assertThat(duplicate.body().toString()).contains("\"jsonrpc\":\"2.0\"", "\"id\":\"init-2\"", "\"code\":-32600",
				"Duplicate initialize request for active session");
		assertThat(sessionStarts).hasValue(1);
	}

	private Exchange initializeExchange(String sessionId, String requestId) throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader("Accept")).thenReturn("text/event-stream, application/json");
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
		when(request.getInputStream()).thenReturn(servletInputStream(this.jsonMapper
			.writeValueAsString(
					new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, requestId, testInitializeRequest()))
			.getBytes(StandardCharsets.UTF_8)));
		when(response.getWriter()).thenReturn(new PrintWriter(body, true));
		return new Exchange(request, response, body);
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

	private static ServletInputStream servletInputStream(byte[] data) {
		ByteArrayInputStream delegate = new ByteArrayInputStream(data);
		return new ServletInputStream() {

			@Override
			public boolean isFinished() {
				return delegate.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
			}

			@Override
			public int read() {
				return delegate.read();
			}

			@Override
			public int read(byte[] b, int off, int len) {
				return delegate.read(b, off, len);
			}

		};
	}

	private record Exchange(HttpServletRequest request, HttpServletResponse response, StringWriter body) {
	}

}
