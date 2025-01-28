/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.UUID;

import io.modelcontextprotocol.MockMcpTransport;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MCP server protocol version negotiation and compatibility.
 */
class McpServerProtocolVersionTests {

	private static final McpSchema.Implementation SERVER_INFO = new McpSchema.Implementation("test-server", "1.0.0");

	private static final McpSchema.Implementation CLIENT_INFO = new McpSchema.Implementation("test-client", "1.0.0");

	private McpSchema.JSONRPCRequest jsonRpcInitializeRequest(String requestId, String protocolVersion) {
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE, requestId,
				new McpSchema.InitializeRequest(protocolVersion, null, CLIENT_INFO));
	}

	@Test
	void shouldUseLatestVersionByDefault() {
		MockMcpTransport transport = new MockMcpTransport();
		McpAsyncServer server = McpServer.async(transport).serverInfo(SERVER_INFO).build();

		String requestId = UUID.randomUUID().toString();

		transport.simulateIncomingMessage(jsonRpcInitializeRequest(requestId, McpSchema.LATEST_PROTOCOL_VERSION));

		McpSchema.JSONRPCMessage response = transport.getLastSentMessage();
		assertThat(response).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse jsonResponse = (McpSchema.JSONRPCResponse) response;
		assertThat(jsonResponse.id()).isEqualTo(requestId);
		assertThat(jsonResponse.result()).isInstanceOf(McpSchema.InitializeResult.class);
		McpSchema.InitializeResult result = (McpSchema.InitializeResult) jsonResponse.result();
		assertThat(result.protocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);

		server.closeGracefully().subscribe();
	}

	@Test
	void shouldNegotiateSpecificVersion() {
		String oldVersion = "0.1.0";
		MockMcpTransport transport = new MockMcpTransport();
		McpAsyncServer server = McpServer.async(transport).serverInfo(SERVER_INFO).build();

		server.setProtocolVersions(List.of(oldVersion, McpSchema.LATEST_PROTOCOL_VERSION));

		String requestId = UUID.randomUUID().toString();

		transport.simulateIncomingMessage(jsonRpcInitializeRequest(requestId, oldVersion));

		McpSchema.JSONRPCMessage response = transport.getLastSentMessage();
		assertThat(response).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse jsonResponse = (McpSchema.JSONRPCResponse) response;
		assertThat(jsonResponse.id()).isEqualTo(requestId);
		assertThat(jsonResponse.result()).isInstanceOf(McpSchema.InitializeResult.class);
		McpSchema.InitializeResult result = (McpSchema.InitializeResult) jsonResponse.result();
		assertThat(result.protocolVersion()).isEqualTo(oldVersion);

		server.closeGracefully().subscribe();
	}

	@Test
	void shouldSuggestLatestVersionForUnsupportedVersion() {
		String unsupportedVersion = "999.999.999";
		MockMcpTransport transport = new MockMcpTransport();
		McpAsyncServer server = McpServer.async(transport).serverInfo(SERVER_INFO).build();

		String requestId = UUID.randomUUID().toString();

		transport.simulateIncomingMessage(jsonRpcInitializeRequest(requestId, unsupportedVersion));

		McpSchema.JSONRPCMessage response = transport.getLastSentMessage();
		assertThat(response).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse jsonResponse = (McpSchema.JSONRPCResponse) response;
		assertThat(jsonResponse.id()).isEqualTo(requestId);
		assertThat(jsonResponse.result()).isInstanceOf(McpSchema.InitializeResult.class);
		McpSchema.InitializeResult result = (McpSchema.InitializeResult) jsonResponse.result();
		assertThat(result.protocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);

		server.closeGracefully().subscribe();
	}

	@Test
	void shouldUseHighestVersionWhenMultipleSupported() {
		String oldVersion = "0.1.0";
		String middleVersion = "0.2.0";
		String latestVersion = McpSchema.LATEST_PROTOCOL_VERSION;

		MockMcpTransport transport = new MockMcpTransport();
		McpAsyncServer server = McpServer.async(transport).serverInfo(SERVER_INFO).build();

		server.setProtocolVersions(List.of(oldVersion, middleVersion, latestVersion));

		String requestId = UUID.randomUUID().toString();
		transport.simulateIncomingMessage(jsonRpcInitializeRequest(requestId, latestVersion));

		McpSchema.JSONRPCMessage response = transport.getLastSentMessage();
		assertThat(response).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse jsonResponse = (McpSchema.JSONRPCResponse) response;
		assertThat(jsonResponse.id()).isEqualTo(requestId);
		assertThat(jsonResponse.result()).isInstanceOf(McpSchema.InitializeResult.class);
		McpSchema.InitializeResult result = (McpSchema.InitializeResult) jsonResponse.result();
		assertThat(result.protocolVersion()).isEqualTo(latestVersion);

		server.closeGracefully().subscribe();
	}

}
