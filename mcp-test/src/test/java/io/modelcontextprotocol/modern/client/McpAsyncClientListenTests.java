/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.client.SubscriptionEvent.PromptsListChanged;
import io.modelcontextprotocol.modern.client.SubscriptionEvent.ResourceUpdated;
import io.modelcontextprotocol.modern.client.SubscriptionEvent.Subscribed;
import io.modelcontextprotocol.modern.client.SubscriptionEvent.ToolsListChanged;
import io.modelcontextprotocol.modern.client.transport.McpClientTransport;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResource;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesPage;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Listen streams of {@link McpAsyncClient}, against the modern server and against a
 * transport that answers as scripted.
 */
class McpAsyncClientListenTests {

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Tool PLAIN = Tool.builder("plain", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private final TestChangePublisher changes = new TestChangePublisher();

	private final List<McpTransportRequest> scriptedRequests = new CopyOnWriteArrayList<>();

	private Long toolsTtlMs;

	// A server with tools and subscribable resources, but no prompts.
	private McpServer server() {
		McpSyncToolRepository tools = new McpSyncToolRepository() {
			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				return new ToolsPage(List.of(PLAIN), null, toolsTtlMs, null);
			}

			@Override
			public McpSyncTool find(McpRequestContext ctx, String name) {
				return McpSyncTool.of(PLAIN, (c, request) -> McpSyncResponse
					.result(CallToolResult.builder().addContent(TextContent.builder("plain").build()).build()));
			}
		};
		McpAsyncResourceRepository resources = new McpAsyncResourceRepository() {
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
				return true;
			}
		};
		return McpServer.builder()
			.serverInfo(Implementation.builder("test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.feature(ToolsFeature.ofSync(tools))
			.feature(ResourcesFeature.ofAsync(resources, JSON_MAPPER, 0L, CacheScope.PRIVATE))
			.subscriptions(this.changes)
			.build();
	}

	private static McpAsyncClient client(McpClientTransport transport) {
		return McpClient.async(transport).jsonMapper(JSON_MAPPER).build();
	}

	private McpAsyncClient scripted(Function<McpTransportRequest, Flux<JSONRPCMessage>> answer) {
		return client(request -> Flux.defer(() -> {
			this.scriptedRequests.add(request);
			return answer.apply(request);
		}));
	}

	private static JSONRPCNotification acknowledged(Map<String, Object> honoured) {
		return new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED,
				Map.of("notifications", honoured));
	}

	private static JSONRPCResponse completed(McpTransportRequest request) {
		return JSONRPCResponse.result(request.message().id(), Map.of("resultType", ResultType.COMPLETE));
	}

	private static long listedTools(MockMcpClientTransport transport) {
		return transport.requests()
			.stream()
			.filter(request -> McpSchema.METHOD_TOOLS_LIST.equals(request.method()))
			.count();
	}

	@Test
	void streamStartsWithTheHonouredFilterThenReportsChanges() {
		TestChangePublisher.Stream stream = this.changes.nextStream();
		McpAsyncClient client = client(new MockMcpClientTransport(server(), JSON_MAPPER, false));

		StepVerifier.create(client.listen().toolsChanged().promptsChanged().resourceUpdates("test://a").events())
			.assertNext(event -> {
				Subscribed subscribed = (Subscribed) event;
				assertThat(subscribed.requested().fields()).containsExactly(Map.entry("toolsListChanged", true),
						Map.entry("promptsListChanged", true), Map.entry("resourceSubscriptions", List.of("test://a")));
				assertThat(subscribed.honoured().fields()).containsOnly(Map.entry("toolsListChanged", true),
						Map.entry("resourceSubscriptions", List.of("test://a")));
				assertThat(subscribed.missing().fields()).containsExactly(Map.entry("promptsListChanged", true));
			})
			.then(() -> stream.broadcastWhenListening(new ServerChange.ToolsListChanged()))
			.expectNext(new ToolsListChanged())
			.then(() -> stream.broadcast(new ServerChange.ResourceUpdated("test://a")))
			.expectNext(new ResourceUpdated("test://a"))
			.thenCancel()
			.verify(TIMEOUT);

		stream.awaitCancelled();
	}

	@Test
	void serverEndingTheStreamCompletesIt() {
		McpServer server = server();
		McpAsyncClient client = client(new MockMcpClientTransport(server, JSON_MAPPER, false));

		StepVerifier.create(client.listen().toolsChanged().events())
			.expectNextMatches(Subscribed.class::isInstance)
			.then(server::closeGracefully)
			.verifyComplete();
	}

	@Test
	@SuppressWarnings("unchecked")
	void requestCarriesTheFilterAndTheApplicationsMeta() {
		MockMcpClientTransport transport = new MockMcpClientTransport(server(), JSON_MAPPER, false);

		StepVerifier.create(client(transport).listen().resourcesChanged().meta(Map.of("trace", "t-1")).events())
			.expectNextMatches(Subscribed.class::isInstance)
			.thenCancel()
			.verify(TIMEOUT);

		McpTransportRequest request = transport.requests().get(0);
		assertThat(request.method()).isEqualTo(McpSchema.METHOD_SUBSCRIPTIONS_LISTEN);
		Map<?, ?> params = (Map<?, ?>) request.message().params();
		assertThat(params.get("notifications")).isEqualTo(Map.of("resourcesListChanged", true));
		assertThat((Map<String, Object>) params.get("_meta")).containsEntry("trace", "t-1")
			.containsKey(MetaKeys.PROTOCOL_VERSION)
			.containsKey(MetaKeys.CLIENT_CAPABILITIES);
	}

	@Test
	void extensionFilterFieldTheServerDoesNotHonourIsMissing() {
		MockMcpClientTransport transport = new MockMcpClientTransport(server(), JSON_MAPPER, false);

		StepVerifier.create(client(transport).listen().toolsChanged().filter("taskIds", List.of("t-1")).events())
			.assertNext(event -> {
				Subscribed subscribed = (Subscribed) event;
				assertThat(subscribed.requested().fields()).containsEntry("taskIds", List.of("t-1"));
				assertThat(subscribed.missing().fields()).containsExactly(Map.entry("taskIds", List.of("t-1")));
			})
			.thenCancel()
			.verify(TIMEOUT);

		Map<?, ?> params = (Map<?, ?>) transport.requests().get(0).message().params();
		assertThat(params.get("notifications")).isEqualTo(Map.of("toolsListChanged", true, "taskIds", List.of("t-1")));
	}

	/**
	 * A server whose extension honours the task ids in {@code known}, and rejects a
	 * filter with task ids when {@code reject} is set.
	 */
	private McpServer tasksServer(List<String> known, boolean reject) {
		McpFeature tasks = new McpFeature() {

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
				if (!(requested.get("taskIds") instanceof List<?> ids)) {
					return SubscriptionFilter.EMPTY;
				}
				if (reject) {
					throw McpException.missingClientCapability(ClientCapabilities.builder()
						.extensions(Map.of("io.modelcontextprotocol/tasks", Map.of()))
						.build());
				}
				return SubscriptionFilter.EMPTY.with("taskIds", ids.stream().filter(known::contains).toList());
			}

		};
		return McpServer.builder()
			.serverInfo(Implementation.builder("test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.feature(tasks)
			.subscriptions(this.changes)
			.build();
	}

	@Test
	void extensionFieldTheServerHonoursInPartIsMissingTheRest() {
		McpAsyncClient client = client(
				new MockMcpClientTransport(tasksServer(List.of("t-2"), false), JSON_MAPPER, false));

		StepVerifier.create(client.listen().filter("taskIds", List.of("t-1", "t-2")).events()).assertNext(event -> {
			Subscribed subscribed = (Subscribed) event;
			assertThat(subscribed.honoured()).isEqualTo(SubscriptionFilter.of(Map.of("taskIds", List.of("t-2"))));
			assertThat(subscribed.missing()).isEqualTo(SubscriptionFilter.of(Map.of("taskIds", List.of("t-1"))));
		}).thenCancel().verify(TIMEOUT);
	}

	@Test
	void extensionRejectingTheFilterFailsTheStream() {
		McpAsyncClient client = client(new MockMcpClientTransport(tasksServer(List.of(), true), JSON_MAPPER, false));

		StepVerifier.create(client.listen().filter("taskIds", List.of("t-1")).events())
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOfSatisfying(McpMissingClientCapabilityException.class,
							missing -> assertThat(missing.requiredCapabilities().extensions())
								.containsKey("io.modelcontextprotocol/tasks")))
			.verify(TIMEOUT);
	}

	@Test
	void metaTheClientSetsItselfIsRejected() {
		McpAsyncClient.ListenSpec listen = scripted(request -> Flux.never()).listen();

		assertThatIllegalArgumentException().isThrownBy(() -> listen.meta(Map.of("progressToken", 1)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> listen.meta(Map.of(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION)));
	}

	@Test
	void resourceUpdatesAccumulateWithoutDuplicates() {
		McpAsyncClient client = scripted(request -> Flux.just(acknowledged(Map.of()), completed(request)));

		client.listen()
			.resourceUpdates("test://a", "test://b")
			.resourceUpdates("test://a", "test://c")
			.events()
			.blockLast(TIMEOUT);

		Map<?, ?> params = (Map<?, ?>) this.scriptedRequests.get(0).message().params();
		assertThat(params.get("notifications"))
			.isEqualTo(Map.of("resourceSubscriptions", List.of("test://a", "test://b", "test://c")));
	}

	@Test
	void notificationsOutsideTheHonouredFilterAreDropped() {
		McpAsyncClient client = scripted(
				request -> Flux.just(
						new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, Map.of()),
						acknowledged(Map.of("toolsListChanged", true)),
						new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, Map.of()),
						new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
								Map.of("uri", "test://a")),
						new JSONRPCNotification("notifications/unknown", Map.of()),
						acknowledged(Map.of("promptsListChanged", true)),
						new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, Map.of()),
						completed(request)));

		StepVerifier.create(client.listen().toolsChanged().promptsChanged().events())
			.assertNext(event -> assertThat(((Subscribed) event).missing().fields())
				.containsExactly(Map.entry("promptsListChanged", true)))
			.expectNext(new ToolsListChanged())
			.verifyComplete();
	}

	@Test
	void honouredListChangesAreReported() {
		McpAsyncClient client = scripted(request -> Flux.just(acknowledged(Map.of("promptsListChanged", true)),
				new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, Map.of()),
				completed(request)));

		StepVerifier.create(client.listen().promptsChanged().events())
			.expectNextMatches(event -> ((Subscribed) event).missing().isEmpty())
			.expectNext(new PromptsListChanged())
			.verifyComplete();
	}

	@Test
	void errorAnswerFailsTheStreamWithTheServersError() {
		McpAsyncClient client = scripted(request -> Flux
			.just(JSONRPCResponse.error(request.message().id(), new JSONRPCError(-32603, "listen failed", null))));

		StepVerifier.create(client.listen().toolsChanged().events())
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOfSatisfying(McpException.class,
					mcp -> assertThat(mcp.error().code()).isEqualTo(-32603)))
			.verify(TIMEOUT);
	}

	@Test
	void streamEndingWithoutAResultIsLost() {
		McpAsyncClient client = scripted(request -> Flux.just(acknowledged(Map.of("toolsListChanged", true))));

		StepVerifier.create(client.listen().toolsChanged().events())
			.expectNextMatches(Subscribed.class::isInstance)
			.expectError(McpSubscriptionLostException.class)
			.verify(TIMEOUT);
	}

	@Test
	void brokenStreamIsLostWithItsCause() {
		McpResponseLostException broken = new McpResponseLostException(McpSchema.METHOD_SUBSCRIPTIONS_LISTEN,
				new IOException("reset"));
		McpAsyncClient client = scripted(request -> Flux.concat(Flux.just(acknowledged(Map.of())), Flux.error(broken)));

		StepVerifier.create(client.listen().toolsChanged().events())
			.expectNextMatches(Subscribed.class::isInstance)
			.expectErrorSatisfies(
					ex -> assertThat(ex).isInstanceOf(McpSubscriptionLostException.class).hasCause(broken))
			.verify(TIMEOUT);
	}

	@Test
	void otherTransportFailuresReachTheCallerUnwrapped() {
		IllegalStateException refused = new IllegalStateException("token expired");
		McpAsyncClient client = scripted(request -> Flux.error(refused));

		StepVerifier.create(client.listen().toolsChanged().events())
			.expectErrorSatisfies(ex -> assertThat(ex).isSameAs(refused))
			.verify(TIMEOUT);
	}

	@Test
	void resubscribingOpensANewStream() {
		List<Integer> attempts = new ArrayList<>();
		McpAsyncClient client = scripted(request -> {
			attempts.add(attempts.size());
			return attempts.size() == 1 ? Flux.just(acknowledged(Map.of("toolsListChanged", true)))
					: Flux.just(acknowledged(Map.of("toolsListChanged", true)), completed(request));
		});

		StepVerifier
			.create(client.listen()
				.toolsChanged()
				.events()
				.retryWhen(Retry.max(1).filter(McpSubscriptionLostException.class::isInstance)))
			.expectNextMatches(Subscribed.class::isInstance)
			.expectNextMatches(Subscribed.class::isInstance)
			.verifyComplete();

		assertThat(this.scriptedRequests).hasSize(2);
		assertThat(this.scriptedRequests.get(0).message().id())
			.isNotEqualTo(this.scriptedRequests.get(1).message().id());
	}

	@Test
	void toolChangesAndReconnectsMakeTheCatalogListAgain() {
		TestChangePublisher.Stream stream = this.changes.nextStream();
		MockMcpClientTransport transport = new MockMcpClientTransport(server(), JSON_MAPPER, true);
		McpAsyncClient client = client(transport);
		client.tool("plain").call().block(TIMEOUT);
		client.tool("plain").call().block(TIMEOUT);
		assertThat(listedTools(transport)).isEqualTo(1);

		StepVerifier.create(client.listen().toolsChanged().events())
			.expectNextMatches(Subscribed.class::isInstance)
			.then(() -> {
				client.tool("plain").call().block(TIMEOUT);
				assertThat(listedTools(transport)).isEqualTo(2);
				stream.broadcastWhenListening(new ServerChange.ToolsListChanged());
			})
			.expectNext(new ToolsListChanged())
			.then(() -> {
				client.tool("plain").call().block(TIMEOUT);
				assertThat(listedTools(transport)).isEqualTo(3);
			})
			.thenCancel()
			.verify(TIMEOUT);
	}

	@Test
	void toolChangesDropTheKeptListing() {
		TestChangePublisher.Stream stream = this.changes.nextStream();
		this.toolsTtlMs = 60_000L;
		MockMcpClientTransport transport = new MockMcpClientTransport(server(), JSON_MAPPER, false);
		McpAsyncClient client = client(transport);
		client.listTools().list().block(TIMEOUT);
		client.listTools().list().block(TIMEOUT);
		assertThat(listedTools(transport)).isEqualTo(1);

		StepVerifier.create(client.listen().toolsChanged().events())
			.expectNextMatches(Subscribed.class::isInstance)
			.then(() -> {
				client.listTools().list().block(TIMEOUT);
				assertThat(listedTools(transport)).isEqualTo(2);
				stream.broadcastWhenListening(new ServerChange.ToolsListChanged());
			})
			.expectNext(new ToolsListChanged())
			.then(() -> {
				client.listTools().list().block(TIMEOUT);
				assertThat(listedTools(transport)).isEqualTo(3);
			})
			.thenCancel()
			.verify(TIMEOUT);
	}

	@Test
	void streamThatDoesNotTrackToolsKeepsTheCatalog() {
		MockMcpClientTransport transport = new MockMcpClientTransport(server(), JSON_MAPPER, true);
		McpAsyncClient client = client(transport);
		client.tool("plain").call().block(TIMEOUT);

		StepVerifier.create(client.listen().resourcesChanged().events())
			.expectNextMatches(Subscribed.class::isInstance)
			.thenCancel()
			.verify(TIMEOUT);

		client.tool("plain").call().block(TIMEOUT);
		assertThat(listedTools(transport)).isEqualTo(1);
	}

}
