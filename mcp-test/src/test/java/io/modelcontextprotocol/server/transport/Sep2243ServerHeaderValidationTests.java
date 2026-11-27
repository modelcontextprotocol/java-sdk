/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.McpJsonMapperUtils;
import jakarta.servlet.http.HttpServlet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;

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

	private record Response(int getStatus, String getContentAsString) {
	}

	private static Response invoke(HttpServlet servlet, String uri, Map<String, String> headers, byte[] body)
			throws Exception {
		return invoke(servlet, "POST", uri, headers, body);
	}

	private static Response invoke(HttpServlet servlet, String method, String uri, Map<String, String> headers,
			byte[] body) throws Exception {
		int port = TomcatTestUtil.findAvailablePort();
		Tomcat tomcat = TomcatTestUtil.createTomcatServer("", port, servlet);
		tomcat.start();
		try {
			var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + uri))
				.method(method, HttpRequest.BodyPublishers.ofByteArray(body))
				.header("Accept", ACCEPT)
				.header("Content-Type", "application/json");
			headers.forEach(request::header);
			var response = HttpClient.newHttpClient()
				.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return new Response(response.statusCode(), response.body());
		}
		finally {
			tomcat.stop();
			tomcat.destroy();
		}
	}

	// --- Streamable servlet provider --------------------------------------------------

	@Test
	void streamableRejectsUnsupportedProtocolVersion() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.PROTOCOL_VERSION, "junk"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Unsupported protocol version", "-32600");
		assertThat(resp.getContentAsString()).doesNotContain("-32601");
	}

	@Test
	void streamableRejectsMissingMcpMethodWhenRequired() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder()
			.mcpEndpoint("/mcp")
			.requireMcpHeaders(true)
			.build();

		var resp = invoke(provider, "/mcp", Map.of(), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("-32020", "Mcp-Method");
	}

	@Test
	void streamableRejectsMissingMcpNameWhenRequired() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder()
			.mcpEndpoint("/mcp")
			.requireMcpHeaders(true)
			.build();

		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.MCP_METHOD, McpSchema.METHOD_TOOLS_CALL),
				toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Name");
	}

	@Test
	void streamableAcceptsCompleteHeadersWhenRequired() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder()
			.mcpEndpoint("/mcp")
			.requireMcpHeaders(true)
			.build();

		// A dummy session ID is supplied purely to get past the transport's unrelated
		// "session required" check (a 404 for an unknown session) so that this test
		// isolates the outcome of the SEP-2243 header validation itself.
		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.MCP_METHOD, McpSchema.METHOD_TOOLS_CALL,
				HttpHeaders.MCP_NAME, "t", HttpHeaders.MCP_SESSION_ID, "test-session"), toolCallBody("t"));

		assertThat(resp.getStatus()).isNotEqualTo(400);
		assertThat(resp.getContentAsString()).doesNotContain("-32020");
	}

	@Test
	void streamableRejectsMcpMethodMismatch() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.MCP_METHOD, "wrong/method"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Method header mismatch", "-32020");
	}

	@Test
	void streamableRejectsMcpNameMismatch() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.MCP_NAME, "wrong-name"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Name header mismatch", "-32020");
	}

	@Test
	void streamableAcceptsBase64EncodedMcpName() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.MCP_NAME, "=?base64?6KiI566X5qmf?="),
				toolCallBody("計算機"));

		assertThat(resp.getContentAsString()).doesNotContain("Mcp-Name header mismatch");
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
		assertThat(resp.getContentAsString()).contains("Unsupported protocol version", "-32600");
		assertThat(resp.getContentAsString()).doesNotContain("-32601");
	}

	@Test
	void statelessRejectsMissingMcpMethodWhenRequired() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder()
			.messageEndpoint("/mcp")
			.requireMcpHeaders(true)
			.build();

		var resp = invoke(transport, "/mcp", Map.of(), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("-32020", "Mcp-Method");
	}

	@Test
	void statelessRejectsMissingMcpNameWhenRequired() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder()
			.messageEndpoint("/mcp")
			.requireMcpHeaders(true)
			.build();

		var resp = invoke(transport, "/mcp", Map.of(HttpHeaders.MCP_METHOD, McpSchema.METHOD_TOOLS_CALL),
				toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Name");
	}

	@Test
	void statelessAcceptsCompleteHeadersWhenRequired() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder()
			.messageEndpoint("/mcp")
			.requireMcpHeaders(true)
			.build();

		var resp = invoke(transport, "/mcp",
				Map.of(HttpHeaders.MCP_METHOD, McpSchema.METHOD_TOOLS_CALL, HttpHeaders.MCP_NAME, "t"),
				toolCallBody("t"));

		assertThat(resp.getStatus()).isNotEqualTo(400);
		assertThat(resp.getContentAsString()).doesNotContain("-32020");
	}

	@Test
	void statelessRejectsMcpMethodMismatch() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();

		var resp = invoke(transport, "/mcp", Map.of(HttpHeaders.MCP_METHOD, "wrong/method"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Method header mismatch", "-32020");
	}

	@Test
	void statelessRejectsMcpNameMismatch() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();

		var resp = invoke(transport, "/mcp", Map.of(HttpHeaders.MCP_NAME, "wrong-name"), toolCallBody("t"));

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Mcp-Name header mismatch", "-32020");
	}

	@Test
	void statelessAcceptsBase64EncodedMcpName() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();

		var resp = invoke(transport, "/mcp", Map.of(HttpHeaders.MCP_NAME, "=?base64?6KiI566X5qmf?="),
				toolCallBody("計算機"));

		assertThat(resp.getContentAsString()).doesNotContain("Mcp-Name header mismatch");
	}

	@Test
	void statelessToleratesAbsentHeaders() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();

		var resp = invoke(transport, "/mcp", Map.of(), toolCallBody("t"));

		assertThat(resp.getContentAsString()).doesNotContain("Unsupported protocol version", "Mcp-Method header",
				"Mcp-Name header");
	}

	// --- Initialize requests must not be rejected on MCP-Protocol-Version ------------

	private static byte[] initializeBody() throws Exception {
		var request = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id",
				Map.of("protocolVersion", "2263-03-18"));
		return McpJsonMapperUtils.JSON_MAPPER.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void streamableToleratesUnsupportedProtocolVersionOnInitialize() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

		// During initialization no protocol version has been negotiated yet, so any
		// header value must be resolved through regular version negotiation instead of
		// a hard 400.
		var resp = invoke(provider, "/mcp", Map.of(HttpHeaders.PROTOCOL_VERSION, "junk"), initializeBody());

		assertThat(resp.getContentAsString()).doesNotContain("Unsupported protocol version");
	}

	@Test
	void statelessToleratesUnsupportedProtocolVersionOnInitialize() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();

		var resp = invoke(transport, "/mcp", Map.of(HttpHeaders.PROTOCOL_VERSION, "junk"), initializeBody());

		assertThat(resp.getContentAsString()).doesNotContain("Unsupported protocol version");
	}

	@Test
	void streamableRejectsUnsupportedProtocolVersionOnGet() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
		var resp = invoke(provider, "GET", "/mcp", Map.of(HttpHeaders.PROTOCOL_VERSION, "junk"), new byte[0]);

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Unsupported protocol version");
	}

	@Test
	void streamableRejectsUnsupportedProtocolVersionOnDelete() throws Exception {
		var provider = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
		var resp = invoke(provider, "DELETE", "/mcp", Map.of(HttpHeaders.PROTOCOL_VERSION, "junk"), new byte[0]);

		assertThat(resp.getStatus()).isEqualTo(400);
		assertThat(resp.getContentAsString()).contains("Unsupported protocol version");
	}

}
