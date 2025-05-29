/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.McpTransportDetector;
import io.modelcontextprotocol.client.transport.StreamableHttpClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Tests for the {@link McpAsyncClient} with auto-detected transport.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class StreamableHttpMcpAsyncClientTests extends AbstractMcpAsyncClientTests {

	String host = "http://localhost:3004";

	// Uses the https://github.com/tzolov/mcp-everything-server-docker-image
	@SuppressWarnings("resource")
	GenericContainer<?> container = new GenericContainer<>("mcp/everything:latest")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.withCommand("/bin/sh", "-c",
				"npm install -g @modelcontextprotocol/server-everything@latest && npx --node-options=\"--inspect\" @modelcontextprotocol/server-everything streamableHttp")
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	@Override
	protected McpClientTransport createMcpTransport() {
		return StreamableHttpClientTransport.builder(host).build();
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