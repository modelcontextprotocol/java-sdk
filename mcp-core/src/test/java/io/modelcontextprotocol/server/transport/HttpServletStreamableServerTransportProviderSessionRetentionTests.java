/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import com.google.gson.JsonSerializer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that a failed write on one SSE stream does not evict the whole streamable HTTP
 * session, while explicit DELETE requests still do.
 */
class HttpServletStreamableServerTransportProviderSessionRetentionTests {

	private static final String INIT_BODY = """
			{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2025-06-18",
			"capabilities":{},"clientInfo":{"name":"it","version":"1.0"}}}""";

	private static final String NOTIFICATION_BODY = """
			{"jsonrpc":"2.0","method":"notifications/test","params":{}}""";

	private HttpServletStreamableServerTransportProvider provider;

	private final StringWriter initResponseBody = new StringWriter();

	@BeforeEach
	void setUp() {
		this.provider = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(jsonMapper())
			.mcpEndpoint("/mcp")
			.build();
		this.provider.setSessionFactory(this::startSession);
	}

	private McpJsonMapper jsonMapper() {
		// The test Gson needs a Throwable adapter: responseError serializes McpError
		// (a RuntimeException), and reflective serialization of Throwable fields is
		// not accessible on JDK 17+.
		Gson gson = new GsonBuilder().serializeNulls()
			.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.registerTypeHierarchyAdapter(Throwable.class, (JsonSerializer<Throwable>) (src, typeOfSrc, context) -> {
				JsonObject error = new JsonObject();
				error.addProperty("message", src.getMessage());
				return error;
			})
			.create();
		return new GsonMcpJsonMapper(gson);
	}

	private McpStreamableServerSession.McpStreamableServerSessionInit startSession(
			McpSchema.InitializeRequest initializeRequest) {
		McpStreamableServerSession session = new McpStreamableServerSession("test-session-id",
				new McpSchema.ClientCapabilities(null, null, null, null),
				new McpSchema.Implementation("it", null, "1.0", null, null, null), Duration.ofSeconds(5), Map.of(),
				Map.of());
		McpSchema.InitializeResult initResult = new McpSchema.InitializeResult("2025-06-18",
				McpSchema.ServerCapabilities.builder().build(),
				new McpSchema.Implementation("test-server", null, "1.0", null, null, null), null, null);
		return new McpStreamableServerSession.McpStreamableServerSessionInit(session, Mono.just(initResult));
	}

	@Test
	void sessionSurvivesFailedWriteOnSseStream() throws Exception {
		String sessionId = initializeSession();

		openBrokenSseStream(sessionId);

		// The write failure happens inside the notification delivery
		this.provider.notifyClient(sessionId, "notifications/test", Map.of()).block();

		// The session must still be usable for subsequent client requests
		HttpServletRequest post = postRequest(NOTIFICATION_BODY, sessionId);
		HttpServletResponse response = mockResponse(new PrintWriter(new StringWriter()));
		this.provider.doPost(post, response);

		verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
	}

	@Test
	void explicitDeleteStillRemovesSession() throws Exception {
		String sessionId = initializeSession();

		HttpServletRequest delete = mock(HttpServletRequest.class);
		when(delete.getRequestURI()).thenReturn("/mcp");
		when(delete.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		HttpServletResponse deleteResponse = mockResponse(new PrintWriter(new StringWriter()));
		this.provider.doDelete(delete, deleteResponse);
		verify(deleteResponse).setStatus(HttpServletResponse.SC_OK);

		HttpServletRequest post = postRequest(NOTIFICATION_BODY, sessionId);
		HttpServletResponse postResponse = mockResponse(new PrintWriter(new StringWriter()));
		this.provider.doPost(post, postResponse);

		verify(postResponse).setStatus(HttpServletResponse.SC_NOT_FOUND);
	}

	private String initializeSession() throws Exception {
		HttpServletRequest post = postRequest(INIT_BODY, null);
		HttpServletResponse response = mockResponse(new PrintWriter(this.initResponseBody));
		this.provider.doPost(post, response);

		ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
		verify(response).setHeader(eq(HttpHeaders.MCP_SESSION_ID), sessionId.capture());
		return sessionId.getValue();
	}

	private void openBrokenSseStream(String sessionId) throws Exception {
		HttpServletRequest get = mock(HttpServletRequest.class);
		when(get.getRequestURI()).thenReturn("/mcp");
		when(get.getHeader(HttpHeaders.ACCEPT)).thenReturn("text/event-stream");
		when(get.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);

		PrintWriter brokenWriter = mock(PrintWriter.class);
		when(brokenWriter.checkError()).thenReturn(true);

		AsyncContext asyncContext = mock(AsyncContext.class);
		when(get.startAsync()).thenReturn(asyncContext);

		HttpServletResponse response = mockResponse(brokenWriter);
		this.provider.doGet(get, response);
	}

	private HttpServletRequest postRequest(String body, String sessionId) throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader(HttpHeaders.ACCEPT)).thenReturn("application/json, text/event-stream");
		if (sessionId != null) {
			when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		}
		when(request.getContentLengthLong()).thenReturn((long) body.getBytes(StandardCharsets.UTF_8).length);
		when(request.getInputStream()).thenReturn(bodyStream(body));
		return request;
	}

	private HttpServletResponse mockResponse(PrintWriter writer) throws IOException {
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(writer);
		return response;
	}

	private static ServletInputStream bodyStream(String body) {
		ByteArrayInputStream source = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
		return new ServletInputStream() {
			@Override
			public boolean isFinished() {
				return source.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
			}

			@Override
			public int read() {
				return source.read();
			}
		};
	}

}
