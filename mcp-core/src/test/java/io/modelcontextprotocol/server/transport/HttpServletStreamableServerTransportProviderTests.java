package io.modelcontextprotocol.server.transport;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the GET stream of {@link HttpServletStreamableServerTransportProvider}.
 */
class HttpServletStreamableServerTransportProviderTests {

	private HttpServletStreamableServerTransportProvider provider;

	private final Map<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

	@BeforeEach
	void setUp() throws Exception {
		this.provider = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(mock(io.modelcontextprotocol.json.McpJsonMapper.class))
			.mcpEndpoint("/mcp")
			.contextExtractor(request -> McpTransportContext.EMPTY)
			.build();

		McpStreamableServerSession session = mock(McpStreamableServerSession.class);
		McpStreamableServerSession.McpStreamableServerSessionStream stream = mock(
				McpStreamableServerSession.McpStreamableServerSessionStream.class);
		when(session.listeningStream(any())).thenReturn(stream);

		var sessionsField = HttpServletStreamableServerTransportProvider.class.getDeclaredField("sessions");
		sessionsField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, McpStreamableServerSession> sessions = (Map<String, McpStreamableServerSession>) sessionsField
			.get(this.provider);
		sessions.put("session-1", session);
	}

	@Test
	void getStreamCommitsResponseHeadersImmediately() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader("Accept")).thenReturn("text/event-stream");
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn("session-1");
		when(request.getHeader("Last-Event-ID")).thenReturn(null);
		AsyncContext asyncContext = mock(AsyncContext.class);
		when(request.startAsync()).thenReturn(asyncContext);

		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(mock(PrintWriter.class));

		this.provider.doGet(request, response);

		// The status line and the SSE headers must be committed as soon as the stream
		// opens,
		// before any event is written (see gh-1155)
		verify(response).flushBuffer();
	}

}
