/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.MockMcpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MCP protocol version negotiation and compatibility.
 */
class McpClientProtocolVersionTests {

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);

	private static final McpSchema.Implementation CLIENT_INFO = McpSchema.Implementation.builder("test-client", "1.0.0")
		.build();

	@Test
	void shouldUseLatestVersionByDefault() {
		MockMcpClientTransport transport = new MockMcpClientTransport();
		McpAsyncClient client = McpClient.async(transport)
			.clientInfo(CLIENT_INFO)
			.requestTimeout(REQUEST_TIMEOUT)
			.build();

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			String protocolVersion = transport.protocolVersions().get(transport.protocolVersions().size() - 1);

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				assertThat(request.params()).isInstanceOf(McpSchema.InitializeRequest.class);
				McpSchema.InitializeRequest initRequest = (McpSchema.InitializeRequest) request.params();
				assertThat(initRequest.protocolVersion()).isEqualTo(transport.protocolVersions().get(0));

				transport.simulateIncomingMessage(McpSchema.JSONRPCResponse.result(request.id(),
						McpSchema.InitializeResult
							.builder(protocolVersion, ServerCapabilities.builder().build(),
									McpSchema.Implementation.builder("test-server", "1.0.0").build())
							.build()));
			}).assertNext(result -> {
				assertThat(result.protocolVersion()).isEqualTo(protocolVersion);
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

		client.setProtocolVersions(List.of(oldVersion, ProtocolVersions.MCP_2025_11_25));

		try {
			Mono<InitializeResult> initializeResultMono = client.initialize();

			StepVerifier.create(initializeResultMono).then(() -> {
				McpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
				assertThat(request.params()).isInstanceOf(McpSchema.InitializeRequest.class);
				McpSchema.InitializeRequest initRequest = (McpSchema.InitializeRequest) request.params();
				assertThat(initRequest.protocolVersion()).isIn(List.of(oldVersion, ProtocolVersions.MCP_2025_11_25));

				transport.simulateIncomingMessage(McpSchema.JSONRPCResponse.result(request.id(),
						McpSchema.InitializeResult
							.builder(oldVersion, ServerCapabilities.builder().build(),
									McpSchema.Implementation.builder("test-server", "1.0.0").build())
							.build()));
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

				transport.simulateIncomingMessage(McpSchema.JSONRPCResponse.result(request.id(),
						McpSchema.InitializeResult
							.builder(unsupportedVersion, ServerCapabilities.builder().build(),
									McpSchema.Implementation.builder("test-server", "1.0.0").build())
							.build()));
			}).expectError(RuntimeException.class).verify();
		}
		finally {
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void shouldUseHighestVersionWhenMultipleSupported() {
		String oldVersion = "0.1.0";
		String middleVersion = "0.2.0";
		String latestVersion = ProtocolVersions.MCP_2025_11_25;

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

				transport.simulateIncomingMessage(McpSchema.JSONRPCResponse.result(request.id(),
						McpSchema.InitializeResult
							.builder(latestVersion, ServerCapabilities.builder().build(),
									McpSchema.Implementation.builder("test-server", "1.0.0").build())
							.build()));
			}).assertNext(result -> {
				assertThat(result.protocolVersion()).isEqualTo(latestVersion);
			}).verifyComplete();
		}
		finally {
			StepVerifier.create(client.closeGracefully()).verifyComplete();
		}

	}

}
