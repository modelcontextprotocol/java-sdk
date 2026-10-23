/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsAcknowledgedParams;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesPage;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.PERMISSIVE_VALIDATOR;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.emptyTools;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionsFeatureTests {

	@Test
	void ackIsFirstAndReflectsHonouredSubset() {
		McpChangeFeed feed = new McpChangeFeed();
		McpServer server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ToolsFeature.ofAsync(emptyTools(), McpJsonDefaults.getMapper(), PERMISSIVE_VALIDATOR, 0L,
					CacheScope.PRIVATE))
			.subscriptions(feed)
			.build();

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", Map.of("toolsListChanged", true, "promptsListChanged", true));
		JSONRPCRequest request = new JSONRPCRequest("subscriptions/listen", 7, params);

		var invocation = (McpTransportResponse.Streaming) server.handle(McpTransportContext.EMPTY, request).block();

		// The feed has no buffered replay, so broadcast only after the listen stream has
		// actually subscribed - otherwise the change is dropped before anyone is
		// listening, same as a real client connecting after a change already fired.
		StepVerifier.create(invocation.messages()).assertNext(msg -> {
			JSONRPCNotification ack = (JSONRPCNotification) msg;
			assertThat(ack.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED);
			SubscriptionFilter notifications = ((SubscriptionsAcknowledgedParams) ack.params()).notifications();
			assertThat(notifications.toolsListChanged()).isTrue();
			assertThat(notifications.promptsListChanged()).isNull();
		})
			.then(() -> feed.broadcast(new ServerChange.ToolsListChanged()))
			.assertNext(msg -> assertThat(((JSONRPCNotification) msg).method())
				.isEqualTo(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED))
			.then(() -> feed.broadcast(new ServerChange.PromptsListChanged()))
			.then(server::closeGracefully)
			.assertNext(msg -> assertThat(msg).isInstanceOf(JSONRPCResponse.class))
			.verifyComplete();
	}

	@Test
	void unrequestedTypeIsNeverEmitted() {
		McpChangeFeed feed = new McpChangeFeed();
		McpServer server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ToolsFeature.ofAsync(emptyTools(), McpJsonDefaults.getMapper(), PERMISSIVE_VALIDATOR, 0L,
					CacheScope.PRIVATE))
			.subscriptions(feed)
			.build();

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", Map.of("toolsListChanged", true));
		JSONRPCRequest request = new JSONRPCRequest("subscriptions/listen", 1, params);

		var invocation = (McpTransportResponse.Streaming) server.handle(McpTransportContext.EMPTY, request).block();

		StepVerifier.create(invocation.messages())
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED))
			.then(() -> feed.broadcast(new ServerChange.PromptsListChanged())) // not
																				// requested
			.then(() -> feed.broadcast(new ServerChange.ToolsListChanged()))
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED))
			.then(server::closeGracefully)
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.verifyComplete();
	}

	@Test
	void subscriptionIdMatchesRequestId() {
		McpChangeFeed feed = new McpChangeFeed();
		McpServer server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ToolsFeature.ofAsync(emptyTools(), McpJsonDefaults.getMapper(), PERMISSIVE_VALIDATOR, 0L,
					CacheScope.PRIVATE))
			.subscriptions(feed)
			.build();

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", Map.of("toolsListChanged", true));
		JSONRPCRequest request = new JSONRPCRequest("subscriptions/listen", 42, params);

		var invocation = (McpTransportResponse.Streaming) server.handle(McpTransportContext.EMPTY, request).block();
		server.closeGracefully();

		StepVerifier.create(invocation.messages()).assertNext(msg -> {
			JSONRPCNotification ack = (JSONRPCNotification) msg;
			assertThat(subscriptionIdOf(ack)).isEqualTo(42);
		}).assertNext(msg -> assertThat(msg).isInstanceOf(JSONRPCResponse.class)).verifyComplete();
	}

	@Test
	void changeEmittedWithNoListenerIsDropped() {
		McpChangeFeed feed = new McpChangeFeed();
		McpServer server = toolsServer(feed);

		feed.broadcast(new ServerChange.ToolsListChanged());

		StepVerifier.create(listen(server, 1).messages())
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED))
			.then(server::closeGracefully)
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.verifyComplete();
	}

	@Test
	void listenAfterEarlierListenEndedReceivesChanges() {
		McpChangeFeed feed = new McpChangeFeed();
		McpServer server = toolsServer(feed);

		// Receiving a change proves the first stream subscribed to the feed before it
		// goes away.
		StepVerifier.create(listen(server, 1).messages())
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED))
			.then(() -> feed.broadcast(new ServerChange.ToolsListChanged()))
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED))
			.thenCancel()
			.verify();

		StepVerifier.create(listen(server, 2).messages())
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED))
			.then(() -> feed.broadcast(new ServerChange.ToolsListChanged()))
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED))
			.then(server::closeGracefully)
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.verifyComplete();
	}

	@Test
	void resourceSubscribeIsAdvertisedAndHonouredWhenRepositorySupportsIt() {
		McpServer server = resourcesServer(true, new McpChangeFeed());

		assertThat(resourcesCapability(server)).containsEntry("subscribe", true).containsEntry("listChanged", true);
		StepVerifier.create(listenToResource(server).messages())
			.assertNext(msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg).resourceSubscriptions())
				.containsExactly("test://a"))
			.then(server::closeGracefully)
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.verifyComplete();
	}

	@Test
	void resourceSubscribeIsNeitherAdvertisedNorHonouredByDefault() {
		McpServer server = resourcesServer(false, new McpChangeFeed());

		assertThat(resourcesCapability(server)).containsEntry("subscribe", false).containsEntry("listChanged", true);
		StepVerifier.create(listenToResource(server).messages())
			.assertNext(msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg).resourceSubscriptions())
				.isNull())
			.then(server::closeGracefully)
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.verifyComplete();
	}

	@Test
	void resourceSubscribeIsNotAdvertisedWithoutSubscriptions() {
		McpServer server = resourcesServer(true, null);

		assertThat(resourcesCapability(server)).containsEntry("subscribe", false);
	}

	@Test
	void listenWithoutNotificationsIsRejectedAsInvalidParams() {
		McpServer server = toolsServer(new McpChangeFeed());
		JSONRPCRequest request = new JSONRPCRequest("subscriptions/listen", 1, Map.of("_meta", meta()));

		assertThat(server.handle(McpTransportContext.EMPTY, request).block()).isInstanceOfSatisfying(
				McpTransportResponse.Error.class,
				error -> assertThat(error.response().error().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS));
	}

	@Test
	void listenWithNullNotificationsIsRejectedAsInvalidParams() {
		McpServer server = toolsServer(new McpChangeFeed());
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", null);
		JSONRPCRequest request = new JSONRPCRequest("subscriptions/listen", 1, params);

		assertThat(server.handle(McpTransportContext.EMPTY, request).block()).isInstanceOfSatisfying(
				McpTransportResponse.Error.class,
				error -> assertThat(error.response().error().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS));
	}

	@Test
	void listenWithEmptyNotificationsIsAccepted() {
		McpServer server = toolsServer(new McpChangeFeed());
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", Map.of());
		JSONRPCRequest request = new JSONRPCRequest("subscriptions/listen", 1, params);

		assertThat(server.handle(McpTransportContext.EMPTY, request).block())
			.isInstanceOf(McpTransportResponse.Streaming.class);
	}

	private static McpServer resourcesServer(boolean supportsSubscribe, McpChangePublisher publisher) {
		McpAsyncResourceRepository repository = new McpAsyncResourceRepository() {
			@Override
			public Mono<ResourcesPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ResourcesPage.of(List.of()));
			}

			@Override
			public Mono<McpAsyncResponse<ReadResourceOutcome>> read(McpRequestContext ctx,
					ReadResourceRequest request) {
				return Mono.empty();
			}

			@Override
			public boolean supportsSubscribe() {
				return supportsSubscribe;
			}
		};
		McpServer.Builder builder = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ResourcesFeature.ofAsync(repository, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE));
		if (publisher != null) {
			builder.subscriptions(publisher);
		}
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> resourcesCapability(McpServer server) {
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_SERVER_DISCOVER, 1, Map.of("_meta", meta()));
		Map<String, Object> result = (Map<String, Object>) respond(server, request).block().result();
		Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
		return (Map<String, Object>) capabilities.get("resources");
	}

	private static McpTransportResponse.Streaming listenToResource(McpServer server) {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", Map.of("resourceSubscriptions", List.of("test://a")));
		JSONRPCRequest request = new JSONRPCRequest("subscriptions/listen", 1, params);
		return (McpTransportResponse.Streaming) server.handle(McpTransportContext.EMPTY, request).block();
	}

	private static SubscriptionFilter acknowledgedNotifications(JSONRPCNotification ack) {
		assertThat(ack.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED);
		return ((SubscriptionsAcknowledgedParams) ack.params()).notifications();
	}

	private static McpServer toolsServer(McpChangePublisher publisher) {
		return McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ToolsFeature.ofAsync(emptyTools(), McpJsonDefaults.getMapper(), PERMISSIVE_VALIDATOR, 0L,
					CacheScope.PRIVATE))
			.subscriptions(publisher)
			.build();
	}

	private static McpTransportResponse.Streaming listen(McpServer server, int id) {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", Map.of("toolsListChanged", true));
		JSONRPCRequest request = new JSONRPCRequest("subscriptions/listen", id, params);
		return (McpTransportResponse.Streaming) server.handle(McpTransportContext.EMPTY, request).block();
	}

	private static Object subscriptionIdOf(JSONRPCNotification notification) {
		return ((SubscriptionsAcknowledgedParams) notification.params()).meta().get(MetaKeys.SUBSCRIPTION_ID);
	}

}
