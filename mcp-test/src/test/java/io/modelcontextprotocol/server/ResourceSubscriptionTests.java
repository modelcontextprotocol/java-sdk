/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.UUID;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.spec.jsonrpc.JSONRPC;
import io.modelcontextprotocol.spec.jsonrpc.JSONRPCMessage;
import io.modelcontextprotocol.spec.jsonrpc.JSONRPCNotification;
import io.modelcontextprotocol.spec.jsonrpc.JSONRPCRequest;
import io.modelcontextprotocol.spec.schema.resource.SubscribeRequest;
import io.modelcontextprotocol.spec.schema.resource.UnsubscribeRequest;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for resource subscription logic in {@link McpAsyncServer}. Uses
 * {@link MockMcpServerTransportProvider} to drive sessions directly without a real
 * network stack.
 */
class ResourceSubscriptionTests {

	private static final String RESOURCE_URI = "test://resource/1";

	private static final McpSchema.Implementation SERVER_INFO = new McpSchema.Implementation("test-server", "1.0.0");

	private static final McpSchema.Implementation CLIENT_INFO = new McpSchema.Implementation("test-client", "1.0.0");

	private static McpAsyncServer buildServer(MockMcpServerTransportProvider transportProvider) {
		return McpServer.async(transportProvider)
			.serverInfo(SERVER_INFO)
			.capabilities(McpSchema.ServerCapabilities.builder().resources(true, false).build())
			.build();
	}

	private static JSONRPCRequest initRequest() {
		return new JSONRPCRequest(JSONRPC.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE, UUID.randomUUID().toString(),
				new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_11_25, null, CLIENT_INFO));
	}

	private static JSONRPCNotification initializedNotification() {
		return new JSONRPCNotification(JSONRPC.JSONRPC_VERSION, McpSchema.METHOD_NOTIFICATION_INITIALIZED, null);
	}

	private static JSONRPCRequest subscribeRequest(String uri) {
		return new JSONRPCRequest(JSONRPC.JSONRPC_VERSION, McpSchema.METHOD_RESOURCES_SUBSCRIBE,
				UUID.randomUUID().toString(), new SubscribeRequest(uri));
	}

	private static JSONRPCRequest unsubscribeRequest(String uri) {
		return new JSONRPCRequest(JSONRPC.JSONRPC_VERSION, McpSchema.METHOD_RESOURCES_UNSUBSCRIBE,
				UUID.randomUUID().toString(), new UnsubscribeRequest(uri));
	}

	@Test
	void notifyResourcesUpdated_noSubscribers_completesEmpty() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transport.clearSentMessages();

		StepVerifier.create(server.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(RESOURCE_URI)))
			.verifyComplete();

		assertThat(transport.getAllSentMessages()).as("no notification should be sent when nobody is subscribed")
			.isEmpty();

		server.closeGracefully().block();
	}

	@Test
	void notifyResourcesUpdated_afterSubscribe_notifiesSession() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transportProvider.simulateIncomingMessage(subscribeRequest(RESOURCE_URI));
		transport.clearSentMessages();

		StepVerifier.create(server.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(RESOURCE_URI)))
			.verifyComplete();

		JSONRPCMessage sent = transport.getLastSentMessage();
		assertThat(sent).isInstanceOf(JSONRPCNotification.class);
		JSONRPCNotification notification = (JSONRPCNotification) sent;
		assertThat(notification.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED);

		server.closeGracefully().block();
	}

	@Test
	void notifyResourcesUpdated_differentUri_doesNotNotifySession() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transportProvider.simulateIncomingMessage(subscribeRequest(RESOURCE_URI));
		transport.clearSentMessages();

		StepVerifier
			.create(server.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification("test://other/resource")))
			.verifyComplete();

		assertThat(transport.getAllSentMessages())
			.as("notification for a different URI should not reach a session subscribed to a different URI")
			.isEmpty();

		server.closeGracefully().block();
	}

	@Test
	void notifyResourcesUpdated_afterUnsubscribe_doesNotNotifySession() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transportProvider.simulateIncomingMessage(subscribeRequest(RESOURCE_URI));
		transportProvider.simulateIncomingMessage(unsubscribeRequest(RESOURCE_URI));
		transport.clearSentMessages();

		StepVerifier.create(server.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(RESOURCE_URI)))
			.verifyComplete();

		assertThat(transport.getAllSentMessages()).as("no notification should be sent after the session unsubscribed")
			.isEmpty();

		server.closeGracefully().block();
	}

	@Test
	void notifyResourcesUpdated_afterSessionClose_doesNotNotifySession() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transportProvider.simulateIncomingMessage(subscribeRequest(RESOURCE_URI));

		// Close the session; onClose must fire and remove the subscription
		transportProvider.closeGracefully().block();
		transport.clearSentMessages();

		StepVerifier.create(server.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(RESOURCE_URI)))
			.verifyComplete();

		assertThat(transport.getAllSentMessages()).as("no notification should be sent after the session has closed")
			.isEmpty();

		server.closeGracefully().block();
	}

}
