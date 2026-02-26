/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for resource subscription logic in {@link McpAsyncServer}. Uses
 * {@link MockMcpServerTransportProvider} to drive sessions directly without a real
 * network stack.
 *
 * <p>
 * Each test creates a transport with a response-interceptor latch so that
 * {@code simulateIncomingMessage} (fire-and-forget) can be reliably awaited before
 * asserting subscription state.
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

	/**
	 * Sends a request through the transport provider and blocks until the session has
	 * sent a response for that request ID, guaranteeing the handler has fully executed.
	 */
	private static void sendAndAwait(MockMcpServerTransport transport, MockMcpServerTransportProvider transportProvider,
			McpSchema.JSONRPCRequest request) throws InterruptedException {
		String requestId = request.id().toString();
		CountDownLatch latch = new CountDownLatch(1);
		transport.setInterceptorForNextResponse(requestId, latch);
		transportProvider.simulateIncomingMessage(request);
		assertThat(latch.await(5, TimeUnit.SECONDS))
			.as("server should have responded to request " + requestId + " within 5 s")
			.isTrue();
	}

	private static McpSchema.JSONRPCRequest initRequest() {
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE,
				UUID.randomUUID().toString(),
				new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_11_25, null, CLIENT_INFO));
	}

	private static McpSchema.JSONRPCNotification initializedNotification() {
		return new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_NOTIFICATION_INITIALIZED,
				null);
	}

	private static McpSchema.JSONRPCRequest subscribeRequest(String uri) {
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_RESOURCES_SUBSCRIBE,
				UUID.randomUUID().toString(), new McpSchema.SubscribeRequest(uri));
	}

	private static McpSchema.JSONRPCRequest unsubscribeRequest(String uri) {
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_RESOURCES_UNSUBSCRIBE,
				UUID.randomUUID().toString(), new McpSchema.UnsubscribeRequest(uri));
	}

	@Test
	void notifyResourcesUpdated_noSubscribers_completesEmpty() throws InterruptedException {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		sendAndAwait(transport, transportProvider, initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transport.clearSentMessages();

		StepVerifier.create(server.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(RESOURCE_URI)))
			.verifyComplete();

		assertThat(transport.getAllSentMessages()).as("no notification should be sent when nobody is subscribed")
			.isEmpty();

		server.closeGracefully().block();
	}

	@Test
	void notifyResourcesUpdated_afterSubscribe_notifiesSession() throws InterruptedException {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		sendAndAwait(transport, transportProvider, initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		sendAndAwait(transport, transportProvider, subscribeRequest(RESOURCE_URI));
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
	void notifyResourcesUpdated_differentUri_doesNotNotifySession() throws InterruptedException {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		sendAndAwait(transport, transportProvider, initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		sendAndAwait(transport, transportProvider, subscribeRequest(RESOURCE_URI));
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
	void notifyResourcesUpdated_afterUnsubscribe_doesNotNotifySession() throws InterruptedException {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		sendAndAwait(transport, transportProvider, initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		sendAndAwait(transport, transportProvider, subscribeRequest(RESOURCE_URI));
		sendAndAwait(transport, transportProvider, unsubscribeRequest(RESOURCE_URI));
		transport.clearSentMessages();

		StepVerifier.create(server.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(RESOURCE_URI)))
			.verifyComplete();

		assertThat(transport.getAllSentMessages()).as("no notification should be sent after the session unsubscribed")
			.isEmpty();

		server.closeGracefully().block();
	}

	@Test
	void notifyResourcesUpdated_afterSessionClose_doesNotNotifySession() throws InterruptedException {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		sendAndAwait(transport, transportProvider, initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		sendAndAwait(transport, transportProvider, subscribeRequest(RESOURCE_URI));

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
