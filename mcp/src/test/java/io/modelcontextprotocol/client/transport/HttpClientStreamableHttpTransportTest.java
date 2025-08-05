/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link HttpClientStreamableHttpTransport} class.
 *
 * @author Daniel Garnier-Moiroux
 */
class HttpClientStreamableHttpTransportTest {

	static String host = "http://localhost:3001";

	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v2")
		.withCommand("node dist/index.js streamableHttp")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	@BeforeAll
	static void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@AfterAll
	static void stopContainer() {
		container.stop();
	}

	void withTransport(HttpClientStreamableHttpTransport transport, Consumer<HttpClientStreamableHttpTransport> c) {
		try {
			c.accept(transport);
		}
		finally {
			StepVerifier.create(transport.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void testRequestCustomizer() throws URISyntaxException {
		var uri = new URI(host + "/mcp");
		var mockRequestCustomizer = mock(McpSyncHttpRequestCustomizer.class);

		var transport = HttpClientStreamableHttpTransport.builder(host)
			.httpRequestCustomizer(mockRequestCustomizer)
			.build();

		withTransport(transport, (t) -> {
			// Send test message
			var initializeRequest = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
					McpSchema.ClientCapabilities.builder().roots(true).build(),
					new McpSchema.Implementation("Spring AI MCP Client", "0.3.1"));
			var testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE,
					"test-id", initializeRequest);

			StepVerifier.create(t.sendMessage(testMessage)).verifyComplete();

			// Verify the customizer was called
			verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("GET"), eq(uri), eq(
					"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"Spring AI MCP Client\",\"version\":\"0.3.1\"}}}"));
		});
	}

	@Test
	void testAsyncRequestCustomizer() throws URISyntaxException {
		var uri = new URI(host + "/mcp");
		var mockRequestCustomizer = mock(McpAsyncHttpRequestCustomizer.class);
		when(mockRequestCustomizer.customize(any(), any(), any(), any()))
			.thenAnswer(invocation -> Mono.just(invocation.getArguments()[0]));

		var transport = HttpClientStreamableHttpTransport.builder(host)
			.asyncHttpRequestCustomizer(mockRequestCustomizer)
			.build();

		withTransport(transport, (t) -> {
			// Send test message
			var initializeRequest = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
					McpSchema.ClientCapabilities.builder().roots(true).build(),
					new McpSchema.Implementation("Spring AI MCP Client", "0.3.1"));
			var testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE,
					"test-id", initializeRequest);

			StepVerifier.create(t.sendMessage(testMessage)).verifyComplete();

			// Verify the customizer was called
			verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("GET"), eq(uri), eq(
					"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"Spring AI MCP Client\",\"version\":\"0.3.1\"}}}"));
		});
	}

}
