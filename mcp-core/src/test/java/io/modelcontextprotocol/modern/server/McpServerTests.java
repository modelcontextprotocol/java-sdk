/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.invoke;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerTests {

	private static McpServer.Builder baseBuilder() {
		return McpServer.builder().serverInfo(SERVER_INFO).jsonMapper(new GsonMcpJsonMapper());
	}

	private static McpFeature feature(String method,
			BiFunction<McpRequestContext, Object, Mono<? extends McpAsyncResponse<? extends Result>>> handler) {
		return new McpFeature() {
			@Override
			public Set<String> methods() {
				return Set.of(method);
			}

			@Override
			public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
				return handler.apply(ctx, params);
			}
		};
	}

	private static CallToolResult ok() {
		return CallToolResult.builder().addContent(TextContent.builder("ok").build()).build();
	}

	private static McpFeature echoFeature(String method) {
		return feature(method, (ctx, params) -> Mono.just(McpAsyncResponse.result(ok())));
	}

	private static JSONRPCRequest request(String method, Map<String, Object> meta) {
		return new JSONRPCRequest(method, 1, Map.of("_meta", meta));
	}

	@Test
	void missingMetaIsRejected() {
		McpServer server = baseBuilder().build();

		StepVerifier.create(invoke(server, new JSONRPCRequest("tools/list", 1, Map.of())))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	@Test
	void missingClientCapabilitiesIsRejected() {
		McpServer server = baseBuilder().build();
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);

		StepVerifier.create(invoke(server, request("tools/list", meta)))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	@Test
	void unsupportedVersionIsRejected() {
		McpServer server = baseBuilder().build();

		StepVerifier.create(invoke(server, request("tools/list", meta(MetaKeys.PROTOCOL_VERSION, "1999-01-01"))))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION))
			.verifyComplete();
	}

	@Test
	void unsupportedVersionWinsOverMissingClientCapabilities() {
		McpServer server = baseBuilder().build();
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, "2099-01-01");

		StepVerifier.create(invoke(server, request("tools/list", meta)))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION))
			.verifyComplete();
	}

	@Test
	void malformedClientCapabilitiesIsInvalidParams() {
		McpServer server = baseBuilder().feature(echoFeature("tools/call")).build();

		StepVerifier.create(invoke(server, request("tools/call", meta(MetaKeys.CLIENT_CAPABILITIES, "bogus"))))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	@Test
	void nonObjectParamsIsInvalidParams() {
		McpServer server = baseBuilder().feature(echoFeature("tools/call")).build();

		StepVerifier.create(invoke(server, new JSONRPCRequest("tools/call", 1, List.of(1))))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	@Test
	void unknownMethodIsRejected() {
		McpServer server = baseBuilder().build();

		StepVerifier.create(invoke(server, request("does/not/exist", meta())))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.METHOD_NOT_FOUND))
			.verifyComplete();
	}

	@Test
	void logLevelIsIgnored() {
		McpServer server = baseBuilder().feature(echoFeature("tools/call")).build();

		StepVerifier.create(respond(server, request("tools/call", meta(MetaKeys.LOG_LEVEL, "not-a-level"))))
			.assertNext(response -> assertThat(response.error()).isNull())
			.verifyComplete();
	}

	@Test
	void signalledMcpExceptionIsAnsweredWithItsError() {
		McpServer server = baseBuilder()
			.feature(feature("tools/call",
					(ctx, params) -> Mono.error(new McpException(-32000, "Quota exceeded", Map.of("retryAfter", 5)))))
			.build();

		StepVerifier.create(invoke(server, request("tools/call", meta())))
			.assertNext(invocation -> assertThat(invocation).isInstanceOfSatisfying(McpTransportResponse.Error.class,
					error -> assertThat(error.response().error())
						.isEqualTo(new JSONRPCError(-32000, "Quota exceeded", Map.of("retryAfter", 5)))))
			.verifyComplete();
	}

	@Test
	void thrownMcpExceptionIsAnsweredWithItsError() {
		McpServer server = baseBuilder().feature(feature("tools/call", (ctx, params) -> {
			throw McpException.invalidParams("bad arguments");
		})).build();

		StepVerifier.create(invoke(server, request("tools/call", meta())))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	@Test
	void handlerExceptionBecomesInternalError() {
		McpServer server = baseBuilder()
			.feature(feature("tools/call", (ctx, params) -> Mono.error(new RuntimeException("boom"))))
			.build();

		StepVerifier.create(invoke(server, request("tools/call", meta()))).assertNext(invocation -> {
			assertError(invocation, ErrorCodes.INTERNAL_ERROR);
			assertThat(((McpTransportResponse.Error) invocation).response().error().message()).doesNotContain("boom");
		}).verifyComplete();
	}

	@Test
	void handlerThrowingSynchronouslyBecomesInternalError() {
		McpServer server = baseBuilder().feature(feature("tools/call", (ctx, params) -> {
			throw new IllegalStateException("boom");
		})).build();

		StepVerifier.create(invoke(server, request("tools/call", meta())))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.INTERNAL_ERROR))
			.verifyComplete();
	}

	@Test
	void resultWithoutResultTypeIsInternalError() {
		Result untyped = new Result() {
			@Override
			public String resultType() {
				return null;
			}

			@Override
			public Map<String, Object> meta() {
				return null;
			}
		};
		McpServer server = baseBuilder()
			.feature(feature("tools/call", (ctx, params) -> Mono.just(McpAsyncResponse.<Result>result(untyped))))
			.build();

		StepVerifier.create(invoke(server, request("tools/call", meta())))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.INTERNAL_ERROR))
			.verifyComplete();
	}

	@Test
	void inputRequiredFromUndeclaredMethodBecomesInternalError() {
		McpServer server = baseBuilder()
			.feature(feature("resources/list",
					(ctx, params) -> Mono
						.just(McpAsyncResponse.result(InputRequiredResult.builder().requestState("s").build()))))
			.build();

		StepVerifier.create(invoke(server, request("resources/list", meta())))
			.assertNext(invocation -> assertError(invocation, ErrorCodes.INTERNAL_ERROR))
			.verifyComplete();
	}

	@Test
	void negativeDiscoverTtlIsRejected() {
		assertThatThrownBy(() -> baseBuilder().discoverCache(-1, McpSchema.CacheScope.PRIVATE))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void serverInfoIsStampedOnEveryResult() {
		McpServer server = baseBuilder().feature(echoFeature("tools/call")).build();

		StepVerifier.create(respond(server, request("tools/call", meta()))).assertNext(response -> {
			@SuppressWarnings("unchecked")
			Map<String, Object> result = (Map<String, Object>) response.result();
			@SuppressWarnings("unchecked")
			Map<String, Object> meta = (Map<String, Object>) result.get("_meta");
			assertThat(meta).containsKey(MetaKeys.SERVER_INFO);
		}).verifyComplete();
	}

	@Test
	void discoverReturnsAggregatedCapabilities() {
		McpFeature toolsCapability = new McpFeature() {
			@Override
			public Set<String> methods() {
				return Set.of();
			}

			@Override
			public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
				return Mono.empty();
			}

			@Override
			public void capabilities(ServerCapabilities.Builder builder) {
				builder.tools(false);
			}
		};
		McpServer server = baseBuilder().feature(toolsCapability).build();

		StepVerifier.create(respond(server, request("server/discover", meta()))).assertNext(response -> {
			@SuppressWarnings("unchecked")
			Map<String, Object> result = (Map<String, Object>) response.result();
			assertThat(result.get("supportedVersions")).isEqualTo(List.of(McpSchema.LATEST_PROTOCOL_VERSION));
			assertThat(result.get("capabilities")).isNotNull();
		}).verifyComplete();
	}

	@Test
	void streamingBodyRunsOnlyOnceTheStreamIsSubscribed() {
		AtomicBoolean ran = new AtomicBoolean();
		McpServer server = baseBuilder()
			.feature(feature("tools/call", (ctx, params) -> Mono.just(McpAsyncResponse.streaming(notifier -> {
				ran.set(true);
				return Mono.just(ok());
			}))))
			.build();

		McpTransportResponse invocation = invoke(server, request("tools/call", meta())).block();

		assertThat(invocation).isInstanceOf(McpTransportResponse.Streaming.class);
		assertThat(ran).isFalse();
		StepVerifier.create(((McpTransportResponse.Streaming) invocation).messages())
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse response && response.error() == null)
			.verifyComplete();
		assertThat(ran).isTrue();
	}

	@Test
	void streamingBodyNotificationsPrecedeTheResult() {
		McpServer server = baseBuilder().feature(feature("tools/call",
				(ctx, params) -> Mono.just(McpAsyncResponse.streaming(notifier -> notifier.progress(1.0, 2.0, "half")
					.then(notifier.progress(2.0, 2.0, "done"))
					.thenReturn(ok())))))
			.build();

		McpTransportResponse invocation = invoke(server, request("tools/call", meta(MetaKeys.PROGRESS_TOKEN, "tok-1")))
			.block();

		StepVerifier.create(((McpTransportResponse.Streaming) invocation).messages())
			.expectNextMatches(msg -> msg instanceof JSONRPCNotification)
			.expectNextMatches(msg -> msg instanceof JSONRPCNotification)
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.verifyComplete();
	}

	@Test
	void progressIsSuppressedWithoutProgressToken() {
		McpServer server = baseBuilder()
			.feature(feature("tools/call",
					(ctx, params) -> Mono.just(McpAsyncResponse
						.streaming(notifier -> notifier.progress(1.0, null, null).thenReturn(ok())))))
			.build();

		McpTransportResponse invocation = invoke(server, request("tools/call", meta())).block();

		StepVerifier.create(((McpTransportResponse.Streaming) invocation).messages())
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse)
			.verifyComplete();
	}

	@Test
	void streamingBodyExceptionIsAnInternalErrorInStream() {
		McpServer server = baseBuilder()
			.feature(feature("tools/call",
					(ctx, params) -> Mono
						.just(McpAsyncResponse.streaming(notifier -> Mono.error(new IllegalStateException("boom"))))))
			.build();

		McpTransportResponse invocation = invoke(server, request("tools/call", meta())).block();

		StepVerifier.create(((McpTransportResponse.Streaming) invocation).messages())
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse response
					&& response.error().code() == ErrorCodes.INTERNAL_ERROR)
			.verifyComplete();
	}

	@Test
	void streamingBodyMcpExceptionIsItsErrorInStream() {
		McpServer server = baseBuilder()
			.feature(
					feature("tools/call",
							(ctx, params) -> Mono.just(McpAsyncResponse
								.streaming(notifier -> Mono.error(McpException.invalidParams("bad arguments"))))))
			.build();

		McpTransportResponse invocation = invoke(server, request("tools/call", meta())).block();

		StepVerifier.create(((McpTransportResponse.Streaming) invocation).messages())
			.expectNextMatches(msg -> msg instanceof JSONRPCResponse response
					&& response.error().code() == ErrorCodes.INVALID_PARAMS)
			.verifyComplete();
	}

	@Test
	void methodServedByTwoFeaturesIsRejectedAtBuild() {
		McpServer.Builder builder = baseBuilder().feature(echoFeature("tools/call")).feature(echoFeature("tools/call"));

		assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class).hasMessageContaining("tools/call");
	}

	@Test
	void featureCannotClaimDiscover() {
		McpServer.Builder builder = baseBuilder().feature(echoFeature("server/discover"));

		assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("server/discover");
	}

	private static void assertError(McpTransportResponse response, int code) {
		assertThat(response).isInstanceOfSatisfying(McpTransportResponse.Error.class,
				error -> assertThat(error.response().error().code()).isEqualTo(code));
	}

}
