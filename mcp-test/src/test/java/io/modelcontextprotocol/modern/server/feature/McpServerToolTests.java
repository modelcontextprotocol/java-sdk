/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.PERMISSIVE_VALIDATOR;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpServerToolTests {

	private static final Tool ECHO = Tool.builder("echo", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static McpServer server(McpServerTool tool) {
		return McpServer.builder().serverInfo(SERVER_INFO).tool(tool).build();
	}

	private static JSONRPCRequest callEcho() {
		return new JSONRPCRequest("tools/call", 1,
				Map.of("_meta", meta(MetaKeys.PROGRESS_TOKEN, "t1"), "name", "echo"));
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	private static JSONRPCResponse callSingle(Mono<McpTransportResponse> invocation) {
		return invocation.map(inv -> ((McpTransportResponse.Result) inv).response()).block();
	}

	private static List<JSONRPCMessage> callStreaming(Mono<McpTransportResponse> invocation) {
		List<JSONRPCMessage> delivered = new CopyOnWriteArrayList<>();
		((McpTransportResponse.Streaming) invocation.block()).messages().doOnNext(delivered::add).blockLast();
		return delivered;
	}

	@SuppressWarnings("unchecked")
	private static String text(JSONRPCMessage message) {
		Map<String, Object> result = (Map<String, Object>) ((JSONRPCResponse) message).result();
		List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
		return (String) content.get(0).get("text");
	}

	private static int errorCode(McpServer server, JSONRPCRequest request) {
		return respond(server, request).block().error().code();
	}

	@Test
	void nullToolIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> McpServerTool.sync(null, (ctx, request) -> text("")))
			.withMessage("tool must not be null");
	}

	@Test
	void nullHandlerIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> McpServerTool.sync(ECHO, null));
		assertThatIllegalArgumentException().isThrownBy(() -> McpServerTool.syncStreaming(ECHO, null));
		assertThatIllegalArgumentException().isThrownBy(() -> McpServerTool.async(ECHO, null));
		assertThatIllegalArgumentException().isThrownBy(() -> McpServerTool.asyncStreaming(ECHO, null));
	}

	@Test
	void invalidToolNameIsRejected() {
		Tool invalid = Tool.builder("has spaces", ToolsUtils.EMPTY_JSON_SCHEMA).build();

		assertThatIllegalArgumentException().isThrownBy(() -> McpServerTool.sync(invalid, (ctx, request) -> text("")))
			.withMessageContaining("has spaces");
	}

	@Test
	void toStringNamesTheTool() {
		assertThat(McpServerTool.sync(ECHO, (ctx, request) -> text(""))).hasToString("McpServerTool[echo]");
	}

	@Test
	void blockingCallerRunsSyncHandlerOnItsOwnThread() {
		AtomicReference<String> handlerThread = new AtomicReference<>();
		McpServer server = server(McpServerTool.sync(ECHO, (ctx, request) -> {
			handlerThread.set(Thread.currentThread().getName());
			return text("hello");
		}));

		JSONRPCResponse response = callSingle(server.handleBlocking(McpTransportContext.EMPTY, callEcho()));

		assertThat(handlerThread.get()).isEqualTo(Thread.currentThread().getName());
		assertThat(text(response)).isEqualTo("hello");
	}

	@Test
	void nonBlockingCallerOffloadsSyncHandler() {
		AtomicReference<String> handlerThread = new AtomicReference<>();
		McpServer server = server(McpServerTool.sync(ECHO, (ctx, request) -> {
			handlerThread.set(Thread.currentThread().getName());
			return text("hello");
		}));

		JSONRPCResponse response = callSingle(server.handle(McpTransportContext.EMPTY, callEcho()));

		assertThat(handlerThread.get()).startsWith("boundedElastic-");
		assertThat(text(response)).isEqualTo("hello");
	}

	@Test
	void blockingCallerRunsSyncStreamingHandlerOnItsOwnThread() {
		AtomicReference<String> handlerThread = new AtomicReference<>();
		McpServer server = server(McpServerTool.syncStreaming(ECHO, (ctx, request, notifier) -> {
			handlerThread.set(Thread.currentThread().getName());
			notifier.progress(1.0, 1.0, "halfway");
			return text("hello");
		}));

		List<JSONRPCMessage> messages = callStreaming(server.handleBlocking(McpTransportContext.EMPTY, callEcho()));

		assertThat(handlerThread.get()).isEqualTo(Thread.currentThread().getName());
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0)).isInstanceOf(JSONRPCNotification.class);
		assertThat(text(messages.get(1))).isEqualTo("hello");
	}

	@Test
	void nonBlockingCallerOffloadsSyncStreamingHandler() {
		AtomicReference<String> handlerThread = new AtomicReference<>();
		McpServer server = server(McpServerTool.syncStreaming(ECHO, (ctx, request, notifier) -> {
			handlerThread.set(Thread.currentThread().getName());
			return text("hello");
		}));

		List<JSONRPCMessage> messages = callStreaming(server.handle(McpTransportContext.EMPTY, callEcho()));

		assertThat(handlerThread.get()).startsWith("boundedElastic-");
		assertThat(text(messages.get(messages.size() - 1))).isEqualTo("hello");
	}

	@Test
	void asyncHandlerAnswersTheCall() {
		McpServer server = server(McpServerTool.async(ECHO, (ctx, request) -> Mono.just(text("hello"))));

		assertThat(text(callSingle(server.handle(McpTransportContext.EMPTY, callEcho())))).isEqualTo("hello");
	}

	@Test
	void asyncStreamingHandlerSendsNotificationsBeforeTheResult() {
		McpServer server = server(McpServerTool.asyncStreaming(ECHO,
				(ctx, request, notifier) -> notifier.progress(1.0, 1.0, "halfway").thenReturn(text("hello"))));

		List<JSONRPCMessage> messages = callStreaming(server.handle(McpTransportContext.EMPTY, callEcho()));

		assertThat(messages).hasSize(2);
		assertThat(messages.get(0)).isInstanceOf(JSONRPCNotification.class);
		assertThat(text(messages.get(1))).isEqualTo("hello");
	}

	@Test
	@SuppressWarnings("unchecked")
	void handlerMayAskForInput() {
		ElicitFormRequest askName = ElicitFormRequest
			.builder("What is your name?", Map.of("type", "object", "properties", Map.of()))
			.build();
		McpServer server = server(McpServerTool.sync(ECHO,
				(ctx, request) -> InputRequiredResult.builder().elicit("name", askName).build()));

		Map<String, Object> result = (Map<String, Object>) respond(server, callEcho()).block().result();

		assertThat(result).containsKey("inputRequests");
	}

	@Test
	void thrownMcpExceptionIsAnsweredWithItsError() {
		McpServer server = server(McpServerTool.sync(ECHO, (ctx, request) -> {
			throw McpException.invalidParams("bad");
		}));

		assertThat(errorCode(server, callEcho())).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void signalledMcpExceptionIsAnsweredWithItsError() {
		McpServer server = server(
				McpServerTool.async(ECHO, (ctx, request) -> Mono.error(McpException.invalidParams("bad"))));

		assertThat(errorCode(server, callEcho())).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void otherExceptionIsAnInternalError() {
		McpServer server = server(McpServerTool.sync(ECHO, (ctx, request) -> {
			throw new IllegalStateException("boom");
		}));

		assertThat(errorCode(server, callEcho())).isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void nullResultIsAnInternalError() {
		McpServer server = server(McpServerTool.sync(ECHO, (ctx, request) -> null));

		assertThat(errorCode(server, callEcho())).isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void missingStructuredContentIsAnInternalError() {
		Tool structured = Tool.builder("echo", ToolsUtils.EMPTY_JSON_SCHEMA)
			.outputSchema(Map.of("type", "object"))
			.build();
		McpServer server = server(McpServerTool.sync(structured, (ctx, request) -> text("no structured content")));

		assertThat(errorCode(server, callEcho())).isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	private static McpServer ofTools(List<McpServerTool> tools) {
		return McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ToolsFeature.ofTools(tools, McpJsonDefaults.getMapper(), PERMISSIVE_VALIDATOR, 60_000L,
					CacheScope.PUBLIC))
			.build();
	}

	private static McpServerTool named(String name) {
		return McpServerTool.sync(Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build(),
				(ctx, request) -> text(name));
	}

	@Test
	@SuppressWarnings("unchecked")
	void ofToolsListsToolsInOrderWithTheGivenCacheHints() {
		McpServer server = ofTools(List.of(named("b"), named("a")));

		Map<String, Object> result = (Map<String, Object>) respond(server,
				new JSONRPCRequest("tools/list", 1, Map.of("_meta", meta())))
			.block()
			.result();

		assertThat((List<Map<String, Object>>) result.get("tools")).extracting(tool -> tool.get("name"))
			.containsExactly("b", "a");
		assertThat(((Number) result.get("ttlMs")).longValue()).isEqualTo(60_000L);
		assertThat(result.get("cacheScope"))
			.isEqualTo(McpJsonDefaults.getMapper().convertValue(CacheScope.PUBLIC, String.class));
	}

	@Test
	void ofToolsRejectsACursor() {
		JSONRPCRequest request = new JSONRPCRequest("tools/list", 1, Map.of("_meta", meta(), "cursor", "next"));

		assertThat(errorCode(ofTools(List.of(named("a"))), request)).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void ofToolsRejectsAnUnknownTool() {
		assertThat(errorCode(ofTools(List.of(named("a"))), callEcho())).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void ofToolsRejectsDuplicateNames() {
		assertThatIllegalArgumentException().isThrownBy(() -> ofTools(List.of(named("a"), named("a"))))
			.withMessage("Duplicate tool name: a");
	}

	@Test
	void ofToolsRejectsNulls() {
		assertThatIllegalArgumentException().isThrownBy(() -> ofTools(null));
		assertThatIllegalArgumentException().isThrownBy(() -> ofTools(Collections.singletonList(null)));
	}

}
