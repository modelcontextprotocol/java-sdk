/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for resource subscription logic in {@link McpAsyncServer}. Uses
 * {@link MockMcpServerTransportProvider} to drive sessions directly without a real
 * network stack.
 */
class ResourceSubscriptionTests {

	private static final String RESOURCE_URI = "test://resource/1";

	private static final McpSchema.Implementation SERVER_INFO = McpSchema.Implementation.builder("test-server", "1.0.0")
		.build();

	private static final McpSchema.Implementation CLIENT_INFO = McpSchema.Implementation.builder("test-client", "1.0.0")
		.build();

	private static McpAsyncServer buildServer(MockMcpServerTransportProvider transportProvider) {
		return McpServer.async(transportProvider)
			.serverInfo(SERVER_INFO)
			.capabilities(McpSchema.ServerCapabilities.builder().resources(true, false).build())
			.build();
	}

	private static McpSchema.JSONRPCRequest initRequest() {
		return new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, UUID.randomUUID().toString(),
				McpSchema.InitializeRequest
					.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().build(),
							CLIENT_INFO)
					.build());
	}

	private static McpSchema.JSONRPCNotification initializedNotification() {
		return new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED);
	}

	private static McpSchema.JSONRPCRequest subscribeRequest(String uri) {
		return new McpSchema.JSONRPCRequest(McpSchema.METHOD_RESOURCES_SUBSCRIBE, UUID.randomUUID().toString(),
				McpSchema.SubscribeRequest.builder(uri).build());
	}

	private static McpSchema.JSONRPCRequest unsubscribeRequest(String uri) {
		return new McpSchema.JSONRPCRequest(McpSchema.METHOD_RESOURCES_UNSUBSCRIBE, UUID.randomUUID().toString(),
				McpSchema.UnsubscribeRequest.builder(uri).build());
	}

	private static McpAsyncServer buildServerWithResource(MockMcpServerTransportProvider transportProvider) {
		McpSchema.Resource resource = McpSchema.Resource.builder(RESOURCE_URI, "Test Resource")
			.mimeType("text/plain")
			.build();
		return McpServer.async(transportProvider)
			.serverInfo(SERVER_INFO)
			.capabilities(McpSchema.ServerCapabilities.builder().resources(true, false).build())
			.resources(new McpServerFeatures.AsyncResourceSpecification(resource,
					(exchange, request) -> Mono.just(new McpSchema.ReadResourceResult(List.of()))))
			.build();
	}

	private static McpAsyncServer buildServerWithResourceTemplate(MockMcpServerTransportProvider transportProvider) {
		McpSchema.ResourceTemplate resourceTemplate = McpSchema.ResourceTemplate
			.builder("test://resource/{id}", "Test Resource Template")
			.mimeType("text/plain")
			.build();
		return McpServer.async(transportProvider)
			.serverInfo(SERVER_INFO)
			.capabilities(McpSchema.ServerCapabilities.builder().resources(true, false).build())
			.resourceTemplates(new McpServerFeatures.AsyncResourceTemplateSpecification(resourceTemplate,
					(exchange, request) -> Mono.just(new McpSchema.ReadResourceResult(List.of()))))
			.build();
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

		McpSchema.JSONRPCMessage sent = transport.getLastSentMessage();
		assertThat(sent).isInstanceOf(McpSchema.JSONRPCNotification.class);
		McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) sent;
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

	@Test
	void subscribeToRegisteredUri_succeeds() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServerWithResource(transportProvider);

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transport.clearSentMessages();
		transportProvider.simulateIncomingMessage(subscribeRequest(RESOURCE_URI));

		McpSchema.JSONRPCMessage sent = transport.getLastSentMessage();
		assertThat(sent).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sent;
		assertThat(response.error()).as("subscribing to a registered resource must succeed").isNull();
		assertThat(response.result()).isEqualTo(Map.of());

		server.closeGracefully().block();
	}

	@Test
	void subscribeToUnregisteredUri_returnsResourceNotFound() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServerWithResource(transportProvider);

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transport.clearSentMessages();
		transportProvider.simulateIncomingMessage(subscribeRequest("test://unknown/uri"));

		McpSchema.JSONRPCMessage sent = transport.getLastSentMessage();
		assertThat(sent).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sent;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND);

		server.closeGracefully().block();
	}

	@Test
	void subscribeBeyondPerSessionLimit_isRejected() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServerWithResourceTemplate(transportProvider);

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transport.clearSentMessages();

		for (int i = 0; i < 1024; i++) {
			transportProvider.simulateIncomingMessage(subscribeRequest("test://resource/" + i));
		}

		transportProvider.simulateIncomingMessage(subscribeRequest("test://resource/1024"));

		McpSchema.JSONRPCMessage sent = transport.getLastSentMessage();
		assertThat(sent).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sent;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);

		server.closeGracefully().block();
	}

}
