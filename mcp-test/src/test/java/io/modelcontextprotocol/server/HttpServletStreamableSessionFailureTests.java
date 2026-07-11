/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider.APPLICATION_JSON;
import static io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider.TEXT_EVENT_STREAM;
import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class HttpServletStreamableSessionFailureTests {

	private static final String MCP_ENDPOINT = "/mcp";

	@Test
	void postStreamWriteFailureShouldNotRemoveSession() throws Exception {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.mcpEndpoint(MCP_ENDPOINT)
			.build();

		var tool = McpSchema.Tool.builder("test-tool").description("Test tool").build();
		var toolSpecification = McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
				.content(List.of(McpSchema.TextContent.builder("tool response").build()))
				.isError(false)
				.build())
			.build();
		var server = McpServer.sync(transport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
			.tools(toolSpecification)
			.build();

		try {
			MockHttpServletResponse initializeResponse = new MockHttpServletResponse();
			transport.service(postRequest(initializeRequest(), null), initializeResponse);

			String sessionId = initializeResponse.getHeader(HttpHeaders.MCP_SESSION_ID);
			assertThat(sessionId).isNotBlank();

			CheckErrorResponse failedWriteResponse = new CheckErrorResponse();
			transport.service(postRequest(toolCallRequest("first-call"), sessionId), failedWriteResponse);

			assertThat(failedWriteResponse.getWrittenContent()).contains("event: message");

			MockHttpServletResponse subsequentResponse = new MockHttpServletResponse();
			transport.service(postRequest(toolCallRequest("second-call"), sessionId), subsequentResponse);

			assertThat(subsequentResponse.getStatus()).isNotEqualTo(404);
			assertThat(subsequentResponse.getContentAsString()).doesNotContain("Session not found");
		}
		finally {
			server.close();
			transport.closeGracefully().block();
		}
	}

	private static MockHttpServletRequest postRequest(McpSchema.JSONRPCMessage message, String sessionId)
			throws IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", MCP_ENDPOINT);
		byte[] content = JSON_MAPPER.writeValueAsBytes(message);
		request.setContent(content);
		request.setCharacterEncoding(StandardCharsets.UTF_8.name());
		request.addHeader("Accept", APPLICATION_JSON + ", " + TEXT_EVENT_STREAM);
		request.addHeader("Content-Type", APPLICATION_JSON);
		request.addHeader("Content-Length", Integer.toString(content.length));
		request.addHeader(HttpHeaders.PROTOCOL_VERSION, ProtocolVersions.MCP_2025_11_25);
		request.setAsyncSupported(true);
		if (sessionId != null) {
			request.addHeader(HttpHeaders.MCP_SESSION_ID, sessionId);
		}
		return request;
	}

	private static McpSchema.JSONRPCRequest initializeRequest() {
		var clientInfo = McpSchema.Implementation.builder("test-client", "1.0.0").build();
		var initializeRequest = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().build(), clientInfo)
			.build();
		return new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "init", initializeRequest);
	}

	private static McpSchema.JSONRPCRequest toolCallRequest(String id) {
		var callToolRequest = McpSchema.CallToolRequest.builder("test-tool").arguments(Map.of()).build();
		return new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, id, callToolRequest);
	}

	private static final class CheckErrorResponse extends MockHttpServletResponse {

		private final StringWriter content = new StringWriter();

		private final PrintWriter writer = new PrintWriter(this.content) {
			@Override
			public boolean checkError() {
				super.checkError();
				return true;
			}
		};

		@Override
		public PrintWriter getWriter() {
			return this.writer;
		}

		String getWrittenContent() {
			this.writer.flush();
			return this.content.toString();
		}

	}

}
