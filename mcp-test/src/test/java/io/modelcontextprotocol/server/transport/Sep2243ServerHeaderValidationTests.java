/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.McpJsonMapperUtils;
import jakarta.servlet.http.HttpServlet;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the SEP-2243 server-side validation added to the servlet transports: an
 * unsupported {@code MCP-Protocol-Version} is rejected, and a present {@code Mcp-Method}
 * / {@code Mcp-Name} header that does not mirror the request body is rejected, while
 * absent headers remain tolerated and do not by themselves trigger a validation error.
 */
class Sep2243ServerHeaderValidationTests {

	private static final String ACCEPT = "application/json, text/event-stream";

	private static byte[] toolCallBody(String toolName) throws Exception {
		var request = new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, "test-id",
				new McpSchema.CallToolRequest(toolName, Map.of(), null));
		return McpJsonMapperUtils.JSON_MAPPER.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
	}

	private static MockHttpServletRequest req(String uri, Map<String, String> headers) {
		var req = new MockHttpServletRequest();
		req.setMethod("POST");
		req.setRequestURI(uri);
		req.setContentType("application/json");
		req.addHeader("Accept", ACCEPT);
		headers.forEach(req::addHeader);
		return req;
	}

	private static MockHttpServletResponse invoke(HttpServlet servlet, String uri, Map<String, String> headers,
			byte[] body) throws Exception {
		var req = req(uri, headers);
		req.setContent(body);
		var resp = new MockHttpServletResponse();
		servlet.service(req, resp);
		return resp;
	}

	// --- Streamable servlet provider --------------------------------------------------

	@Test
	void streamableRejectsUnsupportedProtocolVersion() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.PROTOCOL_VERSION, "junk"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Unsupported protocol version");
	}

	@Test
	void streamableRejectsMcpMethodMismatch() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.MCP_METHOD, "wrong/method"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Method header mismatch");
	}

	@Test
	void streamableRejectsMcpNameMismatch() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.MCP_NAME, "wrong-name"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Name header mismatch");
	}

	@Test
	void streamableToleratesAbsentHeaders() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

		// Absent SEP-2243 headers must not by themselves trigger a validation error; the
		// request may legitimately fail later (e.g. missing session), but the rejection
		// must not be one of the SEP-2243 validation errors.
		var resp = invoke(provider, "/mcp", Map.of(), toolCallBody("t"));

		assertThat(resp.getContentAsString()).doesNotContain("Unsupported protocol version", "Mcp-Method header",
				"Mcp-Name header");
	}

	// --- Stateless transport ---------------------------------------------------------

	@Test
	void statelessRejectsUnsupportedProtocolVersionHeader() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();

		var resp = invoke(transport, "/mcp", Map.of(HttpHeaders.PROTOCOL_VERSION, "junk"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Unsupported protocol version");
	}

	@Test
	void statelessRejectsMcpMethodMismatch() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();

		var resp = invoke(transport, "/mcp", Map.of(HttpHeaders.MCP_METHOD, "wrong/method"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Method header mismatch");
	}

	@Test
	void statelessRejectsMcpNameMismatch() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();

		var resp = invoke(transport, "/mcp", Map.of(HttpHeaders.MCP_NAME, "wrong-name"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Name header mismatch");
	}

	@Test
	void statelessToleratesAbsentHeaders() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();

		var resp = invoke(transport, "/mcp", Map.of(), toolCallBody("t"));

		assertThat(resp.getContentAsString()).doesNotContain("Unsupported protocol version", "Mcp-Method header",
				"Mcp-Name header");
	}

}