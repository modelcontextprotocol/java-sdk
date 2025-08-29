/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Tests for the {@link WebSocketClientTransport} class.
 *
 * @author Aliaksei Darafeyeu
 */
class WebSocketClientTransportTest {

	private static GenericContainer<?> wsContainer;

	private static URI websocketUri;

	private WebSocketClientTransport transport;

	@BeforeAll
	static void startContainer() {
		wsContainer = new GenericContainer<>(
				new ImageFromDockerfile().withFileFromClasspath("server.js", "ws/server.js")
					.withFileFromClasspath("Dockerfile", "ws/Dockerfile"))
			.withExposedPorts(8080);

		wsContainer.start();

		int port = wsContainer.getMappedPort(8080);
		websocketUri = URI.create("ws://localhost:" + port);
	}

	@BeforeEach
	public void setUp() {
		transport = WebSocketClientTransport.builder(websocketUri).build();
	}

	@AfterAll
	static void tearDown() {
		wsContainer.stop();
	}

	@Test
	void testConnectSuccessfully() {
		// Try to connect to the WebSocket server
		Mono<Void> connection = transport.connect(message -> Mono.empty());

		// Wait for the connection to complete
		StepVerifier.create(connection).expectComplete().verify();

		// Ensure that connection is established
		assertEquals(WebSocketClientTransport.TransportState.CONNECTED, transport.getState());
	}

	@Test
	void testSendMessage() {
		// Connect to the server
		Mono<Void> connection = transport.connect(message -> Mono.empty());

		// Ensure connection is successful
		StepVerifier.create(connection).expectComplete().verify();

		// Create a simple message to send
		var messageRequest = new McpSchema.CreateMessageRequest(
				List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message"))),
				null, null, null, null, 0, null, null);
		McpSchema.JSONRPCMessage message = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, "test-id", messageRequest);

		// Send a message to the server
		Mono<Void> sendMessage = transport.sendMessage(message);

		// Ensure message is sent successfully
		StepVerifier.create(sendMessage).expectComplete().verify();
	}

	@Test
	void testCloseConnectionGracefully() {
		Mono<Void> connection = transport.connect(message -> Mono.empty());

		StepVerifier.create(connection).expectComplete().verify();

		// Close the connection gracefully
		Mono<Void> closeConnection = transport.closeGracefully();

		// Verify that the connection is closed successfully
		StepVerifier.create(closeConnection).expectComplete().verify();

		assertEquals(WebSocketClientTransport.TransportState.CLOSED, transport.getState());
	}

	@Test
	void testSendMessageAfterConnectionClosed() {
		// Send a message before connection is established
		// Create a simple message to send
		var messageRequest = new McpSchema.CreateMessageRequest(
				List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message"))),
				null, null, null, null, 0, null, null);
		McpSchema.JSONRPCMessage message = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, "test-id", messageRequest);

		Mono<Void> sendMessageBeforeConnect = transport.sendMessage(message);

		// Verify that the transport returns an error because the connection is closed
		StepVerifier.create(sendMessageBeforeConnect).expectError(IllegalStateException.class).verify();
	}

}