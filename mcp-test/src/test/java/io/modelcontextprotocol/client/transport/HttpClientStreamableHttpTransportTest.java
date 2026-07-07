/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

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

	private McpTransportContext context = McpTransportContext
		.create(Map.of("test-transport-context-key", "some-value"));

	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/node:lts-alpine3.23")
		.withCommand("npx -y @modelcontextprotocol/server-everything@2025.12.18 streamableHttp")
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
		var mockRequestCustomizer = mock(McpSyncHttpClientRequestCustomizer.class);

		var transport = HttpClientStreamableHttpTransport.builder(host)
			.httpRequestCustomizer(mockRequestCustomizer)
			.build();

		withTransport(transport, (t) -> {
			// Send test message
			var initializeRequest = McpSchema.InitializeRequest
				.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().roots(true).build(),
						McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
				.build();
			var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

			StepVerifier
				.create(t.sendMessage(testMessage).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
				.verifyComplete();

			// Verify the customizer was called
			verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("POST"), eq(uri), eq(
					"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"MCP Client\",\"version\":\"0.3.1\"}}}"),
					eq(context));
		});
	}

	@Test
	void testAsyncRequestCustomizer() throws URISyntaxException {
		var uri = new URI(host + "/mcp");
		var mockRequestCustomizer = mock(McpAsyncHttpClientRequestCustomizer.class);
		when(mockRequestCustomizer.customize(any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> Mono.just(invocation.getArguments()[0]));

		var transport = HttpClientStreamableHttpTransport.builder(host)
			.asyncHttpRequestCustomizer(mockRequestCustomizer)
			.build();

		withTransport(transport, (t) -> {
			// Send test message
			var initializeRequest = McpSchema.InitializeRequest
				.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().roots(true).build(),
						McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
				.build();
			var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

			StepVerifier
				.create(t.sendMessage(testMessage).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
				.verifyComplete();

			// Verify the customizer was called
			verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("POST"), eq(uri), eq(
					"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"MCP Client\",\"version\":\"0.3.1\"}}}"),
					eq(context));
		});
	}

	@Test
	void testCloseUninitialized() {
		var transport = HttpClientStreamableHttpTransport.builder(host).build();

		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		var initializeRequest = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().roots(true).build(),
					McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
			.build();
		var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMessage("Transport has already been closed.")
			.verify();
	}

	@Test
	void testCloseInitialized() {
		var transport = HttpClientStreamableHttpTransport.builder(host).build();
		transport.connect(Function.identity()).block();

		var initializeRequest = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().roots(true).build(),
					McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
			.build();
		var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();
		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMatches(err -> err instanceof McpTransportSessionClosedException
					&& err.getMessage().contains("Transport has already been closed"))
			.verify();
	}

	@Test
	void testMcpMethodHeaderIsAddedForInitializeRequest() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		try {
			server.createContext("/mcp", exchange -> {
				try (exchange) {
					if (!"POST".equals(exchange.getRequestMethod())) {
						exchange.sendResponseHeaders(405, -1);
						return;
					}

					String methodHeader = exchange.getRequestHeaders().getFirst("Mcp-Method");
					byte[] requestBody = exchange.getRequestBody().readAllBytes();
					String body = new String(requestBody, StandardCharsets.UTF_8);

					if (!"initialize".equals(methodHeader) || !body.contains("\"method\":\"initialize\"")) {
						exchange.sendResponseHeaders(400, 0);
						return;
					}

					exchange.sendResponseHeaders(202, -1);
				}
			});
			server.start();

			int port = server.getAddress().getPort();
			var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
				.endpoint("/mcp")
				.build();
			try {
				var initializeRequest = McpSchema.InitializeRequest
					.builder(ProtocolVersions.MCP_2025_11_25,
							McpSchema.ClientCapabilities.builder().roots(true).build(),
							McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
					.build();
				var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id",
						initializeRequest);

				StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();
			}
			finally {
				StepVerifier.create(transport.closeGracefully()).verifyComplete();
			}
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void testMcpNameHeaderIsAddedForToolsCall() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		try {
			server.createContext("/mcp", exchange -> {
				try (exchange) {
					if (!"POST".equals(exchange.getRequestMethod())) {
						exchange.sendResponseHeaders(405, -1);
						return;
					}

					String nameHeader = exchange.getRequestHeaders().getFirst("Mcp-Name");
					byte[] requestBody = exchange.getRequestBody().readAllBytes();
					String body = new String(requestBody, StandardCharsets.UTF_8);

					if (!"test-tool".equals(nameHeader) || !body.contains("\"method\":\"tools/call\"")) {
						exchange.sendResponseHeaders(400, 0);
						return;
					}

					exchange.sendResponseHeaders(202, -1);
				}
			});
			server.start();

			int port = server.getAddress().getPort();
			var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
				.endpoint("/mcp")
				.build();
			try {
				var callToolRequest = McpSchema.CallToolRequest.builder("test-tool").build();
				var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, "test-id", callToolRequest);

				StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();
			}
			finally {
				StepVerifier.create(transport.closeGracefully()).verifyComplete();
			}
		}
		finally {
			server.stop(0);
		}
	}

}
