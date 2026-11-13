/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@link McpAsyncClient} answering the modern server's input requests.
 */
@SuppressWarnings("deprecation")
class McpAsyncClientInputTests {

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Map<String, Object> NAME_SCHEMA = Map.of("type", "object", "properties",
			Map.of("name", Map.of("type", "string")));

	private static final ClientCapabilities ELICITATION = ClientCapabilities.builder()
		.elicitation(new ClientCapabilities.Elicitation(new ClientCapabilities.Elicitation.Form(), null))
		.build();

	private final List<String> seenRequestStates = new CopyOnWriteArrayList<>();

	private final List<ClientCapabilities> seenCapabilities = new CopyOnWriteArrayList<>();

	private final List<Object> seenProgressTokens = new CopyOnWriteArrayList<>();

	private final MockMcpClientTransport transport = new MockMcpClientTransport(server(), JSON_MAPPER, false);

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
				return ToolsPage.of(List.of());
			}

			@Override
			public McpSyncTool find(McpRequestContext ctx, String name) {
				Tool tool = Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build();
				McpSyncTool.Handler handler = (c, request) -> {
					seenCapabilities.add(c.clientCapabilities());
					seenProgressTokens.add(String.valueOf(c.progressToken()));
					if (request.requestState() != null) {
						seenRequestStates.add(request.requestState());
					}
					return call(c, request);
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

	private McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
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

	private McpClient.AsyncBuilder client() {
		return McpClient.async(this.transport).jsonMapper(JSON_MAPPER);
	}

	private static McpAsyncElicitationHandler answering(String name) {
		return request -> Mono.just(ElicitResult.accept(Map.of("name", name)));
	}

	@Test
	void elicitationIsAnsweredAndTheRequestStateEchoed() {
		McpAsyncClient client = client().formElicitation(answering("Ada")).build();

		CallToolResult result = client.tool("greet").call().block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("Hello, Ada");
		assertThat(this.seenRequestStates).containsExactly("round-1");
		assertThat(this.transport.requests()).hasSize(2);
		assertThat(this.transport.requests().get(0).message().id())
			.isNotEqualTo(this.transport.requests().get(1).message().id());
		assertThat(this.seenCapabilities).allSatisfy(capabilities -> {
			assertThat(capabilities.supportsElicitationForm()).isTrue();
			assertThat(capabilities.supportsElicitationUrl()).isFalse();
			assertThat(capabilities.supportsSampling()).isFalse();
		});
	}

	@Test
	void everyRoundIsAnsweredUntilTheServerCompletes() {
		AtomicInteger asked = new AtomicInteger();
		McpAsyncClient client = client()
			.formElicitation(request -> Mono.just(ElicitResult.accept(Map.of("name", "n" + asked.incrementAndGet()))))
			.build();

		CallToolResult result = client.tool("two-step").call().block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("n1+n2");
		assertThat(this.transport.requests()).hasSize(3);
	}

	@Test
	void handlersOfARoundRunConcurrently() {
		Sinks.One<Boolean> bothAsked = Sinks.one();
		AtomicInteger waiting = new AtomicInteger();
		Mono<Boolean> barrier = Mono.defer(() -> {
			if (waiting.incrementAndGet() == 2) {
				bothAsked.tryEmitValue(true);
			}
			return bothAsked.asMono();
		});
		McpAsyncClient client = client()
			.formElicitation(request -> barrier.thenReturn(ElicitResult.accept(Map.of("name", "Ada"))))
			.urlElicitation(request -> barrier.thenReturn(ElicitResult.decline()))
			.build();

		CallToolResult result = client.tool("everything").call().block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("Ada/decline");
		ClientCapabilities declared = this.seenCapabilities.get(0);
		assertThat(declared.supportsElicitationForm()).isTrue();
		assertThat(declared.supportsElicitationUrl()).isTrue();
	}

	@Test
	void failingHandlerFailsTheCallAndCancelsTheOthers() {
		AtomicBoolean consentCancelled = new AtomicBoolean();
		// The handlers run in the order of the server's keys; the form fails only once
		// the
		// URL elicitation is running, so there is a running handler to cancel.
		Sinks.Empty<Void> consentAsked = Sinks.empty();
		McpAsyncClient client = client()
			.formElicitation(
					request -> consentAsked.asMono().then(Mono.error(new IllegalStateException("dialog closed"))))
			.urlElicitation(request -> Mono.<ElicitResult>never()
				.doOnSubscribe(subscription -> consentAsked.tryEmitEmpty())
				.doOnCancel(() -> consentCancelled.set(true)))
			.build();

		assertThatThrownBy(() -> client.tool("everything").call().block(TIMEOUT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("dialog closed");
		// Cancellation runs asynchronously. Under high load (e.g. CI), the signal may not
		// be instantaneous
		await().atMost(TIMEOUT).pollDelay(Duration.ZERO).untilTrue(consentCancelled);
		assertThat(this.transport.requests()).hasSize(1);
	}

	@Test
	void perCallHandlerOverridesTheClientsForThatCallOnly() {
		McpAsyncClient client = client().formElicitation(answering("Ada")).build();

		String perCall = client.tool("greet")
			.formElicitation(request -> Mono.just(ElicitResult.decline()))
			.call()
			.map(McpAsyncClientInputTests::textOf)
			.block(TIMEOUT);
		String clientWide = client.tool("greet").call().map(McpAsyncClientInputTests::textOf).block(TIMEOUT);

		assertThat(perCall).isEqualTo("Hello, stranger");
		assertThat(clientWide).isEqualTo("Hello, Ada");
	}

	@Test
	void perCallHandlerDeclaresItsCapabilityForThatCall() {
		McpAsyncClient client = client().build();

		assertThatThrownBy(() -> client.tool("confirm").call().block(TIMEOUT))
			.isInstanceOf(McpMissingClientCapabilityException.class);
		CallToolResult result = client.tool("confirm").formElicitation(answering("Bob")).call().block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("Hello, Bob");
	}

	@Test
	void urlElicitationReachesTheUrlHandler() {
		AtomicReference<ElicitUrlRequest> seen = new AtomicReference<>();
		McpAsyncClient client = client().formElicitation(request -> {
			throw new AssertionError("asked a form handler for a URL");
		}).urlElicitation(request -> {
			seen.set(request);
			return Mono.just(ElicitResult.accept(null));
		}).build();

		CallToolResult result = client.tool("login").call().block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("login accept");
		assertThat(seen.get().url()).isEqualTo("https://example.com/login");
		assertThat(this.seenCapabilities.get(0).supportsElicitationUrl()).isTrue();
	}

	@Test
	void stateOnlyRoundIsRetriedWithTheState() {
		McpAsyncClient client = client().build();

		CallToolResult result = client.tool("state-only").call().block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("after later");
		assertThat(this.transport.requests()).hasSize(2);
	}

	@Test
	void serverThatKeepsAskingExceedsTheRounds() {
		McpAsyncClient client = client().formElicitation(answering("Ada")).maxInputRounds(3).build();

		assertThatThrownBy(() -> client.tool("forever").call().block(TIMEOUT)).isInstanceOfSatisfying(
				McpInputRoundsExceededException.class, ex -> assertThat(ex.maxRounds()).isEqualTo(3));
		assertThat(this.transport.requests()).hasSize(4);
	}

	@Test
	void roundTimeoutDoesNotCoverTheTimeSpentInHandlers() {
		McpAsyncClient client = client().roundTimeout(Duration.ofSeconds(20))
			.formElicitation(
					request -> Mono.delay(Duration.ofMinutes(5)).thenReturn(ElicitResult.accept(Map.of("name", "Ada"))))
			.build();

		StepVerifier.withVirtualTime(() -> client.tool("greet").call().map(McpAsyncClientInputTests::textOf))
			.expectSubscription()
			.thenAwait(Duration.ofMinutes(5))
			.expectNext("Hello, Ada")
			.verifyComplete();
	}

	@Test
	void callTimeoutCoversTheTimeSpentInHandlers() {
		AtomicBoolean cancelled = new AtomicBoolean();
		McpAsyncClient client = client()
			.formElicitation(request -> Mono.<ElicitResult>never().doOnCancel(() -> cancelled.set(true)))
			.build();

		StepVerifier.withVirtualTime(() -> client.tool("greet").callTimeout(Duration.ofMinutes(10)).call())
			.expectSubscription()
			.thenAwait(Duration.ofMinutes(10))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(McpTimeoutException.class, ex -> {
				assertThat(ex.kind()).isEqualTo(McpTimeoutException.Kind.CALL);
				assertThat(ex.timeout()).isEqualTo(Duration.ofMinutes(10));
			}))
			.verify(TIMEOUT);
		assertThat(cancelled).isTrue();
		assertThat(this.transport.requests()).hasSize(1);
	}

	@Test
	void handlersRunInTheCallsContext() {
		AtomicReference<Object> user = new AtomicReference<>();
		McpAsyncClient client = client().formElicitation(request -> Mono.deferContextual(ctx -> {
			user.set(ctx.<McpTransportContext>get(McpTransportContext.KEY).get("user"));
			return Mono.just(ElicitResult.accept(Map.of("name", "Ada")));
		})).build();

		client.tool("greet")
			.call()
			.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, McpTransportContext.create(Map.of("user", "alice"))))
			.block(TIMEOUT);

		assertThat(user.get()).isEqualTo("alice");
	}

	@Test
	void progressReachesTheListenerOfTheCall() {
		List<ProgressParams> progress = new CopyOnWriteArrayList<>();
		McpAsyncClient client = client().build();

		client.tool("progress").progress(collectingInto(progress)).call().block(TIMEOUT);

		assertThat(progress).extracting(ProgressParams::message).containsExactly("half", "done");
	}

	@Test
	void progressFallsBackToTheClientsListener() {
		List<ProgressParams> clientWide = new CopyOnWriteArrayList<>();
		List<ProgressParams> perCall = new CopyOnWriteArrayList<>();
		McpAsyncClient client = client().progress(collectingInto(clientWide)).build();

		client.tool("progress").call().block(TIMEOUT);
		client.tool("progress").progress(collectingInto(perCall)).call().block(TIMEOUT);

		assertThat(clientWide).hasSize(2);
		assertThat(perCall).hasSize(2);
	}

	@Test
	void resultWaitsForTheProgressListener() {
		List<ProgressParams> handled = new CopyOnWriteArrayList<>();
		McpAsyncClient client = client().build();

		client.tool("progress")
			.progress(p -> Mono.delay(Duration.ofMillis(50)).doOnNext(tick -> handled.add(p)).then())
			.call()
			.block(TIMEOUT);

		assertThat(handled).extracting(ProgressParams::message).containsExactly("half", "done");
	}

	@Test
	void failingProgressListenerDoesNotFailTheCall() {
		AtomicInteger calls = new AtomicInteger();
		McpAsyncClient client = client().build();

		CallToolResult result = client.tool("progress").progress(p -> {
			calls.incrementAndGet();
			return Mono.error(new IllegalStateException("listener failed"));
		}).call().block(TIMEOUT);

		assertThat(result).isNotNull();
		assertThat(calls).hasValue(2);
	}

	@Test
	void everyRoundGetsItsOwnProgressToken() {
		McpAsyncClient client = client().progress(p -> Mono.empty()).formElicitation(answering("Ada")).build();

		client.tool("greet").call().block(TIMEOUT);

		assertThat(this.seenProgressTokens).hasSize(2).doesNotHaveDuplicates().doesNotContain("null");
		assertThat(this.transport.requests()).extracting(McpAsyncClientInputTests::progressToken).doesNotContainNull();
	}

	@Test
	void withoutListenerNoProgressTokenIsSent() {
		McpAsyncClient client = client().build();

		client.tool("progress").call().block(TIMEOUT);

		assertThat(this.seenProgressTokens).containsExactly("null");
	}

	private static Function<ProgressParams, Mono<Void>> collectingInto(List<ProgressParams> progress) {
		return p -> Mono.fromRunnable(() -> progress.add(p));
	}

	private static Object progressToken(McpTransportRequest request) {
		Map<?, ?> meta = (Map<?, ?>) ((Map<?, ?>) request.message().params()).get("_meta");
		return meta.get(MetaKeys.PROGRESS_TOKEN);
	}

}
