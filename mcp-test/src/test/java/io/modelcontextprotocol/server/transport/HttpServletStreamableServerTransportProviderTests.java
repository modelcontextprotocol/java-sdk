/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider.APPLICATION_JSON;
import static io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider.TEXT_EVENT_STREAM;
import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

class HttpServletStreamableServerTransportProviderTests {

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	@Test
	void shouldRejectDuplicateInitializeForActiveSession() throws Exception {
		var transportProvider = HttpServletStreamableServerTransportProvider.builder()
			.mcpEndpoint(MESSAGE_ENDPOINT)
			.build();
		var server = McpServer.sync(transportProvider).serverInfo("test-server", "1.0.0").build();

		try {
			MockHttpServletResponse initialResponse = initialize(transportProvider, null, "init-1",
					ProtocolVersions.MCP_2025_11_25, "initial-client");
			assertThat(initialResponse.getStatus()).isEqualTo(200);

			String sessionId = initialResponse.getHeader(HttpHeaders.MCP_SESSION_ID);
			assertThat(sessionId).isNotBlank();

			MockHttpServletResponse initializedResponse = sendMessage(transportProvider, sessionId,
					new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED),
					ProtocolVersions.MCP_2025_11_25);
			assertThat(initializedResponse.getStatus()).isEqualTo(202);

			MockHttpServletResponse duplicateResponse = initialize(transportProvider, sessionId, "init-2",
					ProtocolVersions.MCP_2024_11_05, "duplicate-client");

			assertThat(duplicateResponse.getStatus()).isEqualTo(400);
			assertThat(duplicateResponse.getHeader(HttpHeaders.MCP_SESSION_ID)).isNull();
			assertThat(duplicateResponse.getContentAsString()).contains("Duplicate initialize");
		}
		finally {
			server.closeGracefully();
		}
	}

	private static MockHttpServletResponse initialize(HttpServletStreamableServerTransportProvider transportProvider,
			String sessionId, String requestId, String protocolVersion, String clientName) throws Exception {
		McpSchema.InitializeRequest initializeRequest = McpSchema.InitializeRequest
			.builder(protocolVersion, McpSchema.ClientCapabilities.builder().roots(true).build(),
					McpSchema.Implementation.builder(clientName, "1.0.0").build())
			.build();
		McpSchema.JSONRPCRequest jsonRpcRequest = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, requestId,
				initializeRequest);
		return sendMessage(transportProvider, sessionId, jsonRpcRequest, protocolVersion);
	}

	private static MockHttpServletResponse sendMessage(HttpServletStreamableServerTransportProvider transportProvider,
			String sessionId, McpSchema.JSONRPCMessage message, String protocolVersion) throws Exception {
		byte[] content = JSON_MAPPER.writeValueAsBytes(message);

		MockHttpServletRequest request = new MockHttpServletRequest("POST", MESSAGE_ENDPOINT);
		request.setContent(content);
		request.addHeader("Content-Type", APPLICATION_JSON);
		request.addHeader("Content-Length", Integer.toString(content.length));
		request.addHeader("Accept", APPLICATION_JSON + ", " + TEXT_EVENT_STREAM);
		request.addHeader(HttpHeaders.PROTOCOL_VERSION, protocolVersion);
		if (sessionId != null) {
			request.addHeader(HttpHeaders.MCP_SESSION_ID, sessionId);
		}

		MockHttpServletResponse response = new MockHttpServletResponse();
		transportProvider.service(request, response);
		return response;
	}

}
