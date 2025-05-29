/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import io.modelcontextprotocol.client.transport.FlowSseClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating MCP client transports based on server capabilities. Supports
 * automatic detection of server transport type (Streamable HTTP or HTTP+SSE).
 */
public class McpTransportDetector {

	private static final Logger logger = LoggerFactory.getLogger(McpTransportDetector.class);

	/**
	 * Supported protocol versions.
	 */
	private List<String> protocolVersions = List.of(McpSchema.LATEST_PROTOCOL_VERSION);

	/**
	 * Creates an appropriate MCP client transport for the given server URL. Automatically
	 * detects if the server supports Streamable HTTP or the older HTTP+SSE transport.
	 * @param serverUrl The URL of the MCP server
	 * @return A suitable McpClientTransport implementation
	 * @throws IOException If an error occurs during transport detection
	 */
	public static McpClientTransport createTransport(String serverUrl) throws IOException {
		if (detectStreamableHttpSupport(serverUrl)) {
			logger.debug("Server supports Streamable HTTP transport");
			return StreamableHttpClientTransport.builder(serverUrl).build();
		}
		else {
			logger.debug("Server supports older HTTP+SSE transport");
			return HttpClientSseClientTransport.builder(serverUrl + "/").build();
		}
	}

	/**
	 * Detects if the server supports the Streamable HTTP transport or the older HTTP+SSE
	 * transport.
	 * @param serverUrl The URL of the MCP server
	 * @return true if the server supports Streamable HTTP, false if it supports the older
	 * HTTP+SSE transport
	 * @throws IOException If an error occurs during transport detection
	 */
	private static boolean detectStreamableHttpSupport(String serverUrl) throws IOException {
		HttpClient httpClient = HttpClient.newHttpClient();
		FlowSseClient sseClient = new FlowSseClient(httpClient);

		// Create an initialize request to test with
		String initializeRequest = """
				{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}},"id":1}
				""";

		try {
			// Try POST to the server URL (Streamable HTTP)
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(serverUrl))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(initializeRequest))
				.build();

			HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
			// If successful, it's a Streamable HTTP server
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				return true;
			}
			else { // Must be the old HTTP+SSE transport
				return false;
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted during transport detection", e);
		}
	}

}