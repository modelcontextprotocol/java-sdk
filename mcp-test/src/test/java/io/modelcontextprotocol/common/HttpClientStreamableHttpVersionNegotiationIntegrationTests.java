/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.McpTestRequestRecordingServletFilter;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientStreamableHttpVersionNegotiationIntegrationTests {

	private Tomcat tomcat;

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private final McpTestRequestRecordingServletFilter requestRecordingFilter = new McpTestRequestRecordingServletFilter();

	private final HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider
		.builder()
		.contextExtractor(
				req -> McpTransportContext.create(Map.of("protocol-version", req.getHeader("MCP-protocol-version"))))
		.build();

	private final McpSchema.Tool toolSpec = McpSchema.Tool.builder("test-tool")
		.description("return the protocol version used")
		.build();

	private final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> toolHandler = (
			exchange, request) -> McpSchema.CallToolResult.builder()
				.addTextContent(exchange.transportContext().get("protocol-version").toString())
				.isError(false)
				.build();

	McpSyncServer mcpServer = McpServer.sync(transport)
		.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
		.tools(McpServerFeatures.SyncToolSpecification.builder().tool(toolSpec).callHandler(toolHandler).build())
		.build();

	@AfterEach
	void tearDown() {
		stopTomcat();
	}

	@Test
	void usesLatestVersion() {
		startTomcat();

		var client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT).build())
			.build();

		client.initialize();
		McpSchema.CallToolResult response = client
			.callTool(McpSchema.CallToolRequest.builder("test-tool").arguments(Map.of()).build());

		var calls = requestRecordingFilter.getCalls();

		assertThat(calls).filteredOn(c -> !c.body().contains("\"method\":\"initialize\""))
			// GET /mcp ; POST notification/initialized ; POST tools/call
			.hasSize(3)
			.map(McpTestRequestRecordingServletFilter.Call::headers)
			.allSatisfy(headers -> assertThat(headers).containsEntry("mcp-protocol-version",
					ProtocolVersions.MCP_2025_11_25));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo(ProtocolVersions.MCP_2025_11_25);
		mcpServer.close();
	}

	@Test
	void usesServerSupportedVersion() {
		startTomcat();

		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
			.supportedProtocolVersions(List.of(ProtocolVersions.MCP_2025_11_25, "2263-03-18"))
			.build();
		var client = McpClient.sync(transport).build();

		client.initialize();
		McpSchema.CallToolResult response = client
			.callTool(McpSchema.CallToolRequest.builder("test-tool").arguments(Map.of()).build());

		var calls = requestRecordingFilter.getCalls();
		// Initialize tells the server the Client's latest supported version
		// FIXME: Set the correct protocol version on GET /mcp
		assertThat(calls).filteredOn(c -> c.method().equals("POST") && !c.body().contains("\"method\":\"initialize\""))
			// POST notification/initialized ; POST tools/call
			.hasSize(2)
			.map(McpTestRequestRecordingServletFilter.Call::headers)
			.allSatisfy(headers -> assertThat(headers).containsEntry("mcp-protocol-version",
					ProtocolVersions.MCP_2025_11_25));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo(ProtocolVersions.MCP_2025_11_25);
		mcpServer.close();
	}

	@ParameterizedTest
	@ValueSource(strings = { "1900-01-01", "not-a-version" })
	void rejectsUnsupportedProtocolVersionHeaders(String protocolVersion) throws Exception {
		startTomcat();

		HttpClient httpClient = HttpClient.newHttpClient();
		String endpoint = "http://localhost:" + PORT + "/mcp";

		HttpResponse<String> initializeResponse = httpClient.send(HttpRequest.newBuilder()
			.uri(URI.create(endpoint))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("MCP-Protocol-Version", ProtocolVersions.MCP_2025_11_25)
			.POST(HttpRequest.BodyPublishers.ofString(
					"""
							{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}
							"""))
			.build(), HttpResponse.BodyHandlers.ofString());

		assertThat(initializeResponse.statusCode()).isEqualTo(200);
		String sessionId = initializeResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

		HttpResponse<String> initializedResponse = httpClient.send(HttpRequest.newBuilder()
			.uri(URI.create(endpoint))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("MCP-Protocol-Version", ProtocolVersions.MCP_2025_11_25)
			.header("Mcp-Session-Id", sessionId)
			.POST(HttpRequest.BodyPublishers
				.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}"))
			.build(), HttpResponse.BodyHandlers.ofString());

		assertThat(initializedResponse.statusCode()).isEqualTo(202);

		HttpResponse<String> badVersionResponse = httpClient.send(HttpRequest.newBuilder()
			.uri(URI.create(endpoint))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("MCP-Protocol-Version", protocolVersion)
			.header("Mcp-Session-Id", sessionId)
			.POST(HttpRequest.BodyPublishers
				.ofString("{\"jsonrpc\":\"2.0\",\"id\":\"bad-version-1\",\"method\":\"tools/list\",\"params\":{}}"))
			.build(), HttpResponse.BodyHandlers.ofString());

		assertThat(badVersionResponse.statusCode()).isEqualTo(400);
		assertThat(badVersionResponse.body()).contains("Unsupported or invalid MCP protocol version");
		mcpServer.close();
	}

	private void startTomcat() {
		tomcat = TomcatTestUtil.createTomcatServer("", PORT, transport, requestRecordingFilter);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	private void stopTomcat() {
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

}
