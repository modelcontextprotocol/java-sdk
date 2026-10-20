/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.ModernTestFixtures;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.PERMISSIVE_VALIDATOR;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.emptyTools;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ToolsFeatureTests {

	private static final Tool ECHO_TOOL = Tool.builder("echo", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static McpFeature tools(McpAsyncToolRepository repository) {
		return ToolsFeature.ofAsync(repository, new GsonMcpJsonMapper(), PERMISSIVE_VALIDATOR, 0L, CacheScope.PRIVATE);
	}

	private static McpFeature tools(McpSyncToolRepository repository) {
		return ToolsFeature.ofSync(repository, new GsonMcpJsonMapper(), PERMISSIVE_VALIDATOR, 0L, CacheScope.PRIVATE);
	}

	private static McpServer.Builder baseBuilder() {
		return McpServer.builder().serverInfo(SERVER_INFO).jsonMapper(new GsonMcpJsonMapper());
	}

	@Test
	void unknownToolIsRejected() {
		McpAsyncToolRepository repo = emptyTools();
		McpServer server = baseBuilder().feature(tools(repo)).build();
		JSONRPCRequest request = new JSONRPCRequest("tools/call", 1, Map.of("_meta", meta(), "name", "does-not-exist"));

		StepVerifier.create(respond(server, request))
			.assertNext(response -> assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	@Test
	void malformedArgumentsAreInvalidParams() {
		McpAsyncToolRepository repo = ModernTestFixtures
			.tools((ctx, req) -> Mono.just(McpAsyncResponse.result(CallToolResult.builder().build())));
		McpServer server = baseBuilder().feature(tools(repo)).build();
		JSONRPCRequest request = new JSONRPCRequest("tools/call", 1,
				Map.of("_meta", meta(), "name", "echo", "arguments", "not-an-object"));

		StepVerifier.create(respond(server, request))
			.assertNext(response -> assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	private static final Map<String, Object> OUTPUT_SCHEMA = Map.of("type", "object", "required", List.of("n"));

	private static final Tool STRUCTURED_TOOL = Tool.builder("structured", ToolsUtils.EMPTY_JSON_SCHEMA)
		.outputSchema(OUTPUT_SCHEMA)
		.build();

	private static final JSONRPCRequest CALL_STRUCTURED = new JSONRPCRequest("tools/call", 1,
			Map.of("_meta", meta(), "name", "structured"));

	private static JsonSchemaValidator rejecting(Map<String, Object> rejected) {
		return (schema, content) -> schema.equals(rejected) ? ValidationResponse.asInvalid("rejected")
				: ValidationResponse.asValid(null);
	}

	private static McpServer structuredServer(JsonSchemaValidator validator,
			McpAsyncResponse<CallToolOutcome> response) {
		McpAsyncToolRepository repo = new McpAsyncToolRepository() {
			@Override
			public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ToolsPage.of(List.of(STRUCTURED_TOOL)));
			}

			@Override
			public Mono<Tool> find(McpRequestContext ctx, String name) {
				return Mono.just(STRUCTURED_TOOL);
			}

			@Override
			public Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
				return Mono.just(response);
			}
		};
		return baseBuilder()
			.feature(ToolsFeature.ofAsync(repo, new GsonMcpJsonMapper(), validator, 0L, CacheScope.PRIVATE))
			.build();
	}

	@Test
	@SuppressWarnings("unchecked")
	void invalidArgumentsAreAToolExecutionError() {
		AtomicBoolean called = new AtomicBoolean();
		McpAsyncToolRepository repo = ModernTestFixtures.tools((ctx, req) -> {
			called.set(true);
			return Mono.just(McpAsyncResponse.result(CallToolResult.builder().build()));
		});
		McpServer server = baseBuilder()
			.feature(ToolsFeature.ofAsync(repo, new GsonMcpJsonMapper(), rejecting(ToolsUtils.EMPTY_JSON_SCHEMA), 0L,
					CacheScope.PRIVATE))
			.build();
		JSONRPCRequest request = new JSONRPCRequest("tools/call", 1, Map.of("_meta", meta(), "name", "echo"));

		JSONRPCResponse response = respond(server, request).block();

		assertThat(((Map<String, Object>) response.result()).get("isError")).isEqualTo(true);
		assertThat(greetingText(response)).isEqualTo("Invalid arguments: rejected");
		assertThat(called).isFalse();
	}

	@Test
	void nonconformingStructuredContentIsAnInternalError() {
		McpServer server = structuredServer(rejecting(OUTPUT_SCHEMA),
				McpAsyncResponse.result(CallToolResult.builder().structuredContent(Map.of("n", 1)).build()));

		JSONRPCResponse response = respond(server, CALL_STRUCTURED).block();

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void missingStructuredContentIsAnInternalError() {
		McpServer server = structuredServer(PERMISSIVE_VALIDATOR,
				McpAsyncResponse.result(CallToolResult.builder().build()));

		JSONRPCResponse response = respond(server, CALL_STRUCTURED).block();

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void streamingResultIsCheckedAgainstOutputSchema() {
		McpServer server = structuredServer(rejecting(OUTPUT_SCHEMA), McpAsyncResponse
			.streaming(notifier -> Mono.just(CallToolResult.builder().structuredContent(Map.of("n", 1)).build())));

		List<JSONRPCMessage> messages = callStreaming(server.handle(McpTransportContext.EMPTY, CALL_STRUCTURED),
				new ArrayList<>());

		assertThat(((JSONRPCResponse) messages.get(messages.size() - 1)).error().code())
			.isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void structuredContentGetsATextFallback() {
		McpServer server = structuredServer(PERMISSIVE_VALIDATOR,
				McpAsyncResponse.result(CallToolResult.builder().structuredContent(Map.of("n", 1)).build()));

		JSONRPCResponse response = respond(server, CALL_STRUCTURED).block();

		assertThat(greetingText(response)).isEqualTo("{\"n\":1}");
	}

	@Test
	void negativeTtlIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> ToolsFeature.ofAsync(emptyTools(),
				new GsonMcpJsonMapper(), PERMISSIVE_VALIDATOR, -1L, CacheScope.PRIVATE));
		assertThatIllegalArgumentException().isThrownBy(() -> new ToolsPage(List.of(), null, -1L, null));
	}

	private static final ThreadLocal<String> PRINCIPAL = new ThreadLocal<>();

	private static final Tool ADMIN_TOOL = Tool.builder("admin", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static JSONRPCRequest callEcho() {
		Map<String, Object> meta = meta();
		meta.put(MetaKeys.PROGRESS_TOKEN, "t1");
		return new JSONRPCRequest("tools/call", 1, Map.of("_meta", meta, "name", "echo"));
	}

	private static CallToolResult greeting(String principal) {
		return CallToolResult.builder().addContent(TextContent.builder("hello " + principal).build()).build();
	}

	@SuppressWarnings("unchecked")
	private static String greetingText(JSONRPCMessage message) {
		Map<String, Object> result = (Map<String, Object>) ((JSONRPCResponse) message).result();
		List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
		return (String) content.get(0).get("text");
	}

	private static McpSyncToolRepository singleGreetingRepo(Function<McpRequestContext, String> principal,
			AtomicReference<String> handlerThread) {
		return new McpSyncToolRepository() {
			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				return ToolsPage.of(List.of(ECHO_TOOL));
			}

			@Override
			public Tool find(McpRequestContext ctx, String name) {
				return ECHO_TOOL;
			}

			@Override
			public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
				handlerThread.set(Thread.currentThread().getName());
				return McpSyncResponse.result(greeting(principal.apply(ctx)));
			}
		};
	}

	// Records how many messages the consumer had already received when the handler's
	// progress call returned, to tell inline delivery apart from buffering.
	private static McpSyncToolRepository streamingGreetingRepo(Function<McpRequestContext, String> principal,
			AtomicReference<String> handlerThread, List<JSONRPCMessage> delivered,
			AtomicInteger deliveredWhenProgressReturned) {
		return new McpSyncToolRepository() {
			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				return ToolsPage.of(List.of(ECHO_TOOL));
			}

			@Override
			public Tool find(McpRequestContext ctx, String name) {
				return ECHO_TOOL;
			}

			@Override
			public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
				return McpSyncResponse.streaming(notifier -> {
					handlerThread.set(Thread.currentThread().getName());
					notifier.progress(1.0, 1.0, "greeting");
					deliveredWhenProgressReturned.set(delivered.size());
					return greeting(principal.apply(ctx));
				});
			}
		};
	}

	private static JSONRPCResponse callSingle(Mono<McpTransportResponse> invocation) {
		return invocation.map(inv -> ((McpTransportResponse.Result) inv).response()).block();
	}

	private static List<JSONRPCMessage> callStreaming(Mono<McpTransportResponse> invocation,
			List<JSONRPCMessage> delivered) {
		((McpTransportResponse.Streaming) invocation.block()).messages().doOnNext(delivered::add).blockLast();
		return delivered;
	}

	@Test
	void blockingCallerRunsSyncSingleHandlerOnItsOwnThread() {
		AtomicReference<String> handlerThread = new AtomicReference<>();
		McpServer server = baseBuilder().feature(tools(singleGreetingRepo(c -> PRINCIPAL.get(), handlerThread)))
			.build();

		PRINCIPAL.set("alice");
		JSONRPCResponse response;
		try {
			response = callSingle(server.handleBlocking(McpTransportContext.EMPTY, callEcho()));
		}
		finally {
			PRINCIPAL.remove();
		}

		assertThat(handlerThread.get()).isEqualTo(Thread.currentThread().getName());
		assertThat(greetingText(response)).isEqualTo("hello alice");
	}

	@Test
	void blockingCallerRunsSyncStreamingHandlerOnItsOwnThreadAndReceivesNotificationsInline() {
		AtomicReference<String> handlerThread = new AtomicReference<>();
		List<JSONRPCMessage> delivered = new CopyOnWriteArrayList<>();
		AtomicInteger deliveredWhenProgressReturned = new AtomicInteger(-1);
		McpServer server = baseBuilder()
			.feature(tools(streamingGreetingRepo(c -> PRINCIPAL.get(), handlerThread, delivered,
					deliveredWhenProgressReturned)))
			.build();

		PRINCIPAL.set("alice");
		try {
			callStreaming(server.handleBlocking(McpTransportContext.EMPTY, callEcho()), delivered);
		}
		finally {
			PRINCIPAL.remove();
		}

		assertThat(handlerThread.get()).isEqualTo(Thread.currentThread().getName());
		assertThat(deliveredWhenProgressReturned.get()).isEqualTo(1);
		assertThat(delivered).hasSize(2);
		assertThat(delivered.get(0)).isInstanceOf(JSONRPCNotification.class);
		assertThat(greetingText(delivered.get(1))).isEqualTo("hello alice");
	}

	@Test
	@SuppressWarnings("unchecked")
	void blockingCallerRunsSyncListOnItsOwnThread() {
		McpSyncToolRepository repo = new McpSyncToolRepository() {
			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				return ToolsPage
					.of("admin".equals(PRINCIPAL.get()) ? List.of(ECHO_TOOL, ADMIN_TOOL) : List.of(ECHO_TOOL));
			}

			@Override
			public Tool find(McpRequestContext ctx, String name) {
				return null;
			}

			@Override
			public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
				throw McpException.invalidParams("Unknown tool: " + request.name());
			}
		};
		McpServer server = baseBuilder().feature(tools(repo)).build();
		JSONRPCRequest listTools = new JSONRPCRequest("tools/list", 1, Map.of("_meta", meta()));

		PRINCIPAL.set("admin");
		JSONRPCResponse asAdmin;
		JSONRPCResponse asAnonymous;
		try {
			asAdmin = callSingle(server.handleBlocking(McpTransportContext.EMPTY, listTools));
			PRINCIPAL.remove();
			asAnonymous = callSingle(server.handleBlocking(McpTransportContext.EMPTY, listTools));
		}
		finally {
			PRINCIPAL.remove();
		}

		assertThat((List<Object>) ((Map<String, Object>) asAdmin.result()).get("tools")).hasSize(2);
		assertThat((List<Object>) ((Map<String, Object>) asAnonymous.result()).get("tools")).hasSize(1);
	}

	@Test
	void nonBlockingCallerOffloadsSyncSingleHandler() {
		AtomicReference<String> handlerThread = new AtomicReference<>();
		McpServer server = baseBuilder().feature(tools(singleGreetingRepo(c -> PRINCIPAL.get(), handlerThread)))
			.build();

		PRINCIPAL.set("alice");
		JSONRPCResponse response;
		try {
			response = callSingle(server.handle(McpTransportContext.EMPTY, callEcho()));
		}
		finally {
			PRINCIPAL.remove();
		}

		assertThat(handlerThread.get()).startsWith("boundedElastic-");
		assertThat(greetingText(response)).isEqualTo("hello null");
	}

	@Test
	void nonBlockingCallerOffloadsSyncStreamingHandler() {
		AtomicReference<String> handlerThread = new AtomicReference<>();
		List<JSONRPCMessage> delivered = new CopyOnWriteArrayList<>();
		McpServer server = baseBuilder()
			.feature(tools(streamingGreetingRepo(c -> PRINCIPAL.get(), handlerThread, delivered, new AtomicInteger())))
			.build();

		PRINCIPAL.set("alice");
		try {
			callStreaming(server.handle(McpTransportContext.EMPTY, callEcho()), delivered);
		}
		finally {
			PRINCIPAL.remove();
		}

		assertThat(handlerThread.get()).startsWith("boundedElastic-");
		assertThat(delivered).hasSize(2);
		assertThat(greetingText(delivered.get(1))).isEqualTo("hello null");
	}

	@Test
	void offloadedSyncHandlerSeesPrincipalCapturedInTransportContext() {
		AtomicReference<String> handlerThread = new AtomicReference<>();
		McpServer server = baseBuilder()
			.feature(tools(singleGreetingRepo(c -> (String) c.transportContext().get("principal"), handlerThread)))
			.build();
		McpTransportContext transportContext = McpTransportContext.create(Map.of("principal", "alice"));

		JSONRPCResponse response = callSingle(server.handle(transportContext, callEcho()));

		assertThat(handlerThread.get()).startsWith("boundedElastic-");
		assertThat(greetingText(response)).isEqualTo("hello alice");
	}

}
