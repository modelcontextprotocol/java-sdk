/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.MockMcpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MCP protocol version negotiation and compatibility.
 */
class McpClientProtocolVersionTests {

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private static final McpSchema.Implementation CLIENT_INFO = new McpSchema.Implementation("test-client", "1.0.0");

	@Test
	void shouldUseLatestVersionByDefault() {
		MockMcpClientTransport transport = new MockMcpClientTransport();
		McpAsyncClient client = McpClient.async(transport)
			.clientInfo(CLIENT_INFO)
			.requestTimeout(REQUEST_TIMEOUT)
			.build();

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				assertThat(request.params()).isInstanceOf(McpSchema.InitializeRequest.class);
				McpSchema.InitializeRequest initRequest = (McpSchema.InitializeRequest) request.params();
				assertThat(initRequest.protocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);

				transport.simulateIncomingMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						new McpSchema.InitializeResult(McpSchema.LATEST_PROTOCOL_VERSION, null,
								new McpSchema.Implementation("test-server", "1.0.0"), null),
						null));
			}).assertNext(result -> {
				assertThat(result.protocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);
			}).verifyComplete();

		}
		finally {
			// Ensure cleanup happens even if test fails
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void shouldNegotiateSpecificVersion() {
		String oldVersion = "0.1.0";
		MockMcpClientTransport transport = new MockMcpClientTransport();
		McpAsyncClient client = McpClient.async(transport)
			.clientInfo(CLIENT_INFO)
			.requestTimeout(REQUEST_TIMEOUT)
			.build();

		client.setProtocolVersions(List.of(oldVersion, McpSchema.LATEST_PROTOCOL_VERSION));

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				assertThat(request.params()).isInstanceOf(McpSchema.InitializeRequest.class);
				McpSchema.InitializeRequest initRequest = (McpSchema.InitializeRequest) request.params();
				assertThat(initRequest.protocolVersion()).isIn(List.of(oldVersion, McpSchema.LATEST_PROTOCOL_VERSION));

				transport.simulateIncomingMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						new McpSchema.InitializeResult(oldVersion, null,
								new McpSchema.Implementation("test-server", "1.0.0"), null),
						null));
			}).assertNext(result -> {
				assertThat(result.protocolVersion()).isEqualTo(oldVersion);
			}).verifyComplete();
		}
		finally {
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void shouldFailForUnsupportedVersion() {
		String unsupportedVersion = "999.999.999";
		MockMcpClientTransport transport = new MockMcpClientTransport();
		McpAsyncClient client = McpClient.async(transport)
			.clientInfo(CLIENT_INFO)
			.requestTimeout(REQUEST_TIMEOUT)
			.build();

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				assertThat(request.params()).isInstanceOf(McpSchema.InitializeRequest.class);

				transport.simulateIncomingMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						new McpSchema.InitializeResult(unsupportedVersion, null,
								new McpSchema.Implementation("test-server", "1.0.0"), null),
						null));
			}).expectError(McpError.class).verify();
		}
		finally {
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void shouldUseHighestVersionWhenMultipleSupported() {
		String oldVersion = "0.1.0";
		String middleVersion = "0.2.0";
		String latestVersion = McpSchema.LATEST_PROTOCOL_VERSION;

		MockMcpClientTransport transport = new MockMcpClientTransport();
		McpAsyncClient client = McpClient.async(transport)
			.clientInfo(CLIENT_INFO)
			.requestTimeout(REQUEST_TIMEOUT)
			.build();

		client.setProtocolVersions(List.of(oldVersion, middleVersion, latestVersion));

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				McpSchema.InitializeRequest initRequest = (McpSchema.InitializeRequest) request.params();
				assertThat(initRequest.protocolVersion()).isEqualTo(latestVersion);

				transport.simulateIncomingMessage(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						new McpSchema.InitializeResult(latestVersion, null,
								new McpSchema.Implementation("test-server", "1.0.0"), null),
						null));
			}).assertNext(result -> {
				assertThat(result.protocolVersion()).isEqualTo(latestVersion);
			}).verifyComplete();
		}
		finally {
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}

	}

}
