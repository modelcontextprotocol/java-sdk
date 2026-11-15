/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsAcknowledgedParams;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResource;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesPage;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.PERMISSIVE_VALIDATOR;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.emptyTools;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

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
			assertThat(notifications.wantsToolsListChanged()).isTrue();
			assertThat(notifications.get(SubscriptionFilter.PROMPTS_LIST_CHANGED)).isNull();
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
	void listenWithoutNotificationsIsRejected() {
		McpServer server = toolsServer(new McpChangeFeed());

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		McpTransportResponse response = server
			.handle(McpTransportContext.EMPTY, new JSONRPCRequest("subscriptions/listen", 1, params))
			.block();

		assertNotificationsRequired(response);
	}

	@Test
	void listenWithNullNotificationsIsRejected() {
		McpServer server = toolsServer(new McpChangeFeed());

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", null);
		McpTransportResponse response = server
			.handle(McpTransportContext.EMPTY, new JSONRPCRequest("subscriptions/listen", 1, params))
			.block();

		assertNotificationsRequired(response);
	}

	@ParameterizedTest
	@MethodSource("nonObjectNotifications")
	void listenWithNonObjectNotificationsIsRejected(Object notifications) {
		McpServer server = toolsServer(new McpChangeFeed());

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", notifications);
		McpTransportResponse response = server
			.handle(McpTransportContext.EMPTY, new JSONRPCRequest("subscriptions/listen", 1, params))
			.block();

		assertNotificationsRequired(response);
	}

	static Stream<Object> nonObjectNotifications() {
		// "" and [] matter in particular: a mapper configured with
		// ACCEPT_EMPTY_STRING_AS_NULL_OBJECT / ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT would
		// coerce them to null, which SubscriptionsListenRequest turns into an empty
		// filter.
		return Stream.of("", List.of(), true, 1);
	}

	@Test
	void emptyNotificationsIsAcknowledgedAndStaysOpen() {
		McpChangeFeed feed = new McpChangeFeed();
		McpServer server = toolsServer(feed);

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", Map.of());
		var invocation = (McpTransportResponse.Streaming) server
			.handle(McpTransportContext.EMPTY, new JSONRPCRequest("subscriptions/listen", 1, params))
			.block();

		StepVerifier.create(invocation.messages())
			.assertNext(msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg))
				.isEqualTo(SubscriptionFilter.EMPTY))
			.then(() -> feed.broadcast(new ServerChange.ToolsListChanged()))
			.then(server::closeGracefully)
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.verifyComplete();
	}

	@Test
	void resourceSubscribeIsAdvertisedAndHonouredWhenRepositorySupportsIt() {
		McpServer server = resourcesServer(true, new McpChangeFeed());

		assertThat(resourcesCapability(server)).containsEntry("subscribe", true).containsEntry("listChanged", true);
		StepVerifier.create(listenToResource(server).messages())
			.assertNext(msg -> assertThat(
					acknowledgedNotifications((JSONRPCNotification) msg).resourceSubscriptionsOrEmpty())
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
			.assertNext(msg -> assertThat(
					acknowledgedNotifications((JSONRPCNotification) msg).get(SubscriptionFilter.RESOURCE_SUBSCRIPTIONS))
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
	void changeBroadcastAsSoonAsAckIsDeliveredReachesTheStream() {
		McpChangeFeed feed = new McpChangeFeed();
		McpServer server = toolsServer(feed);

		// Broadcast a change synchronously from inside the ack's onNext, on the
		// thread delivering the ack. This is the earliest moment a test harness
		// reacting to the ack can broadcast a change, so the stream must already
		// be subscribed to the feed by the time the ack is emitted.
		Flux<JSONRPCMessage> messages = listen(server, 1).messages().doOnNext(msg -> {
			if (msg instanceof JSONRPCNotification notification
					&& notification.method().equals(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED)) {
				feed.broadcast(new ServerChange.ToolsListChanged());
			}
		});

		// A lost change never arrives: time out instead of hanging.
		StepVerifier.create(messages)
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED))
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED))
			.then(server::closeGracefully)
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.expectComplete()
			.verify(Duration.ofSeconds(5));
	}

	@Test
	void changeDeliveredBeforeAckIsSentFollowsTheAck() {
		// Delivers a change as soon as the listen stream subscribes, before the ack is
		// out.
		McpChangePublisher publisher = () -> Flux.<ServerChange>just(new ServerChange.ToolsListChanged())
			.concatWith(Flux.never());
		McpServer server = toolsServer(publisher);

		StepVerifier.create(listen(server, 1).messages())
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED))
			.expectNextMatches(msg -> ((JSONRPCNotification) msg).method()
				.equals(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED))
			.then(server::closeGracefully)
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.expectComplete()
			.verify(Duration.ofSeconds(5));
	}

	@Test
	void restrictionNarrowsResourceSubscriptionsAndOnlyThoseAreForwarded() {
		McpChangeFeed feed = new McpChangeFeed();
		McpServer server = resourcesServerBuilder(true).subscriptions(feed)
			.restrictSubscriptions((ctx, requested, honoured) -> Mono
				.just(honoured.with(SubscriptionFilter.RESOURCE_SUBSCRIPTIONS, List.of("test://mine"))))
			.build();

		var listen = (McpTransportResponse.Streaming) listenWith(server,
				Map.of("resourceSubscriptions", List.of("test://mine", "test://theirs")));

		StepVerifier.create(listen.messages())
			.assertNext(msg -> assertThat(
					acknowledgedNotifications((JSONRPCNotification) msg).resourceSubscriptionsOrEmpty())
				.containsExactly("test://mine"))
			.then(() -> {
				feed.broadcast(new ServerChange.ResourceUpdated("test://theirs"));
				feed.broadcast(new ServerChange.ResourceUpdated("test://mine"));
			})
			.assertNext(msg -> assertThat(((JSONRPCNotification) msg).params()).asInstanceOf(MAP)
				.containsEntry("uri", "test://mine"))
			.then(server::closeGracefully)
			.expectNextMatches(JSONRPCResponse.class::isInstance)
			.verifyComplete();
	}

	@Test
	void restrictionSeesTheCallerTheRequestedFilterAndWhatTheServerHonours() {
		List<Object> seen = new ArrayList<>();
		McpServer server = resourcesServerBuilder(true).subscriptions(new McpChangeFeed())
			.restrictSubscriptions((ctx, requested, honoured) -> {
				seen.add(ctx.transportContext().get("principal"));
				seen.add(requested);
				seen.add(honoured);
				return Mono.just(honoured);
			})
			.build();

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", Map.of("resourceSubscriptions", List.of("test://a"), "toolsListChanged", true));
		server
			.handle(McpTransportContext.create(Map.of("principal", "alice")),
					new JSONRPCRequest("subscriptions/listen", 1, params))
			.block();
		server.closeGracefully();

		assertThat(seen).containsExactly("alice",
				SubscriptionFilter.of(Map.of("resourceSubscriptions", List.of("test://a"), "toolsListChanged", true)),
				SubscriptionFilter.of(Map.of("resourceSubscriptions", List.of("test://a"))));
	}

	@Test
	void restrictionCannotWidenWhatTheServerHonours() {
		McpServer server = resourcesServerBuilder(true).subscriptions(new McpChangeFeed())
			.restrictSubscriptions((ctx, requested, honoured) -> Mono.just(SubscriptionFilter.of(Map.of(
					// Requested and honoured, kept.
					"resourcesListChanged", true,
					// One requested and honoured URI, kept, and one never requested.
					"resourceSubscriptions", List.of("test://a", "test://unrequested"),
					// Requested, but the server has no tools to honour it for.
					"toolsListChanged", true,
					// Never requested.
					"promptsListChanged", true, "unrequested", true))))
			.build();

		var listen = (McpTransportResponse.Streaming) listenWith(server, Map.of("resourcesListChanged", true,
				"resourceSubscriptions", List.of("test://a"), "toolsListChanged", true));

		StepVerifier.create(listen.messages())
			.assertNext(
					msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg)).isEqualTo(SubscriptionFilter
						.of(Map.of("resourcesListChanged", true, "resourceSubscriptions", List.of("test://a")))))
			.then(server::closeGracefully)
			.expectNextMatches(JSONRPCResponse.class::isInstance)
			.verifyComplete();
	}

	@Test
	void restrictionCannotRestoreWhatAFeatureNarrowed() {
		McpServer server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(new TasksExtension(List.of("t-1"), false))
			.subscriptions(new McpChangeFeed())
			.restrictSubscriptions((ctx, requested, honoured) -> Mono.just(requested))
			.build();

		var listen = (McpTransportResponse.Streaming) listenForTasks(server, Map.of("taskIds", List.of("t-1", "t-2")));

		StepVerifier.create(listen.messages())
			.assertNext(msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg))
				.isEqualTo(SubscriptionFilter.of(Map.of("taskIds", List.of("t-1")))))
			.then(server::closeGracefully)
			.expectNextMatches(JSONRPCResponse.class::isInstance)
			.verifyComplete();
	}

	@Test
	void restrictionCompletingEmptyHonoursNothing() {
		McpServer server = resourcesServerBuilder(true).subscriptions(new McpChangeFeed())
			.restrictSubscriptions((ctx, requested, honoured) -> Mono.empty())
			.build();

		var listen = (McpTransportResponse.Streaming) listenWith(server,
				Map.of("resourcesListChanged", true, "resourceSubscriptions", List.of("test://a")));

		StepVerifier.create(listen.messages())
			.assertNext(msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg))
				.isEqualTo(SubscriptionFilter.EMPTY))
			.then(server::closeGracefully)
			.expectNextMatches(JSONRPCResponse.class::isInstance)
			.verifyComplete();
	}

	@Test
	void restrictionIsNotCalledWhenNothingIsHonoured() {
		McpServer server = resourcesServerBuilder(true).subscriptions(new McpChangeFeed())
			.restrictSubscriptions((ctx, requested, honoured) -> {
				throw new AssertionError("restriction called for " + honoured);
			})
			.build();

		// The server has no tools to honour toolsListChanged for.
		var listen = (McpTransportResponse.Streaming) listenWith(server, Map.of("toolsListChanged", true));

		StepVerifier.create(listen.messages())
			.assertNext(msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg))
				.isEqualTo(SubscriptionFilter.EMPTY))
			.then(server::closeGracefully)
			.expectNextMatches(JSONRPCResponse.class::isInstance)
			.verifyComplete();
	}

	static Stream<SubscriptionRestriction> rejectingRestrictions() {
		return Stream.of((ctx, requested, honoured) -> Mono.error(McpException.invalidParams("not permitted")),
				(ctx, requested, honoured) -> {
					throw McpException.invalidParams("not permitted");
				});
	}

	@ParameterizedTest
	@MethodSource("rejectingRestrictions")
	void restrictionRejectingTheFilterAnswersBeforeAnyStreamOpens(SubscriptionRestriction restriction) {
		McpServer server = resourcesServerBuilder(true).subscriptions(new McpChangeFeed())
			.restrictSubscriptions(restriction)
			.build();

		McpTransportResponse response = listenWith(server, Map.of("resourceSubscriptions", List.of("test://a")));

		assertThat(response).asInstanceOf(type(McpTransportResponse.Error.class))
			.extracting(error -> error.response().error())
			.returns(ErrorCodes.INVALID_PARAMS, JSONRPCError::code)
			.returns("not permitted", JSONRPCError::message);
	}

	private static McpTransportResponse listenWith(McpServer server, Map<String, Object> filter) {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", filter);
		return server.handle(McpTransportContext.EMPTY, new JSONRPCRequest("subscriptions/listen", 1, params)).block();
	}

	private static McpServer resourcesServer(boolean supportsSubscribe, McpChangePublisher publisher) {
		McpServer.Builder builder = resourcesServerBuilder(supportsSubscribe);
		if (publisher != null) {
			builder.subscriptions(publisher);
		}
		return builder.build();
	}

	private static McpServer.Builder resourcesServerBuilder(boolean supportsSubscribe) {
		McpAsyncResourceRepository repository = new McpAsyncResourceRepository() {
			@Override
			public Mono<ResourcesPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ResourcesPage.of(List.of()));
			}

			@Override
			public Mono<McpAsyncResource> find(McpRequestContext ctx, String uri) {
				return Mono.empty();
			}

			@Override
			public boolean supportsSubscribe() {
				return supportsSubscribe;
			}
		};
		return McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ResourcesFeature.ofAsync(repository, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE));
	}

	@SuppressWarnings("unchecked")
	/**
	 * An extension in the shape of Tasks: it honours the task ids it knows, rejects a
	 * filter that asks for task ids the client may not receive, and publishes
	 * {@link TaskChanged}.
	 */
	private static final class TasksExtension implements McpFeature {

		private final List<String> known;

		private final boolean reject;

		TasksExtension(List<String> known, boolean reject) {
			this.known = known;
			this.reject = reject;
		}

		@Override
		public Set<String> methods() {
			return Set.of();
		}

		@Override
		public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
			return Mono.error(new IllegalStateException("serves no method"));
		}

		@Override
		public SubscriptionFilter honouredSubscriptions(McpRequestContext ctx, SubscriptionFilter requested) {
			if (!(requested.get("taskIds") instanceof List<?> ids)) {
				return SubscriptionFilter.EMPTY;
			}
			if (this.reject) {
				throw McpException.missingClientCapability(ClientCapabilities.builder()
					.extensions(Map.of("io.modelcontextprotocol/tasks", Map.of()))
					.build());
			}
			return SubscriptionFilter.EMPTY.with("taskIds", ids.stream().filter(this.known::contains).toList());
		}

	}

	private record TaskChanged(String taskId) implements ServerChange {

		@Override
		public String method() {
			return "notifications/tasks";
		}

		@Override
		public Object params() {
			return Map.of("taskId", this.taskId, "_meta", Map.of("example/trace", "x"));
		}

		@Override
		public boolean isHonouredBy(SubscriptionFilter honoured) {
			return honoured.get("taskIds") instanceof List<?> ids && ids.contains(this.taskId);
		}

	}

	private static McpTransportResponse listenForTasks(McpServer server, Map<String, Object> filter) {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("notifications", filter);
		return server.handle(McpTransportContext.EMPTY, new JSONRPCRequest("subscriptions/listen", 3, params)).block();
	}

	private static McpServer tasksServer(McpChangePublisher publisher, McpFeature tasks) {
		return McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ToolsFeature.ofAsync(emptyTools(), McpJsonDefaults.getMapper(), PERMISSIVE_VALIDATOR, 0L,
					CacheScope.PRIVATE))
			.feature(tasks)
			.subscriptions(publisher)
			.build();
	}

	@Test
	void extensionFieldNoFeatureHonoursIsLeftOutOfTheAcknowledgment() {
		McpServer server = toolsServer(new McpChangeFeed());

		var listen = (McpTransportResponse.Streaming) listenForTasks(server,
				Map.of("toolsListChanged", true, "taskIds", List.of("t-1")));

		StepVerifier.create(listen.messages())
			.assertNext(msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg))
				.isEqualTo(SubscriptionFilter.of(Map.of("toolsListChanged", true))))
			.then(server::closeGracefully)
			.expectNextMatches(JSONRPCResponse.class::isInstance)
			.verifyComplete();
	}

	@Test
	@SuppressWarnings("unchecked")
	void featureHonoursPartOfItsFieldAndItsChangesAreForwarded() {
		McpChangeFeed feed = new McpChangeFeed();
		McpServer server = tasksServer(feed, new TasksExtension(List.of("t-1"), false));

		var listen = (McpTransportResponse.Streaming) listenForTasks(server,
				Map.of("taskIds", List.of("t-1", "t-2"), "toolsListChanged", true));

		StepVerifier.create(listen.messages())
			.assertNext(msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg))
				.isEqualTo(SubscriptionFilter.of(Map.of("toolsListChanged", true, "taskIds", List.of("t-1")))))
			.then(() -> {
				feed.broadcast(new TaskChanged("t-2"));
				feed.broadcast(new TaskChanged("t-1"));
			})
			.assertNext(msg -> {
				JSONRPCNotification notification = (JSONRPCNotification) msg;
				assertThat(notification.method()).isEqualTo("notifications/tasks");
				Map<String, Object> params = (Map<String, Object>) notification.params();
				assertThat(params).containsEntry("taskId", "t-1");
				assertThat((Map<String, Object>) params.get("_meta")).containsEntry("example/trace", "x")
					.containsEntry(MetaKeys.SUBSCRIPTION_ID, 3);
			})
			.then(server::closeGracefully)
			.expectNextMatches(JSONRPCResponse.class::isInstance)
			.verifyComplete();
	}

	@Test
	void featureRejectingTheFilterAnswersBeforeAnyStreamOpens() {
		McpServer server = tasksServer(new McpChangeFeed(), new TasksExtension(List.of("t-1"), true));

		McpTransportResponse response = listenForTasks(server, Map.of("taskIds", List.of("t-1")));

		assertThat(response).isInstanceOfSatisfying(McpTransportResponse.Error.class,
				error -> assertThat(error.response().error().code())
					.isEqualTo(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY));
	}

	@Test
	void featureHonoursNeitherCoreFieldsNorFieldsNotRequested() {
		McpFeature overreaching = new McpFeature() {

			@Override
			public Set<String> methods() {
				return Set.of();
			}

			@Override
			public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
				return Mono.empty();
			}

			@Override
			public SubscriptionFilter honouredSubscriptions(McpRequestContext ctx, SubscriptionFilter requested) {
				return SubscriptionFilter.of(Map.of("promptsListChanged", true, "unrequested", true));
			}

		};
		McpServer server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ToolsFeature.ofAsync(emptyTools(), McpJsonDefaults.getMapper(), PERMISSIVE_VALIDATOR, 0L,
					CacheScope.PRIVATE))
			.feature(overreaching)
			.subscriptions(new McpChangeFeed())
			.build();

		var listen = (McpTransportResponse.Streaming) listenForTasks(server, Map.of("promptsListChanged", true));

		StepVerifier.create(listen.messages())
			.assertNext(msg -> assertThat(acknowledgedNotifications((JSONRPCNotification) msg))
				.isEqualTo(SubscriptionFilter.EMPTY))
			.then(server::closeGracefully)
			.expectNextMatches(JSONRPCResponse.class::isInstance)
			.verifyComplete();
	}

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

	private static void assertNotificationsRequired(McpTransportResponse response) {
		// An error response, not a stream: no acknowledgement is ever sent.
		assertThat(response).asInstanceOf(type(McpTransportResponse.Error.class))
			.extracting(error -> error.response().error())
			.returns(ErrorCodes.INVALID_PARAMS, JSONRPCError::code)
			.extracting(JSONRPCError::message, as(STRING))
			.contains("notifications", "required");
	}

	private static Object subscriptionIdOf(JSONRPCNotification notification) {
		return ((SubscriptionsAcknowledgedParams) notification.params()).meta().get(MetaKeys.SUBSCRIPTION_ID);
	}

}
