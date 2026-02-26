/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for resource subscribe/unsubscribe support in {@link McpAsyncServer}.
 *
 * Covers the handlers registered under {@code resources/subscribe} and
 * {@code resources/unsubscribe}, the capability guard that keeps them absent when
 * {@code subscribe=false}, and the {@code notifications/resources/updated} notification
 * emitted by {@link McpAsyncServer#addResource} when a subscribed URI is (re-)added.
 */
class ResourceSubscriptionTests {

	private static final String RESOURCE_URI = "test://resource/item";

	private MockMcpServerTransport mockTransport;

	private MockMcpServerTransportProvider mockTransportProvider;

	private McpAsyncServer mcpAsyncServer;

	@BeforeEach
	void setUp() {
		mockTransport = new MockMcpServerTransport();
		mockTransportProvider = new MockMcpServerTransportProvider(mockTransport);
	}

	@AfterEach
	void tearDown() {
		if (mcpAsyncServer != null) {
			assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10)))
				.doesNotThrowAnyException();
		}
	}

	// -------------------------------------------------------------------------
	// Capability guard
	// -------------------------------------------------------------------------

	@Test
	void subscribeHandlerNotRegisteredWhenSubscribeCapabilityDisabled() {
		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(false, false).build())
			.build();

		initializeSession();
		mockTransportProvider.simulateIncomingMessage(subscribeRequest("req-1", RESOURCE_URI));

		McpSchema.JSONRPCMessage response = mockTransport.getLastSentMessage();
		assertThat(response).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse jsonResponse = (McpSchema.JSONRPCResponse) response;
		assertThat(jsonResponse.error()).isNotNull();
		assertThat(jsonResponse.error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
	}

	// -------------------------------------------------------------------------
	// Subscribe / unsubscribe happy paths
	// -------------------------------------------------------------------------

	@Test
	void subscribeRequestReturnsEmptyResult() {
		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		initializeSession();
		mockTransportProvider.simulateIncomingMessage(subscribeRequest("req-1", RESOURCE_URI));

		McpSchema.JSONRPCMessage response = mockTransport.getLastSentMessage();
		assertThat(response).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse jsonResponse = (McpSchema.JSONRPCResponse) response;
		assertThat(jsonResponse.id()).isEqualTo("req-1");
		assertThat(jsonResponse.error()).isNull();
	}

	// -------------------------------------------------------------------------
	// addResource notification behaviour
	// -------------------------------------------------------------------------

	@Test
	void addSubscribedResourceSendsResourcesUpdatedNotification() {
		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		initializeSession();
		// Client subscribes first
		mockTransportProvider.simulateIncomingMessage(subscribeRequest("req-1", RESOURCE_URI));
		mockTransport.clearSentMessages();

		mcpAsyncServer.addResource(resourceSpec(RESOURCE_URI)).block(Duration.ofSeconds(5));

		List<McpSchema.JSONRPCMessage> sent = mockTransport.getAllSentMessages();
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0)).isInstanceOf(McpSchema.JSONRPCNotification.class);
		McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) sent.get(0);
		assertThat(notification.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED);
	}

	@Test
	void addSubscribedResourceWithListChangedSendsBothNotifications() {
		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, true).build())
			.build();

		initializeSession();
		mockTransportProvider.simulateIncomingMessage(subscribeRequest("req-1", RESOURCE_URI));
		mockTransport.clearSentMessages();

		mcpAsyncServer.addResource(resourceSpec(RESOURCE_URI)).block(Duration.ofSeconds(5));

		List<McpSchema.JSONRPCMessage> sent = mockTransport.getAllSentMessages();
		assertThat(sent).hasSize(2);

		List<String> methods = sent.stream()
			.filter(m -> m instanceof McpSchema.JSONRPCNotification)
			.map(m -> ((McpSchema.JSONRPCNotification) m).method())
			.toList();
		assertThat(methods).containsExactly(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
				McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED);
	}

	@Test
	void addUnsubscribedResourceDoesNotSendResourcesUpdatedNotification() {
		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, true).build())
			.build();

		initializeSession();
		// No subscribe call — resource URI is not in the subscription map
		mcpAsyncServer.addResource(resourceSpec(RESOURCE_URI)).block(Duration.ofSeconds(5));

		List<McpSchema.JSONRPCMessage> sent = mockTransport.getAllSentMessages();
		assertThat(sent).hasSize(1);
		McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) sent.get(0);
		assertThat(notification.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED);
	}

	@Test
	void addResourceAfterUnsubscribeDoesNotSendResourcesUpdatedNotification() {
		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		initializeSession();
		mockTransportProvider.simulateIncomingMessage(subscribeRequest("req-1", RESOURCE_URI));
		mockTransportProvider.simulateIncomingMessage(unsubscribeRequest("req-2", RESOURCE_URI));
		mockTransport.clearSentMessages();

		mcpAsyncServer.addResource(resourceSpec(RESOURCE_URI)).block(Duration.ofSeconds(5));

		// No notifications expected: not subscribed, listChanged=false
		assertThat(mockTransport.getAllSentMessages()).isEmpty();
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	/**
	 * Performs the MCP initialization handshake so that the session's exchangeSink is
	 * populated and subsequent request handlers can be invoked.
	 */
	private void initializeSession() {
		mockTransportProvider.simulateIncomingMessage(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_INITIALIZE, "init-req", new McpSchema.InitializeRequest(
						ProtocolVersions.MCP_2025_11_25, null, new McpSchema.Implementation("test-client", "1.0.0"))));

		mockTransportProvider.simulateIncomingMessage(new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_INITIALIZED, null));

		mockTransport.clearSentMessages();
	}

	private static McpSchema.JSONRPCRequest subscribeRequest(String id, String uri) {
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_RESOURCES_SUBSCRIBE, id,
				new McpSchema.SubscribeRequest(uri));
	}

	private static McpSchema.JSONRPCRequest unsubscribeRequest(String id, String uri) {
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_RESOURCES_UNSUBSCRIBE, id,
				new McpSchema.SubscribeRequest(uri));
	}

	private static McpServerFeatures.AsyncResourceSpecification resourceSpec(String uri) {
		McpSchema.Resource resource = McpSchema.Resource.builder().uri(uri).name("test-resource").build();
		return new McpServerFeatures.AsyncResourceSpecification(resource,
				(exchange, req) -> Mono.just(new ReadResourceResult(List.of())));
	}

}
