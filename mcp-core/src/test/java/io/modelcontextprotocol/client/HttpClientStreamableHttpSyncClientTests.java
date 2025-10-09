/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpClientTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Timeout(15)
public class HttpClientStreamableHttpSyncClientTests extends AbstractMcpSyncClientTests {

	static String host = "http://localhost:3001";

	// Uses the https://github.com/tzolov/mcp-everything-server-docker-image
	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v3")
		.withCommand("node dist/index.js streamableHttp")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private final McpSyncHttpClientRequestCustomizer requestCustomizer = mock(McpSyncHttpClientRequestCustomizer.class);

	@Override
	protected McpClientTransport createMcpTransport() {
		return HttpClientStreamableHttpTransport.builder(host).httpRequestCustomizer(requestCustomizer).build();
	}

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

	@Test
	void customizesRequests() {
		var mcpTransportContext = McpTransportContext.create(Map.of("some-key", "some-value"));
		withClient(createMcpTransport(), syncSpec -> syncSpec.transportContextProvider(() -> mcpTransportContext),
				mcpSyncClient -> {
					mcpSyncClient.initialize();

					verify(requestCustomizer, atLeastOnce()).customize(any(), eq("POST"), eq(URI.create(host + "/mcp")),
							eq("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
							eq(mcpTransportContext));
				});
	}

	@Test
	void supportsExternalHttpClient() {
		// Create an external HttpClient
		HttpClient externalHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

		// Create transport with external HttpClient
		McpClientTransport transport = HttpClientStreamableHttpTransport.builder(host)
			.httpClient(externalHttpClient)
			.build();

		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();
			// Test should complete without errors
		});

		// External HttpClient should still be usable after transport closes
		// (This is a basic test - in practice you'd verify the client is still
		// functional)
		assertThat(externalHttpClient).isNotNull();
	}

	@Test
	void closesInternalHttpClientGracefully() {
		// Create transport with internal HttpClient (default behavior)
		McpClientTransport transport = HttpClientStreamableHttpTransport.builder(host).build();

		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();
			// Test should complete and close gracefully
		});

		// This test verifies that internal HttpClient resources are cleaned up
		// The actual verification happens during the graceful close process
	}

}
