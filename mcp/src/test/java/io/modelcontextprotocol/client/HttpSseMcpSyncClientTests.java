/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpRequestCustomizer;
import io.modelcontextprotocol.server.McpTransportContext;
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

	String host = "http://localhost:3003";

	// Uses the https://github.com/tzolov/mcp-everything-server-docker-image
	@SuppressWarnings("resource")
	GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v2")
		.withCommand("node dist/index.js sse")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private final McpSyncHttpRequestCustomizer requestCustomizer = mock(McpSyncHttpRequestCustomizer.class);

	@Override
	protected McpClientTransport createMcpTransport() {
		return HttpClientSseClientTransport.builder(host).httpRequestCustomizer(requestCustomizer).build();
	}

	@Override
	protected void onStart() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@Override
	protected void onClose() {
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

}
