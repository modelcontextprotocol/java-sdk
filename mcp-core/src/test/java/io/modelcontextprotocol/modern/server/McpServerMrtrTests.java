/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.InputRequest;
import io.modelcontextprotocol.modern.McpSchema.InputRequired;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.server.feature.McpAsyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesPage;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.PERMISSIVE_VALIDATOR;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.invoke;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.tools;
import static org.assertj.core.api.Assertions.assertThat;

class McpServerMrtrTests {

	private static final ElicitFormRequest CONFIRM = ElicitFormRequest.builder("Confirm?", Map.of("type", "object"))
		.build();

	private static McpServer.Builder baseBuilder() {
		return McpServer.builder().serverInfo(SERVER_INFO).jsonMapper(new GsonMcpJsonMapper());
	}

	private static Map<String, Object> metaWithElicitation() {
		Map<String, Object> meta = meta();
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of("elicitation", Map.of("form", Map.of())));
		return meta;
	}

	private static JSONRPCRequest toolCall(Object id, Map<String, Object> meta, Object requestState) {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta);
		params.put("name", "echo");
		if (requestState != null) {
			params.put("requestState", requestState);
		}
		return new JSONRPCRequest("tools/call", id, params);
	}

	private static McpServer serverRecordingState(AtomicReference<String> seenRequestState) {
		return baseBuilder().feature(ToolsFeature.ofAsync(tools((ctx, req) -> {
			if (req.requestState() != null) {
				seenRequestState.set(req.requestState());
				return Mono.just(McpAsyncResponse.result(CallToolResult.builder().build()));
			}
			return Mono.just(McpAsyncResponse
				.result(InputRequiredResult.builder().elicit("q1", CONFIRM).requestState("secret-plaintext").build()));
		}), new GsonMcpJsonMapper(), PERMISSIVE_VALIDATOR, 0L, CacheScope.PRIVATE)).build();
	}

	@Test
	void requestStateIsSealedOnTheWireAndOpenedOnRetry() {
		AtomicReference<String> seenRequestState = new AtomicReference<>();
		McpServer server = serverRecordingState(seenRequestState);

		JSONRPCResponse first = respond(server, toolCall(1, metaWithElicitation(), null)).block();
		@SuppressWarnings("unchecked")
		String sealed = (String) ((Map<String, Object>) first.result()).get("requestState");
		assertThat(sealed).isNotNull().isNotEqualTo("secret-plaintext");

		JSONRPCResponse retry = respond(server, toolCall(2, metaWithElicitation(), sealed)).block();

		assertThat(retry.error()).isNull();
		assertThat(seenRequestState.get()).isEqualTo("secret-plaintext");
	}

	@Test
	void tamperedRequestStateIsRejectedBeforeTheHandler() {
		AtomicReference<String> seenRequestState = new AtomicReference<>();
		McpServer server = serverRecordingState(seenRequestState);

		McpTransportResponse invocation = invoke(server, toolCall(1, metaWithElicitation(), "forged")).block();

		assertThat(invocation).isInstanceOfSatisfying(McpTransportResponse.Error.class,
				error -> assertThat(error.response().error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS));
		assertThat(seenRequestState.get()).isNull();
	}

	@Test
	void nonStringRequestStateIsRejectedBeforeTheHandler() {
		AtomicReference<String> seenRequestState = new AtomicReference<>();
		McpServer server = serverRecordingState(seenRequestState);

		JSONRPCResponse response = respond(server, toolCall(1, metaWithElicitation(), 42)).block();

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(seenRequestState.get()).isNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void malformedInputResponsesAreRejectedBeforeTheHandler() {
		AtomicReference<String> seenRequestState = new AtomicReference<>();
		McpServer server = serverRecordingState(seenRequestState);

		for (Object inputResponses : List.of("not-an-object", Map.of("q1", "accept"))) {
			JSONRPCRequest request = toolCall(1, metaWithElicitation(), null);
			((Map<String, Object>) request.params()).put("inputResponses", inputResponses);

			JSONRPCResponse response = respond(server, request).block();

			assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		}
		assertThat(seenRequestState.get()).isNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void unparseableInputResponseIsInvalidParams() {
		McpServer server = baseBuilder().feature(ToolsFeature.ofAsync(tools((ctx, req) -> {
			InputResponses.get(req.inputResponses(), "q1", ElicitAnswer.class, new GsonMcpJsonMapper());
			return Mono.just(McpAsyncResponse.result(CallToolResult.builder().build()));
		}), new GsonMcpJsonMapper(), PERMISSIVE_VALIDATOR, 0L, CacheScope.PRIVATE)).build();
		JSONRPCRequest request = toolCall(1, metaWithElicitation(), null);
		((Map<String, Object>) request.params()).put("inputResponses",
				Map.of("q1", Map.of("action", "accept", "content", "not-an-object")));

		JSONRPCResponse response = respond(server, request).block();

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(response.error().message()).isEqualTo("Malformed inputResponses['q1']");
	}

	record ElicitAnswer(String action, Map<String, Object> content) {
	}

	@Test
	void inputRequestsAreNotCheckedAgainstClientCapabilities() {
		// Checking capabilities is the handler's job; the server sends what it is given.
		McpServer server = baseBuilder()
			.feature(
					ToolsFeature.ofAsync(
							tools((ctx,
									req) -> Mono.just(McpAsyncResponse
										.result(InputRequiredResult.builder().elicit("q1", CONFIRM).build()))),
							new GsonMcpJsonMapper(), PERMISSIVE_VALIDATOR, 0L, CacheScope.PRIVATE))
			.build();

		JSONRPCResponse response = respond(server, toolCall(1, meta(), null)).block();

		assertThat(response.error()).isNull();
	}

	@Test
	void retryResultIsMarkedUncacheable() {
		McpAsyncResourceRepository repo = new McpAsyncResourceRepository() {
			@Override
			public Mono<ResourcesPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ResourcesPage.of(List.of()));
			}

			@Override
			public Mono<McpAsyncResponse<McpSchema.ReadResourceOutcome>> read(McpRequestContext ctx,
					McpSchema.ReadResourceRequest request) {
				return Mono.just(McpAsyncResponse.result(ReadResourceResult
					.builder(List.of(new TextResourceContents(request.uri(), "text/plain", "content", null)))
					.ttlMs(60_000L)
					.cacheScope(CacheScope.PUBLIC)
					.build()));
			}
		};
		McpServer server = baseBuilder()
			.feature(ResourcesFeature.ofAsync(repo, new GsonMcpJsonMapper(), 0L, CacheScope.PRIVATE))
			.build();

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("uri", "file:///a.txt");
		params.put("inputResponses", Map.of("q1", Map.of("action", "accept")));
		JSONRPCResponse response = respond(server, new JSONRPCRequest("resources/read", 1, params)).block();

		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) response.result();
		assertThat(((Number) result.get("ttlMs")).longValue()).isZero();
		// Gson ignores @JsonProperty on enums, so compare case-insensitively.
		assertThat((String) result.get("cacheScope")).isEqualToIgnoringCase("private");
	}

	@Test
	@SuppressWarnings("unchecked")
	void resourceRequestStateIsBoundToTheUriNotAStrayName() {
		AtomicReference<String> seenRequestState = new AtomicReference<>();
		McpAsyncResourceRepository repo = new McpAsyncResourceRepository() {
			@Override
			public Mono<ResourcesPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ResourcesPage.of(List.of()));
			}

			@Override
			public Mono<McpAsyncResponse<McpSchema.ReadResourceOutcome>> read(McpRequestContext ctx,
					McpSchema.ReadResourceRequest request) {
				if (request.requestState() != null) {
					seenRequestState.set(request.requestState());
					return Mono.just(McpAsyncResponse.result(ReadResourceResult.builder(List.of()).build()));
				}
				return Mono.just(McpAsyncResponse
					.result(InputRequiredResult.builder().elicit("q1", CONFIRM).requestState("for-a").build()));
			}
		};
		McpServer server = baseBuilder()
			.feature(ResourcesFeature.ofAsync(repo, new GsonMcpJsonMapper(), 0L, CacheScope.PRIVATE))
			.build();

		Map<String, Object> first = new HashMap<>();
		first.put("_meta", metaWithElicitation());
		first.put("uri", "file:///a.txt");
		JSONRPCResponse sealedResponse = respond(server, new JSONRPCRequest("resources/read", 1, first)).block();
		String sealed = (String) ((Map<String, Object>) sealedResponse.result()).get("requestState");

		Map<String, Object> replay = new HashMap<>();
		replay.put("_meta", metaWithElicitation());
		replay.put("uri", "file:///b.txt");
		replay.put("name", "file:///a.txt");
		replay.put("requestState", sealed);
		JSONRPCResponse response = respond(server, new JSONRPCRequest("resources/read", 2, replay)).block();

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(seenRequestState.get()).isNull();
	}

	// An extension method's own input-required record, as an extension would declare it.
	record ExtensionInputRequired(Map<String, InputRequest> inputRequests, String requestState, String resultType,
			Map<String, Object> meta) implements InputRequired {
	}

	@Test
	void extensionInputRequiredGetsTheSameStateIntegrity() {
		AtomicReference<Object> seenParams = new AtomicReference<>();
		McpFeature extension = new McpFeature() {
			@Override
			public Set<String> methods() {
				return Set.of("com.example/run");
			}

			@Override
			public Set<String> inputRequiredMethods() {
				return Set.of("com.example/run");
			}

			@Override
			public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
				if (ctx.isRetry()) {
					seenParams.set(params);
					return Mono.just(McpAsyncResponse
						.result(CallToolResult.builder().addContent(TextContent.builder("done").build()).build()));
				}
				return Mono.just(McpAsyncResponse.result(new ExtensionInputRequired(null, "extension-state",
						McpSchema.ResultType.INPUT_REQUIRED, null)));
			}
		};
		McpServer server = baseBuilder().feature(extension).build();

		JSONRPCResponse first = respond(server, new JSONRPCRequest("com.example/run", 1, Map.of("_meta", meta())))
			.block();
		@SuppressWarnings("unchecked")
		String sealed = (String) ((Map<String, Object>) first.result()).get("requestState");
		assertThat(sealed).isNotNull().isNotEqualTo("extension-state");

		respond(server, new JSONRPCRequest("com.example/run", 2, Map.of("_meta", meta(), "requestState", sealed)))
			.block();

		assertThat(seenParams.get()).isInstanceOfSatisfying(Map.class,
				params -> assertThat(params.get("requestState")).isEqualTo("extension-state"));
	}

}
