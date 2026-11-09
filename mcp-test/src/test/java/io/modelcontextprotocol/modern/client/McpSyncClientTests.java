/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.DiscoverResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;
import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.modern.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.client.transport.McpClientTransport;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import io.modelcontextprotocol.modern.server.InputResponses;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.util.ToolsUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link McpSyncClient} against the modern server and against a transport that answers as
 * scripted.
 */
@SuppressWarnings("deprecation")
class McpSyncClientTests {

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final Map<String, Object> NAME_SCHEMA = Map.of("type", "object", "properties",
			Map.of("name", Map.of("type", "string")));

	private static final ClientCapabilities ELICITATION = ClientCapabilities.builder()
		.elicitation(new ClientCapabilities.Elicitation(new ClientCapabilities.Elicitation.Form(), null))
		.build();

	private static final Tool GREET = Tool.builder("greet", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final ThreadLocal<String> USER = new ThreadLocal<>();

	private final List<ClientCapabilities> seenCapabilities = new CopyOnWriteArrayList<>();

	private final List<String> seenRequestStates = new CopyOnWriteArrayList<>();

	private final List<McpTransportContext> seenContexts = new CopyOnWriteArrayList<>();

	private final MockMcpClientTransport inProcess = new MockMcpClientTransport(server(), JSON_MAPPER, false);

	// Records the identity each exchange is sent for.
	private final McpClientTransport transport = request -> Flux.deferContextual(ctx -> {
		this.seenContexts.add(ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY));
		return this.inProcess.exchange(request);
	});

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	private static String textOf(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	@Nullable private static <T> T answer(CallToolRequest request, String key, Class<T> type) {
		return InputResponses.get(request.inputResponses(), key, type, JSON_MAPPER);
	}

	private static McpSyncResponse<CallToolOutcome> askName(String key, String state) {
		return McpSyncResponse.result(InputRequiredResult.builder()
			.elicit(key, ElicitFormRequest.builder("Your " + key + "?", NAME_SCHEMA).build())
			.requestState(state)
			.build());
	}

	private McpServer server() {
		McpSyncToolRepository tools = new McpSyncToolRepository() {
			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				return ToolsPage.of(List.of(GREET));
			}

			@Override
			public McpSyncTool find(McpRequestContext ctx, String name) {
				Tool tool = Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build();
				McpSyncTool.Handler handler = (c, request) -> {
					seenCapabilities.add(c.clientCapabilities());
					if (request.requestState() != null) {
						seenRequestStates.add(request.requestState());
					}
					return call(request);
				};
				return "confirm".equals(name) ? McpSyncTool.of(tool, ELICITATION, handler)
						: McpSyncTool.of(tool, handler);
			}
		};
		return McpServer.builder()
			.serverInfo(Implementation.builder("test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.feature(ToolsFeature.ofSync(tools))
			.build();
	}

	private McpSyncResponse<CallToolOutcome> call(CallToolRequest request) {
		switch (request.name()) {
			case "greet", "confirm" -> {
				ElicitResult name = answer(request, "name", ElicitResult.class);
				if (name == null) {
					return askName("name", "round-1");
				}
				return McpSyncResponse.result(text(name.action() == ElicitResult.Action.ACCEPT
						? "Hello, " + name.content().get("name") : "Hello, stranger"));
			}
			case "two-step" -> {
				ElicitResult second = answer(request, "second", ElicitResult.class);
				if (second != null) {
					return McpSyncResponse.result(text(request.requestState() + "+" + second.content().get("name")));
				}
				ElicitResult first = answer(request, "first", ElicitResult.class);
				if (first != null) {
					return askName("second", String.valueOf(first.content().get("name")));
				}
				return askName("first", "round-1");
			}
			case "everything" -> {
				ElicitResult name = answer(request, "name", ElicitResult.class);
				ElicitResult consent = answer(request, "consent", ElicitResult.class);
				if (name != null && consent != null) {
					return McpSyncResponse
						.result(text(name.content().get("name") + "/" + consent.action().name().toLowerCase()));
				}
				return McpSyncResponse.result(InputRequiredResult.builder()
					.elicit("name", ElicitFormRequest.builder("Your name?", NAME_SCHEMA).build())
					.elicitUrl("consent", "Accept the terms", "https://example.com/terms")
					.build());
			}
			case "login" -> {
				ElicitResult login = answer(request, "login", ElicitResult.class);
				if (login != null) {
					return McpSyncResponse.result(text("login " + login.action().name().toLowerCase()));
				}
				return McpSyncResponse.result(InputRequiredResult.builder()
					.elicitUrl("login", "Log in", "https://example.com/login")
					.build());
			}
			case "forever" -> {
				return askName("name", "again");
			}
			case "state-only" -> {
				if (request.requestState() == null) {
					return McpSyncResponse.result(InputRequiredResult.builder().requestState("later").build());
				}
				return McpSyncResponse.result(text("after " + request.requestState()));
			}
			case "progress" -> {
				return McpSyncResponse.streaming(notifier -> {
					notifier.progress(1, 2.0, "half");
					notifier.progress(2, 2.0, "done");
					return text("progressed");
				});
			}
			default -> {
				return McpSyncResponse.result(text(request.name()));
			}
		}
	}

	private McpClient.SyncBuilder client() {
		return McpClient.sync(this.transport).jsonMapper(JSON_MAPPER);
	}

	private static McpSyncElicitationHandler answering(String name) {
		return request -> ElicitResult.accept(Map.of("name", name));
	}

	private static Map<?, ?> meta(McpTransportRequest request) {
		return (Map<?, ?>) ((Map<?, ?>) request.message().params()).get("_meta");
	}

	@Test
	void callsAToolAndListsTools() {
		McpSyncClient client = client().build();

		assertThat(textOf(client.tool("echo").call())).isEqualTo("echo");
		assertThat(client.listTools().tools()).extracting(Tool::name).containsExactly("greet");
	}

	@Test
	void discoverResultIsKeptForItsTtl() {
		McpSyncClient client = client().build();

		DiscoverResult first = client.discover();
		DiscoverResult second = client.discover();

		assertThat(first.supportedVersions()).contains(McpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(second).isEqualTo(first);
	}

	@Test
	void elicitationRunsOnTheCallingThreadAndTheRequestStateIsEchoed() {
		AtomicReference<Thread> handlerThread = new AtomicReference<>();
		McpSyncClient client = client().formElicitation(request -> {
			handlerThread.set(Thread.currentThread());
			return ElicitResult.accept(Map.of("name", "Ada"));
		}).build();

		CallToolResult result = client.tool("greet").call();

		assertThat(textOf(result)).isEqualTo("Hello, Ada");
		assertThat(handlerThread.get()).isSameAs(Thread.currentThread());
		assertThat(this.seenRequestStates).containsExactly("round-1");
		assertThat(this.inProcess.requests()).hasSize(2);
		assertThat(this.seenCapabilities).allSatisfy(capabilities -> {
			assertThat(capabilities.supportsElicitationForm()).isTrue();
			assertThat(capabilities.supportsElicitationUrl()).isFalse();
		});
	}

	@Test
	void handlersSeeTheCallingThreadsThreadLocals() {
		AtomicReference<String> seen = new AtomicReference<>();
		McpSyncClient client = client().formElicitation(request -> {
			seen.set(USER.get());
			return ElicitResult.accept(Map.of("name", "Ada"));
		}).build();

		USER.set("alice");
		try {
			client.tool("greet").call();
		}
		finally {
			USER.remove();
		}

		assertThat(seen.get()).isEqualTo("alice");
	}

	@Test
	void transportContextIsCapturedOnTheCallingThreadForEveryExchange() {
		McpSyncClient client = client()
			.transportContextProvider(() -> McpTransportContext.create(Map.of("user", String.valueOf(USER.get()))))
			.formElicitation(answering("Ada"))
			.build();

		USER.set("alice");
		try {
			client.tool("greet").call();
		}
		finally {
			USER.remove();
		}

		assertThat(this.seenContexts).hasSize(2)
			.allSatisfy(context -> assertThat(context.get("user")).isEqualTo("alice"));
	}

	@Test
	void everyRoundIsAnsweredUntilTheServerCompletes() {
		AtomicInteger asked = new AtomicInteger();
		McpSyncClient client = client()
			.formElicitation(request -> ElicitResult.accept(Map.of("name", "n" + asked.incrementAndGet())))
			.build();

		assertThat(textOf(client.tool("two-step").call())).isEqualTo("n1+n2");
		assertThat(this.inProcess.requests()).hasSize(3);
	}

	@Test
	void formAndUrlElicitationAreAnsweredInOneRound() {
		McpSyncClient client = client().formElicitation(answering("Ada"))
			.urlElicitation(request -> ElicitResult.decline())
			.build();

		assertThat(textOf(client.tool("everything").call())).isEqualTo("Ada/decline");
		assertThat(this.seenCapabilities.get(0).supportsElicitationForm()).isTrue();
		assertThat(this.seenCapabilities.get(0).supportsElicitationUrl()).isTrue();
	}

	@Test
	void failingHandlerFailsTheCallWithoutSendingAnswers() {
		McpSyncClient client = client().formElicitation(request -> {
			throw new IllegalStateException("dialog closed");
		}).urlElicitation(request -> ElicitResult.decline()).build();

		assertThatThrownBy(() -> client.tool("everything").call()).isInstanceOf(IllegalStateException.class)
			.hasMessage("dialog closed");
		assertThat(this.inProcess.requests()).hasSize(1);
	}

	@Test
	void handlerWithoutAnAnswerFailsTheCall() {
		McpSyncClient client = client().formElicitation(request -> null).build();

		assertThatThrownBy(() -> client.tool("greet").call()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("without a result for 'name'");
	}

	@Test
	void perCallHandlerOverridesTheClientsForThatCallOnly() {
		McpSyncClient client = client().formElicitation(answering("Ada")).build();

		CallToolResult perCall = client.tool("greet").formElicitation(request -> ElicitResult.decline()).call();
		CallToolResult clientWide = client.tool("greet").call();

		assertThat(textOf(perCall)).isEqualTo("Hello, stranger");
		assertThat(textOf(clientWide)).isEqualTo("Hello, Ada");
	}

	@Test
	void perCallHandlerDeclaresItsCapabilityForThatCall() {
		McpSyncClient client = client().build();

		assertThatThrownBy(() -> client.tool("confirm").call()).isInstanceOf(McpMissingClientCapabilityException.class);
		assertThat(textOf(client.tool("confirm").formElicitation(answering("Bob")).call())).isEqualTo("Hello, Bob");
	}

	@Test
	void urlElicitationReachesTheUrlHandler() {
		AtomicReference<ElicitUrlRequest> seen = new AtomicReference<>();
		McpSyncClient client = client().formElicitation(request -> {
			throw new AssertionError("asked a form handler for a URL");
		}).urlElicitation(request -> {
			seen.set(request);
			return ElicitResult.accept(null);
		}).build();

		assertThat(textOf(client.tool("login").call())).isEqualTo("login accept");
		assertThat(seen.get().url()).isEqualTo("https://example.com/login");
	}

	@Test
	void stateOnlyRoundIsRetriedWithTheState() {
		assertThat(textOf(client().build().tool("state-only").call())).isEqualTo("after later");
		assertThat(this.inProcess.requests()).hasSize(2);
	}

	@Test
	void serverThatKeepsAskingExceedsTheRounds() {
		McpSyncClient client = client().formElicitation(answering("Ada")).maxInputRounds(3).build();

		assertThatThrownBy(() -> client.tool("forever").call()).isInstanceOfSatisfying(
				McpInputRoundsExceededException.class, ex -> assertThat(ex.maxRounds()).isEqualTo(3));
		assertThat(this.inProcess.requests()).hasSize(4);
	}

	@Test
	void roundTimeoutDoesNotCoverTheTimeSpentInHandlers() {
		McpSyncClient client = client().roundTimeout(Duration.ofMillis(200)).formElicitation(request -> {
			sleep(Duration.ofMillis(400));
			return ElicitResult.accept(Map.of("name", "Ada"));
		}).build();

		assertThat(textOf(client.tool("greet").call())).isEqualTo("Hello, Ada");
	}

	@Test
	void callTimeoutIsCheckedWhenAHandlerReturns() {
		McpSyncClient client = client().formElicitation(request -> {
			sleep(Duration.ofMillis(300));
			return ElicitResult.accept(Map.of("name", "Ada"));
		}).build();

		assertThatThrownBy(() -> client.tool("greet").callTimeout(Duration.ofMillis(100)).call())
			.isInstanceOfSatisfying(McpTimeoutException.class, ex -> {
				assertThat(ex.kind()).isEqualTo(McpTimeoutException.Kind.CALL);
				assertThat(ex.timeout()).isEqualTo(Duration.ofMillis(100));
			});
		assertThat(this.inProcess.requests()).hasSize(1);
	}

	@Test
	void progressReachesTheListenerOfTheCall() {
		List<ProgressParams> progress = new CopyOnWriteArrayList<>();

		client().build().tool("progress").progress(progress::add).call();

		assertThat(progress).extracting(ProgressParams::message).containsExactly("half", "done");
	}

	@Test
	void callOnceReturnsTheInputRequestsAndTheCallContinuesFromThem() {
		McpSyncClient client = client().build();

		// The handler declares the capability; callOnce doesn't run it.
		McpSyncClient.ToolSpec greet = client.tool("greet").formElicitation(answering("unused"));
		CallToolOutcome outcome = greet.callOnce();
		InputRequiredResult inputRequired = (InputRequiredResult) outcome;
		CallToolResult result = greet.inputResponses(Map.of("name", ElicitResult.accept(Map.of("name", "Eve"))))
			.requestState(inputRequired.requestState())
			.call();

		assertThat(inputRequired.inputRequests()).containsOnlyKeys("name");
		assertThat(textOf(result)).isEqualTo("Hello, Eve");
	}

	@Test
	void applicationMetaGoesOnEveryRoundOfTheCall() {
		McpSyncClient client = client().formElicitation(answering("Ada")).build();

		client.tool("greet").meta(Map.of("app/trace", "t1")).call();

		assertThat(this.inProcess.requests()).hasSize(2).allSatisfy(request -> {
			assertThat(meta(request).get("app/trace")).isEqualTo("t1");
			assertThat(meta(request).containsKey(MetaKeys.PROTOCOL_VERSION)).isTrue();
		});
	}

	@Test
	void metaKeysTheClientSetsAreRejected() {
		McpSyncClient client = client().build();

		assertThatIllegalArgumentException().isThrownBy(() -> client.tool("greet").meta(Map.of("progressToken", 1)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> client.tools().meta(Map.of("io.modelcontextprotocol/clientInfo", "x")));
	}

	@Test
	void readsResourcesGetsPromptsAndCompletes() {
		McpSyncClient client = scripted(request -> switch (request.method()) {
			case McpSchema.METHOD_RESOURCES_READ -> Map.of("contents", List.of(Map.of("uri", "test://a", "text", "a")));
			case McpSchema.METHOD_PROMPTS_GET -> Map.of("messages", List.of());
			case McpSchema.METHOD_COMPLETION_COMPLETE -> Map.of("completion", Map.of("values", List.of("ab")));
			default -> throw new IllegalArgumentException(request.method());
		});

		ReadResourceResult read = client.resource("test://a").read();
		GetPromptResult prompt = client.prompt("p").arguments(Map.of("a", "b")).get();
		GetPromptOutcome promptOnce = client.prompt("p").getOnce();
		CompleteResult completion = client.completion(new PromptReference("p"), "a", "a").complete();

		assertThat(read.contents()).hasSize(1);
		assertThat(prompt.messages()).isEmpty();
		assertThat(promptOnce).isInstanceOf(GetPromptResult.class);
		assertThat(completion.completion().values()).containsExactly("ab");
	}

	@Test
	void listenStreamDeliversEventsUntilClosed() throws InterruptedException {
		CountDownLatch cancelled = new CountDownLatch(1);
		List<SubscriptionEvent> events = new CopyOnWriteArrayList<>();
		CountDownLatch received = new CountDownLatch(2);
		List<Object> ends = new CopyOnWriteArrayList<>();
		AtomicReference<McpTransportContext> streamContext = new AtomicReference<>();
		McpClientTransport transport = request -> Flux.deferContextual(ctx -> {
			streamContext.set(ctx.get(McpTransportContext.KEY));
			return Flux
				.<JSONRPCMessage>just(acknowledged(Map.of("toolsListChanged", true)),
						new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, Map.of()))
				.concatWith(Flux.never())
				.doOnCancel(cancelled::countDown);
		});
		McpSyncClient client = McpClient.sync(transport)
			.jsonMapper(JSON_MAPPER)
			.transportContextProvider(() -> McpTransportContext.create(Map.of("user", "alice")))
			.build();

		McpSyncClient.Listening listening = client.listen().toolsChanged().open(new McpSyncSubscriptionListener() {

			@Override
			public void onEvent(SubscriptionEvent event) {
				events.add(event);
				received.countDown();
			}

			@Override
			public void onError(Throwable error) {
				ends.add(error);
			}

			@Override
			public void onComplete() {
				ends.add("complete");
			}

		});
		assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
		listening.close();
		listening.close();

		assertThat(events.get(0)).isInstanceOf(SubscriptionEvent.Subscribed.class);
		assertThat(events.get(1)).isEqualTo(new SubscriptionEvent.ToolsListChanged());
		assertThat(streamContext.get().get("user")).isEqualTo("alice");
		assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(ends).isEmpty();
	}

	@Test
	void listenStreamErrorIsReported() {
		CompletableFuture<Throwable> end = new CompletableFuture<>();
		McpClientTransport transport = request -> Flux.just(acknowledged(Map.of("toolsListChanged", true)),
				JSONRPCResponse.error(request.message().id(),
						new JSONRPCError(ErrorCodes.INTERNAL_ERROR, "gone", null)));
		McpSyncClient client = McpClient.sync(transport).jsonMapper(JSON_MAPPER).build();

		client.listen().toolsChanged().open(new McpSyncSubscriptionListener() {

			@Override
			public void onEvent(SubscriptionEvent event) {
			}

			@Override
			public void onError(Throwable error) {
				end.complete(error);
			}

		});

		assertThat(end).succeedsWithin(Duration.ofSeconds(5))
			.isInstanceOfSatisfying(McpException.class, error -> assertThat(error).hasMessageContaining("gone"));
	}

	@Test
	void listenStreamCompletedByTheServerIsReported() throws InterruptedException {
		CountDownLatch completed = new CountDownLatch(1);
		McpClientTransport transport = request -> Flux.just(acknowledged(Map.of("toolsListChanged", true)),
				JSONRPCResponse.result(request.message().id(), Map.of("resultType", ResultType.COMPLETE)));
		McpSyncClient client = McpClient.sync(transport).jsonMapper(JSON_MAPPER).build();

		client.listen().toolsChanged().open(new McpSyncSubscriptionListener() {

			@Override
			public void onEvent(SubscriptionEvent event) {
			}

			@Override
			public void onComplete() {
				completed.countDown();
			}

		});

		assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void listenerMayCallTheClient() {
		CompletableFuture<ListToolsResult> listed = new CompletableFuture<>();
		McpClientTransport transport = request -> request.method().equals(McpSchema.METHOD_TOOLS_LIST)
				? Flux.just(JSONRPCResponse.result(request.message().id(), Map.of("tools", List.of())))
				: Flux.<JSONRPCMessage>just(acknowledged(Map.of("toolsListChanged", true))).concatWith(Flux.never());
		McpSyncClient client = McpClient.sync(transport).jsonMapper(JSON_MAPPER).build();

		try (McpSyncClient.Listening listening = client.listen().toolsChanged().open(event -> {
			if (event instanceof SubscriptionEvent.Subscribed) {
				listed.complete(client.listTools());
			}
		})) {
			assertThat(listed).succeedsWithin(Duration.ofSeconds(5));
		}
	}

	@Test
	void interruptedCallIsCancelledAndKeepsTheInterrupt() {
		CountDownLatch cancelled = new CountDownLatch(1);
		McpClientTransport transport = request -> Flux.<JSONRPCMessage>never().doOnCancel(cancelled::countDown);
		McpSyncClient client = McpClient.sync(transport).jsonMapper(JSON_MAPPER).build();

		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(() -> client.tool("t").call()).isInstanceOf(CancellationException.class)
				.hasCauseInstanceOf(InterruptedException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
		assertThat(await(cancelled)).isTrue();
	}

	@Test
	void progressListenerRunsOnAThreadThatMayBlock() {
		CompletableFuture<Boolean> nonBlocking = new CompletableFuture<>();
		McpClientTransport transport = request -> Flux
			.<JSONRPCMessage>just(new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_PROGRESS,
					new ProgressParams(progressToken(request), 1.0, null, null, null)))
			.concatWith(Mono.delay(Duration.ofMillis(100))
				.thenReturn(JSONRPCResponse.result(request.message().id(), Map.of("content", List.of()))))
			.subscribeOn(Schedulers.parallel());
		McpSyncClient client = McpClient.sync(transport)
			.jsonMapper(JSON_MAPPER)
			.progress(progress -> nonBlocking.complete(Schedulers.isInNonBlockingThread()))
			.build();

		client.tool("t").call();

		assertThat(nonBlocking).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(false);
	}

	private static Object progressToken(McpTransportRequest request) {
		Map<?, ?> params = JSON_MAPPER.convertValue(request.message().params(), Map.class);
		return ((Map<?, ?>) params.get("_meta")).get("progressToken");
	}

	private static boolean await(CountDownLatch latch) {
		try {
			return latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static JSONRPCNotification acknowledged(Map<String, Object> honoured) {
		return new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED,
				Map.of("notifications", honoured));
	}

	private static McpSyncClient scripted(Function<McpTransportRequest, Object> answer) {
		return McpClient
			.sync(request -> Flux
				.<JSONRPCMessage>just(JSONRPCResponse.result(request.message().id(), answer.apply(request))))
			.jsonMapper(JSON_MAPPER)
			.build();
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
