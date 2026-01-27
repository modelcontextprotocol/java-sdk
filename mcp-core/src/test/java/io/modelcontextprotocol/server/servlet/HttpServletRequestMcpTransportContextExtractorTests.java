/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server.servlet;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.ProtocolVersions;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpServletRequestMcpTransportContextExtractorTests {

	@Test
	@DisplayName("extract() includes all provided headers in metadata")
	void extractIncludesAllHeaders() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader(HttpHeaders.PROTOCOL_VERSION)).thenReturn("2025-03-26");
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn("session-abc");
		when(request.getHeader(HttpHeaders.LAST_EVENT_ID)).thenReturn("evt-42");

		HttpServletRequestMcpTransportContextExtractor extractor = new HttpServletRequestMcpTransportContextExtractor();

		McpTransportContext ctx = extractor.extract(request);

		assertEquals("2025-03-26", ctx.get(HttpHeaders.PROTOCOL_VERSION));
		assertEquals("session-abc", ctx.get(HttpHeaders.MCP_SESSION_ID));
		assertEquals("evt-42", ctx.get(HttpHeaders.LAST_EVENT_ID));

		verify(request, times(1)).getHeader(HttpHeaders.PROTOCOL_VERSION);
		verify(request, times(1)).getHeader(HttpHeaders.MCP_SESSION_ID);
		verify(request, times(1)).getHeader(HttpHeaders.LAST_EVENT_ID);
		verifyNoMoreInteractions(request);
	}

	@Test
	@DisplayName("extract() defaults protocol version when header missing")
	void extractDefaultsProtocolVersion() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader(HttpHeaders.PROTOCOL_VERSION)).thenReturn(null);
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(null);
		when(request.getHeader(HttpHeaders.LAST_EVENT_ID)).thenReturn(null);

		HttpServletRequestMcpTransportContextExtractor extractor = new HttpServletRequestMcpTransportContextExtractor();

		McpTransportContext ctx = extractor.extract(request);

		assertEquals(ProtocolVersions.MCP_2025_03_26, ctx.get(HttpHeaders.PROTOCOL_VERSION));
		assertNull(ctx.get(HttpHeaders.MCP_SESSION_ID));
		assertNull(ctx.get(HttpHeaders.LAST_EVENT_ID));

		verify(request, times(1)).getHeader(HttpHeaders.PROTOCOL_VERSION);
		verify(request, times(1)).getHeader(HttpHeaders.MCP_SESSION_ID);
		verify(request, times(1)).getHeader(HttpHeaders.LAST_EVENT_ID);
		verifyNoMoreInteractions(request);
	}

	@Test
	@DisplayName("extract() omits optional headers when not present")
	void extractOmitsOptionalHeaders() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader(HttpHeaders.PROTOCOL_VERSION)).thenReturn(ProtocolVersions.MCP_2025_03_26);
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(null);
		when(request.getHeader(HttpHeaders.LAST_EVENT_ID)).thenReturn(null);

		HttpServletRequestMcpTransportContextExtractor extractor = new HttpServletRequestMcpTransportContextExtractor();

		McpTransportContext ctx = extractor.extract(request);

		assertEquals(ProtocolVersions.MCP_2025_03_26, ctx.get(HttpHeaders.PROTOCOL_VERSION));
		assertNull(ctx.get(HttpHeaders.MCP_SESSION_ID));
		assertNull(ctx.get(HttpHeaders.LAST_EVENT_ID));

		verify(request, times(1)).getHeader(HttpHeaders.PROTOCOL_VERSION);
		verify(request, times(1)).getHeader(HttpHeaders.MCP_SESSION_ID);
		verify(request, times(1)).getHeader(HttpHeaders.LAST_EVENT_ID);
		verifyNoMoreInteractions(request);
	}

}
