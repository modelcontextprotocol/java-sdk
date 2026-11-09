/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.DiscoverResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.McpSchema.PromptMessage;
import io.modelcontextprotocol.modern.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.Role;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncPrompt;
import io.modelcontextprotocol.modern.server.feature.McpSyncPromptRepository;
import io.modelcontextprotocol.modern.server.feature.McpSyncResource;
import io.modelcontextprotocol.modern.server.feature.McpSyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.PromptsFeature;
import io.modelcontextprotocol.modern.server.feature.PromptsPage;
import io.modelcontextprotocol.modern.server.feature.ResourceTemplatesPage;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesPage;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link McpAsyncClient} against the modern server.
 */
class McpAsyncClientTests {

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Implementation CLIENT_INFO = Implementation.builder("test-client", "1.0.0").build();

	private static final String OLDER_VERSION = "2026-01-01";

	private static final ClientCapabilities ELICITATION = ClientCapabilities.builder()
		.elicitation(new ClientCapabilities.Elicitation(new ClientCapabilities.Elicitation.Form(), null))
		.build();

	private static final Tool ECHO = Tool.builder("echo", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final Tool ASK = Tool.builder("ask", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final Tool CONFIRM = Tool.builder("confirm", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final Tool WHO = Tool.builder("who", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private final AtomicReference<McpRequestContext> lastContext = new AtomicReference<>();

	private final AtomicReference<CallToolRequest> lastCall = new AtomicReference<>();

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	private static String textOf(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	private McpSyncToolRepository tools() {
		return new McpSyncToolRepository() {
			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				if (cursor == null) {
					return new ToolsPage(List.of(ECHO, ASK), "page-2", 1000L, CacheScope.PUBLIC);
				}
				if ("page-2".equals(cursor)) {
					return ToolsPage.of(List.of(CONFIRM, WHO));
				}
				throw McpException.invalidParams("Unknown cursor");
			}

			@Override
			public McpSyncTool find(McpRequestContext ctx, String name) {
				return switch (name) {
					case "echo" -> McpSyncTool.of(ECHO, (c, request) -> {
						lastContext.set(c);
						lastCall.set(request);
						return McpSyncResponse.result(text(String.valueOf(request.arguments().get("text"))));
					});
					case "ask" -> McpSyncTool.of(ASK, (c, request) -> {
						lastCall.set(request);
						if (request.inputResponses() == null) {
							return McpSyncResponse
								.result(InputRequiredResult.builder().requestState("round-1").build());
						}
						return McpSyncResponse.result(text("answered " + request.requestState()));
					});
					case "confirm" ->
						McpSyncTool.of(CONFIRM, ELICITATION, (c, request) -> McpSyncResponse.result(text("confirmed")));
					case "who" -> McpSyncTool.of(WHO, (c, request) -> McpSyncResponse
						.result(text(String.valueOf(c.transportContext().get("user")))));
					default -> null;
				};
			}
		};
	}

	private static McpSyncResourceRepository resources() {
		return new McpSyncResourceRepository() {
			@Override
			public ResourcesPage list(McpRequestContext ctx, String cursor) {
				return ResourcesPage.of(List.of(Resource.builder("file:///a.txt", "a").build()));
			}

			@Override
			public ResourceTemplatesPage listTemplates(McpRequestContext ctx, String cursor) {
				return ResourceTemplatesPage.of(List.of(ResourceTemplate.builder("file:///{name}", "files").build()));
			}

			@Override
			public McpSyncResource find(McpRequestContext ctx, String uri) {
				if (!uri.startsWith("file:///")) {
					return null;
				}
				return McpSyncResource.of((c,
						request) -> McpSyncResponse.result(ReadResourceResult
							.builder(List.of(TextResourceContents.builder(uri, "contents of " + uri).build()))
							.ttlMs(500)
							.build()));
			}
		};
	}

	private static McpSyncPromptRepository prompts() {
		Prompt greeting = Prompt.builder("greeting").arguments(List.of(PromptArgument.builder("name").build())).build();
		return new McpSyncPromptRepository() {
			@Override
			public PromptsPage list(McpRequestContext ctx, String cursor) {
				return PromptsPage.of(List.of(greeting));
			}

			@Override
			public McpSyncPrompt find(McpRequestContext ctx, String name) {
				if (!"greeting".equals(name)) {
					return null;
				}
				return McpSyncPrompt.of(
						greeting, (c,
								request) -> McpSyncResponse.result(
										GetPromptResult
											.builder(
													List.of(PromptMessage
														.builder(Role.USER,
																TextContent
																	.builder(
																			"Hello, " + request.arguments().get("name"))
																	.build())
														.build()))
											.build()))
					.withCompletionHandler((c, request) -> CompleteResult
						.of(new CompleteResult.Completion(List.of(request.argument().value() + "-1",
								request.argument().value() + "-" + request.context().arguments().get("other")))));
			}

			@Override
			public boolean supportsCompletion() {
				return true;
			}
		};
	}

	private McpServer.Builder server() {
		return McpServer.builder()
			.serverInfo(Implementation.builder("test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.instructions("Be nice")
			.feature(ToolsFeature.ofSync(tools()))
			.feature(ResourcesFeature.ofSync(resources()))
			.feature(PromptsFeature.ofSync(prompts()));
	}

	private static MockMcpClientTransport transport(McpServer server) {
		return new MockMcpClientTransport(server, JSON_MAPPER, false);
	}

	private static McpAsyncClient client(MockMcpClientTransport transport) {
		return McpClient.async(transport).clientInfo(CLIENT_INFO).jsonMapper(JSON_MAPPER).build();
	}

	@Test
	void buildingAClientSendsNothing() {
		MockMcpClientTransport transport = transport(server().build());

		McpAsyncClient client = client(transport);
		Mono<CallToolResult> call = client.tool("echo").arguments(Map.of("text", "hi")).call();

		assertThat(client).isNotNull();
		assertThat(call).isNotNull();
		assertThat(transport.requests()).isEmpty();
	}

	@Test
	void listsToolsPageByPage() {
		McpAsyncClient client = client(transport(server().build()));

		ListToolsResult first = client.tools().list().block(TIMEOUT);
		ListToolsResult second = client.tools().cursor(first.nextCursor()).list().block(TIMEOUT);

		assertThat(first.tools()).extracting(Tool::name).containsExactly("echo", "ask");
		assertThat(first.ttlMs()).isEqualTo(1000L);
		assertThat(first.cacheScope()).isEqualTo(CacheScope.PUBLIC);
		assertThat(second.tools()).extracting(Tool::name).containsExactly("confirm", "who");
		assertThat(second.nextCursor()).isNull();
	}

	@Test
	void listToolsCollectsEveryPage() {
		McpAsyncClient client = client(transport(server().build()));

		ListToolsResult all = client.listTools().block(TIMEOUT);

		assertThat(all.tools()).extracting(Tool::name).containsExactly("echo", "ask", "confirm", "who");
		assertThat(all.nextCursor()).isNull();
	}

	@Test
	void listingThatDoesNotEndWithinTheLimitFails() {
		McpAsyncClient client = McpClient.async(transport(server().build())).maxListPages(1).build();

		assertThatThrownBy(() -> client.listTools().block(TIMEOUT)).isInstanceOf(McpInvalidResultException.class)
			.hasMessageContaining("1 pages");
	}

	@Test
	void listsPromptsResourcesAndTemplates() {
		McpAsyncClient client = client(transport(server().build()));

		assertThat(client.prompts().list().block(TIMEOUT).prompts()).extracting(Prompt::name)
			.containsExactly("greeting");
		assertThat(client.resources().list().block(TIMEOUT).resources()).extracting(Resource::uri)
			.containsExactly("file:///a.txt");
		assertThat(client.resourceTemplates().list().block(TIMEOUT).resourceTemplates())
			.extracting(ResourceTemplate::uriTemplate)
			.containsExactly("file:///{name}");
	}

	@Test
	void callsAToolWithItsArguments() {
		MockMcpClientTransport transport = transport(server().build());
		McpAsyncClient client = client(transport);

		CallToolResult result = client.tool("echo").arguments(Map.of("text", "hi")).call().block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("hi");
		McpTransportRequest sent = transport.requests().get(0);
		assertThat(sent.method()).isEqualTo(McpSchema.METHOD_TOOLS_CALL);
		assertThat(sent.name()).isEqualTo("echo");
		assertThat(sent.protocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);
		McpRequestContext ctx = this.lastContext.get();
		assertThat(ctx.protocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(ctx.clientInfo()).isEqualTo(CLIENT_INFO);
		assertThat(ctx.clientCapabilities()).isEqualTo(ClientCapabilities.NONE);
		assertThat(ctx.progressToken()).isNull();
	}

	@Test
	void readsAResource() {
		MockMcpClientTransport transport = transport(server().build());
		McpAsyncClient client = client(transport);

		ReadResourceResult result = client.resource("file:///a.txt").read().block(TIMEOUT);

		assertThat(((TextResourceContents) result.contents().get(0)).text()).isEqualTo("contents of file:///a.txt");
		assertThat(result.ttlMs()).isEqualTo(500L);
		assertThat(transport.requests().get(0).name()).isEqualTo("file:///a.txt");
	}

	@Test
	void getsAPrompt() {
		MockMcpClientTransport transport = transport(server().build());
		McpAsyncClient client = client(transport);

		GetPromptResult result = client.prompt("greeting").arguments(Map.of("name", "Ada")).get().block(TIMEOUT);

		assertThat(((TextContent) result.messages().get(0).content()).text()).isEqualTo("Hello, Ada");
		assertThat(transport.requests().get(0).name()).isEqualTo("greeting");
	}

	@Test
	void completesAnArgument() {
		McpAsyncClient client = client(transport(server().build()));

		CompleteResult result = client.completion(new PromptReference("greeting"), "name", "A")
			.context(Map.of("other", "x"))
			.complete()
			.block(TIMEOUT);

		assertThat(result.completion().values()).containsExactly("A-1", "A-x");
	}

	@Test
	void discoverResultIsKeptForItsTtl() {
		MockMcpClientTransport transport = transport(server().discoverCache(60_000, CacheScope.PRIVATE).build());
		McpAsyncClient client = client(transport);

		DiscoverResult first = client.discover().block(TIMEOUT);
		DiscoverResult second = client.discover().block(TIMEOUT);

		assertThat(first.supportedVersions()).containsExactly(McpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(first.instructions()).isEqualTo("Be nice");
		assertThat(first.capabilities().tools()).isNotNull();
		assertThat(second).isSameAs(first);
		assertThat(transport.requests()).hasSize(1);
	}

	@Test
	void discoverResultWithoutTtlIsNotKept() {
		MockMcpClientTransport transport = transport(server().build());
		McpAsyncClient client = client(transport);

		client.discover().block(TIMEOUT);
		client.discover().block(TIMEOUT);

		assertThat(transport.requests()).hasSize(2);
	}

	@Test
	void serverErrorIsAnMcpException() {
		McpAsyncClient client = client(transport(server().build()));

		assertThatThrownBy(() -> client.tool("missing").call().block(TIMEOUT)).isInstanceOf(McpException.class)
			.satisfies(ex -> assertThat(((McpException) ex).error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS));
	}

	@Test
	void missingCapabilityListsTheRequiredCapabilities() {
		McpAsyncClient client = client(transport(server().build()));

		assertThatThrownBy(() -> client.tool("confirm").call().block(TIMEOUT))
			.isInstanceOf(McpMissingClientCapabilityException.class)
			.satisfies(ex -> {
				McpMissingClientCapabilityException missing = (McpMissingClientCapabilityException) ex;
				assertThat(missing.error().code()).isEqualTo(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY);
				assertThat(missing.requiredCapabilities().elicitation()).isNotNull();
			});
	}

	@Test
	void callOnceReturnsTheInputRequiredResult() {
		McpAsyncClient client = client(transport(server().build()));

		CallToolOutcome outcome = client.tool("ask").callOnce().block(TIMEOUT);

		assertThat(outcome).isInstanceOf(InputRequiredResult.class);
		InputRequiredResult inputRequired = (InputRequiredResult) outcome;
		assertThat(inputRequired.inputRequests()).isNull();
		assertThat(inputRequired.requestState()).isNotEmpty();
	}

	@Test
	void callContinuesWithInputResponsesAndRequestState() {
		McpAsyncClient client = client(transport(server().build()));
		McpAsyncClient.ToolSpec ask = client.tool("ask");
		InputRequiredResult firstRound = (InputRequiredResult) ask.callOnce().block(TIMEOUT);

		CallToolResult result = ask.inputResponses(Map.of("login", Map.of("action", "accept")))
			.requestState(firstRound.requestState())
			.call()
			.block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("answered round-1");
		assertThat(this.lastCall.get().inputResponses()).containsOnlyKeys("login");
	}

	@Test
	void callRetriesARequestStateOnlyRoundUntilTheRoundLimit() {
		MockMcpClientTransport transport = transport(server().build());
		McpAsyncClient client = McpClient.async(transport).maxInputRounds(2).build();

		assertThatThrownBy(() -> client.tool("ask").call().block(TIMEOUT))
			.isInstanceOf(McpInputRoundsExceededException.class);
		assertThat(transport.requests()).hasSize(3);
	}

	@Test
	void onceTerminalsOfReadAndGetReturnCompleteResults() {
		McpAsyncClient client = client(transport(server().build()));

		ReadResourceOutcome read = client.resource("file:///a.txt").readOnce().block(TIMEOUT);
		GetPromptOutcome get = client.prompt("greeting").arguments(Map.of("name", "Ada")).getOnce().block(TIMEOUT);

		assertThat(read).isInstanceOf(ReadResourceResult.class);
		assertThat(get).isInstanceOf(GetPromptResult.class);
	}

	@Test
	void subscriberContextIsTheIdentityOfTheCall() {
		McpAsyncClient client = client(transport(server().build()));
		Mono<CallToolResult> call = client.tool("who").call();

		String alice = call
			.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, McpTransportContext.create(Map.of("user", "alice"))))
			.map(McpAsyncClientTests::textOf)
			.block(TIMEOUT);
		String bob = call
			.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, McpTransportContext.create(Map.of("user", "bob"))))
			.map(McpAsyncClientTests::textOf)
			.block(TIMEOUT);

		assertThat(alice).isEqualTo("alice");
		assertThat(bob).isEqualTo("bob");
	}

	@Test
	void specsAreImmutableAndReusable() {
		MockMcpClientTransport transport = transport(server().build());
		McpAsyncClient client = client(transport);
		McpAsyncClient.ToolSpec echo = client.tool("echo");
		McpAsyncClient.ToolSpec hi = echo.arguments(Map.of("text", "hi"));
		McpAsyncClient.ToolSpec bye = echo.arguments(Map.of("text", "bye"));

		List<String> answers = Flux.concat(hi.call(), bye.call(), hi.call())
			.map(McpAsyncClientTests::textOf)
			.collectList()
			.block(TIMEOUT);

		assertThat(answers).containsExactly("hi", "bye", "hi");
		assertThat(transport.requests()).extracting(request -> request.message().id()).doesNotHaveDuplicates();
	}

	@Test
	void clientsSharingATransportUseDistinctIds() {
		MockMcpClientTransport transport = transport(server().build());
		McpAsyncClient first = client(transport);
		McpAsyncClient second = client(transport);

		Flux.concat(first.listTools(), second.listTools(), first.listTools()).blockLast(TIMEOUT);

		assertThat(transport.requests()).extracting(request -> request.message().id()).doesNotHaveDuplicates();
	}

	@Test
	void unsupportedVersionIsRenegotiatedOnceAndKept() {
		MockMcpClientTransport transport = transport(server().supportedVersions(List.of(OLDER_VERSION)).build());
		McpAsyncClient client = McpClient.async(transport)
			.protocolVersions(List.of(McpSchema.LATEST_PROTOCOL_VERSION, OLDER_VERSION))
			.build();

		client.tool("echo").arguments(Map.of("text", "hi")).call().block(TIMEOUT);
		client.tools().list().block(TIMEOUT);

		List<McpTransportRequest> requests = transport.requests();
		assertThat(requests).extracting(McpTransportRequest::protocolVersion)
			.containsExactly(McpSchema.LATEST_PROTOCOL_VERSION, OLDER_VERSION, OLDER_VERSION);
		assertThat(requests.get(0).message().id()).isNotEqualTo(requests.get(1).message().id());
		assertThat(this.lastContext.get().protocolVersion()).isEqualTo(OLDER_VERSION);
		Map<?, ?> meta = (Map<?, ?>) JSON_MAPPER.convertValue(requests.get(1).message().params(), Map.class)
			.get("_meta");
		assertThat(meta.get(MetaKeys.PROTOCOL_VERSION)).isEqualTo(OLDER_VERSION);
	}

	@Test
	void unsupportedVersionWithoutOverlapFailsWithTheServersError() {
		MockMcpClientTransport transport = transport(server().supportedVersions(List.of("2099-01-01")).build());
		McpAsyncClient client = client(transport);

		assertThatThrownBy(() -> client.listTools().block(TIMEOUT)).isInstanceOf(McpException.class)
			.satisfies(ex -> assertThat(((McpException) ex).error().code())
				.isEqualTo(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION));
		assertThat(transport.requests()).hasSize(1);
	}

}
