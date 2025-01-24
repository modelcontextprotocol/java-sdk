/*
 * Copyright 2024-2024 the original author or authors.
 */

package org.modelcontextprotocol.client;

import org.junit.jupiter.api.Timeout;
import org.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.modelcontextprotocol.spec.ClientMcpTransport;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Tests for the {@link McpSyncClient} with {@link HttpClientSseClientTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class ServletSseMcpSyncClientTests extends AbstractMcpSyncClientTests {

	String host = "http://localhost:3003";

	// Uses the https://github.com/tzolov/mcp-everything-server-docker-image
	@SuppressWarnings("resource")
	GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v1")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	@Override
	protected ClientMcpTransport createMcpTransport() {
		return new HttpClientSseClientTransport(host);
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
