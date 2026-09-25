/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates MCP-Protocol-Version header consistency enforcement in
 * {@link HttpServletStreamableServerTransportProvider}.
 *
 * @author Gorre Surya
 */
class StreamableTransportProtocolVersionTests {

	private static final String ACCEPT_HEADER = "application/json, text/event-stream";

	private static final String CONTENT_TYPE = "application/json";

	private static Tomcat tomcat;

	private static String baseUrl;

	private static HttpClient httpClient;

	@BeforeAll
	static void setUp() throws Exception {
		var port = TomcatTestUtil.findAvailablePort();
		baseUrl = "http://localhost:" + port;

		var transport = HttpServletStreamableServerTransportProvider.builder().build();
		McpServer.sync(transport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", port, transport);
		tomcat.start();
		assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);

		httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@AfterAll
	static void tearDown() {
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

	@Test
	void initializeWithMatchingProtocolVersionHeaderSucceeds() throws Exception {
		var body = initializeBody(ProtocolVersions.MCP_2025_11_25);
		var request = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/mcp"))
			.header("Content-Type", CONTENT_TYPE)
			.header("Accept", ACCEPT_HEADER)
			.header(HttpHeaders.PROTOCOL_VERSION, ProtocolVersions.MCP_2025_11_25)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();

		var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isIn(200, 202);
	}

	@Test
	void initializeWithAbsentProtocolVersionHeaderSucceeds() throws Exception {
		var body = initializeBody(ProtocolVersions.MCP_2025_11_25);
		var request = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/mcp"))
			.header("Content-Type", CONTENT_TYPE)
			.header("Accept", ACCEPT_HEADER)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();

		var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isIn(200, 202);
	}

	@Test
	void initializeWithMismatchedProtocolVersionHeaderReturns400() throws Exception {
		var body = initializeBody(ProtocolVersions.MCP_2025_11_25);
		var request = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/mcp"))
			.header("Content-Type", CONTENT_TYPE)
			.header("Accept", ACCEPT_HEADER)
			.header(HttpHeaders.PROTOCOL_VERSION, ProtocolVersions.MCP_2024_11_05)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();

		var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
	}

	private static String initializeBody(String protocolVersion) {
		return """
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"%s","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
				"""
			.formatted(protocolVersion)
			.strip();
	}

}
