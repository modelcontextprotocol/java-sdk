/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

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

	private final McpSchema.Tool toolSpec = McpSchema.Tool.builder()
		.name("test-tool")
		.description("return the protocol version used")
		.build();

	private final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> toolHandler = (
			exchange, request) -> new McpSchema.CallToolResult(
					exchange.transportContext().get("protocol-version").toString(), null);

	McpSyncServer mcpServer = McpServer.sync(transport)
		.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
		.tools(new McpServerFeatures.SyncToolSpecification(toolSpec, null, toolHandler))
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
		McpSchema.CallToolResult response = client.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		var calls = requestRecordingFilter.getCalls();

		assertThat(calls).filteredOn(c -> !c.body().contains("\"method\":\"initialize\""))
			// GET /mcp ; POST notification/initialized ; POST tools/call
			.hasSize(3)
			.map(McpTestRequestRecordingServletFilter.Call::headers)
			.allSatisfy(headers -> assertThat(headers).containsEntry("mcp-protocol-version",
					ProtocolVersions.MCP_2025_06_18));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo(ProtocolVersions.MCP_2025_06_18);
		mcpServer.close();
	}

	@Test
	void usesCustomLatestVersion() {
		startTomcat();

		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
			.supportedProtocolVersions(List.of(ProtocolVersions.MCP_2025_06_18, "2263-03-18"))
			.build();
		var client = McpClient.sync(transport).build();

		client.initialize();
		McpSchema.CallToolResult response = client.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		var calls = requestRecordingFilter.getCalls();

		assertThat(calls).filteredOn(c -> !c.body().contains("\"method\":\"initialize\""))
			// GET /mcp ; POST notification/initialized ; POST tools/call
			.hasSize(3)
			.map(McpTestRequestRecordingServletFilter.Call::headers)
			.allSatisfy(headers -> assertThat(headers).containsEntry("mcp-protocol-version", "2263-03-18"));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("2263-03-18");
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
