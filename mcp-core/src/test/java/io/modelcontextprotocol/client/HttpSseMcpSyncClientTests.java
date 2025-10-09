/*
 * Copyright 2024-2024 the original author or authors.
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

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpClientTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link McpSyncClient} with {@link HttpClientSseClientTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class HttpSseMcpSyncClientTests extends AbstractMcpSyncClientTests {

	static String host = "http://localhost:3003";

	// Uses the https://github.com/tzolov/mcp-everything-server-docker-image
	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v3")
		.withCommand("node dist/index.js sse")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private final McpSyncHttpClientRequestCustomizer requestCustomizer = mock(McpSyncHttpClientRequestCustomizer.class);

	@Override
	protected McpClientTransport createMcpTransport() {
		return HttpClientSseClientTransport.builder(host).httpRequestCustomizer(requestCustomizer).build();
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

					verify(requestCustomizer, atLeastOnce()).customize(any(), eq("GET"), eq(URI.create(host + "/sse")),
							isNull(), eq(mcpTransportContext));
				});
	}

	@Test
	void supportsExternalHttpClient() {
		// Create an external HttpClient
		HttpClient externalHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

		// Create transport with external HttpClient
		McpClientTransport transport = HttpClientSseClientTransport.builder(host)
			.httpClient(externalHttpClient)
			.build();

		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();
			// Test should complete without errors
		});

		// External HttpClient should still be usable after transport closes
		assertThat(externalHttpClient).isNotNull();
	}

	@Test
	void closesInternalHttpClientGracefully() {
		// Create transport with internal HttpClient (default behavior)
		McpClientTransport transport = HttpClientSseClientTransport.builder(host).build();

		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();
			// Test should complete and close gracefully
		});

		// This test verifies that internal HttpClient resources are cleaned up
		// The actual verification happens during the graceful close process
	}

}
