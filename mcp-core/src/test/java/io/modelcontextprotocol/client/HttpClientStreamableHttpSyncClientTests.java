/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
	void supportsExternalHttpClient() throws Exception {
		// Create an external HttpClient that we manage ourselves
		HttpClient externalHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

		// Create transport with external HttpClient - should NOT close it when transport
		// closes
		McpClientTransport transport = HttpClientStreamableHttpTransport.builder(host)
			.httpClient(externalHttpClient)
			.build();

		// Test MCP operations complete successfully with external HttpClient
		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();

			// Perform actual MCP operations to verify functionality
			var capabilities = mcpSyncClient.listTools();
			assertThat(capabilities).isNotNull();
			// Test should complete without errors - external HttpClient works normally
		});

		// Critical test: Verify external HttpClient is still functional after transport
		// closes
		// This proves the transport didn't close our external HttpClient
		HttpRequest testRequest = HttpRequest.newBuilder()
			.uri(URI.create(host + "/"))
			.timeout(Duration.ofSeconds(5))
			.build();

		HttpResponse<String> response = externalHttpClient.send(testRequest, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(404); // MCP server returns 404 for
															// root path
		// The key point is that we can still make requests - the HttpClient is functional

		// Clean up: We are responsible for closing external HttpClient
		// (In real applications, this would be done in application shutdown)
	}

	@Test
	void closesInternalHttpClientGracefully() throws Exception {
		// Create a custom onCloseClient handler to verify graceful shutdown
		AtomicBoolean closeHandlerCalled = new AtomicBoolean(false);
		AtomicReference<HttpClient> capturedHttpClient = new AtomicReference<>();
		AtomicBoolean httpClientWasFunctional = new AtomicBoolean(false);

		// Create transport with custom close handler that verifies HttpClient state
		// before cleanup
		McpClientTransport transport = HttpClientStreamableHttpTransport.builder(host).onCloseClient(httpClient -> {
			closeHandlerCalled.set(true);
			capturedHttpClient.set(httpClient);

			// Verify HttpClient is still functional before we clean it up
			try {
				HttpRequest testRequest = HttpRequest.newBuilder()
					.uri(URI.create(host + "/"))
					.timeout(Duration.ofSeconds(5))
					.build();
				HttpResponse<String> response = httpClient.send(testRequest, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 404) { // MCP server returns 404 for root
													// path
					httpClientWasFunctional.set(true);
				}
			}
			catch (Exception e) {
				throw new RuntimeException("HttpClient should be functional before cleanup", e);
			}

			// Here we could perform custom cleanup logic
			// For example: close connection pools, shutdown executors, etc.
		}).build();

		// Test MCP operations and graceful shutdown
		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();

			// Perform MCP operations to ensure transport works normally
			var capabilities = mcpSyncClient.listTools();
			assertThat(capabilities).isNotNull();

			// Test should complete and close gracefully - custom close handler will be
			// invoked
		});

		// Verify graceful shutdown behavior
		assertThat(closeHandlerCalled.get()).isTrue();
		assertThat(capturedHttpClient.get()).isNotNull();
		assertThat(httpClientWasFunctional.get()).isTrue();

		// At this point, the custom close handler has been called and
		// the HttpClient has been properly cleaned up according to our custom logic
	}

}
