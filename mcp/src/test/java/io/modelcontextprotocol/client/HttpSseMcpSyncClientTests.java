/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransportProvider;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class HttpSseMcpSyncClientTests extends AbstractMcpSyncClientTests {

	String host = "http://localhost:3003";

	// Uses the https://github.com/tzolov/mcp-everything-server-docker-image
	@SuppressWarnings("resource")
	GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v1")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	@Override
	protected McpClientTransportProvider createMcpClientTransportProvider() {
		return HttpClientSseClientTransportProvider.builder(host).build();
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

}
