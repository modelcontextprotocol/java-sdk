/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.modern.McpSchema.UnsupportedProtocolVersionData;
import io.modelcontextprotocol.modern.client.transport.McpClientTransport;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How {@link McpAsyncClient} treats answers a well-behaved server doesn't give, from a
 * transport that answers as scripted.
 */
class McpAsyncClientResponseTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final List<McpTransportRequest> requests = new CopyOnWriteArrayList<>();

	private McpAsyncClient client(Function<McpTransportRequest, Flux<JSONRPCMessage>> answer) {
		McpClientTransport transport = request -> request.withRoundTimeout(Flux.defer(() -> {
			this.requests.add(request);
			return answer.apply(request);
		}));
		return McpClient.async(transport).roundTimeout(Duration.ofMillis(200)).build();
	}

	private static Flux<JSONRPCMessage> result(McpTransportRequest request, Object result) {
		return Flux.just(JSONRPCResponse.result(request.message().id(), result));
	}

	private static Flux<JSONRPCMessage> error(McpTransportRequest request, JSONRPCError error) {
		return Flux.just(JSONRPCResponse.error(request.message().id(), error));
	}

	private static Map<String, Object> textResult(String resultType) {
		Map<String, Object> result = new HashMap<>();
		result.put("content", List.of(Map.of("type", "text", "text", "done")));
		if (resultType != null) {
			result.put("resultType", resultType);
		}
		return result;
	}

	@Test
	void missingResultTypeMeansComplete() {
		McpAsyncClient client = client(request -> result(request, textResult(null)));

		CallToolResult result = client.tool("t").call().block(TIMEOUT);

		assertThat(result.resultType()).isEqualTo(ResultType.COMPLETE);
	}

	@Test
	void unknownResultTypeIsInvalid() {
		McpAsyncClient client = client(request -> result(request, textResult("task")));

		assertThatThrownBy(() -> client.tool("t").callOnce().block(TIMEOUT))
			.isInstanceOf(McpInvalidResultException.class)
			.satisfies(ex -> {
				McpInvalidResultException invalid = (McpInvalidResultException) ex;
				assertThat(invalid.method()).isEqualTo(McpSchema.METHOD_TOOLS_CALL);
				assertThat(invalid.resultType()).isEqualTo("task");
			});
	}

	@Test
	void inputRequiredIsInvalidOnAList() {
		McpAsyncClient client = client(
				request -> result(request, Map.of("resultType", ResultType.INPUT_REQUIRED, "requestState", "s")));

		assertThatThrownBy(() -> client.listTools().block(TIMEOUT)).isInstanceOf(McpInvalidResultException.class)
			.satisfies(ex -> assertThat(((McpInvalidResultException) ex).resultType())
				.isEqualTo(ResultType.INPUT_REQUIRED));
	}

	@Test
	void resultThatIsNotAnObjectIsInvalid() {
		McpAsyncClient client = client(request -> result(request, List.of()));

		assertThatThrownBy(() -> client.listTools().block(TIMEOUT)).isInstanceOf(McpInvalidResultException.class);
	}

	@Test
	void inputRequestWithoutHandlerFailsTheCall() {
		InputRequiredResult inputRequired = InputRequiredResult.builder()
			.elicitUrl("login", "Please log in", "https://example.com/login")
			.requestState("round-1")
			.build();
		McpAsyncClient client = client(
				request -> result(request, McpJsonDefaults.getMapper().convertValue(inputRequired, Map.class)));

		InputRequiredResult once = (InputRequiredResult) client.tool("t").callOnce().block(TIMEOUT);

		assertThat(once.requestState()).isEqualTo("round-1");
		assertThat(once.inputRequests().get("login").method()).isEqualTo(McpSchema.METHOD_ELICITATION_CREATE);
		assertThatThrownBy(() -> client.tool("t").call().block(TIMEOUT))
			.isInstanceOf(McpInputHandlerMissingException.class)
			.hasMessageContaining("mode url")
			.satisfies(ex -> assertThat(((McpInputHandlerMissingException) ex).inputKey()).isEqualTo("login"));
	}

	@Test
	void malformedMissingCapabilityDataLeavesTheCapabilitiesUnknown() {
		McpAsyncClient client = client(request -> error(request,
				new JSONRPCError(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, "Missing", "not an object")));

		assertThatThrownBy(() -> client.listTools().block(TIMEOUT))
			.isInstanceOf(McpMissingClientCapabilityException.class)
			.satisfies(ex -> assertThat(((McpMissingClientCapabilityException) ex).requiredCapabilities()).isNull());
	}

	@Test
	void unsupportedVersionIsRetriedOnlyOnce() {
		McpAsyncClient client = McpClient.async(request -> Flux.defer(() -> {
			this.requests.add(request);
			return error(request,
					new JSONRPCError(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported",
							new UnsupportedProtocolVersionData(List.of("2026-01-01", "2026-02-02"),
									request.protocolVersion())));
		})).protocolVersions(List.of(McpSchema.LATEST_PROTOCOL_VERSION, "2026-02-02", "2026-01-01")).build();

		assertThatThrownBy(() -> client.listTools().block(TIMEOUT)).isInstanceOf(McpException.class)
			.satisfies(ex -> assertThat(((McpException) ex).error().code())
				.isEqualTo(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION));
		assertThat(this.requests).extracting(McpTransportRequest::protocolVersion)
			.containsExactly(McpSchema.LATEST_PROTOCOL_VERSION, "2026-02-02");
	}

	@Test
	void unsupportedVersionListedAsSupportedIsRetriedOnceWithIt() {
		McpAsyncClient client = client(
				request -> this.requests.size() == 1
						? error(request,
								new JSONRPCError(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported",
										new UnsupportedProtocolVersionData(List.of(request.protocolVersion()),
												request.protocolVersion())))
						: result(request, Map.of("tools", List.of())));

		client.listTools().block(TIMEOUT);

		assertThat(this.requests).extracting(McpTransportRequest::protocolVersion)
			.containsExactly(McpSchema.LATEST_PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(this.requests.get(0).message().id()).isNotEqualTo(this.requests.get(1).message().id());
	}

	@Test
	void roundTimeoutCancelsTheExchange() {
		AtomicBoolean cancelled = new AtomicBoolean();
		McpAsyncClient client = client(request -> Flux.<JSONRPCMessage>never().doOnCancel(() -> cancelled.set(true)));

		StepVerifier.withVirtualTime(() -> client.tools().list())
			.expectSubscription()
			.thenAwait(Duration.ofMillis(200))
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOfSatisfying(McpTimeoutException.class, timeout -> {
				assertThat(timeout.kind()).isEqualTo(McpTimeoutException.Kind.ROUND);
				assertThat(timeout.timeout()).isEqualTo(Duration.ofMillis(200));
				assertThat(timeout.method()).isEqualTo(McpSchema.METHOD_TOOLS_LIST);
			}))
			.verify(TIMEOUT);
		assertThat(cancelled).isTrue();
	}

	@Test
	void notificationsDoNotExtendTheRoundTimeout() {
		McpAsyncClient client = client(request -> Flux.interval(Duration.ofMillis(50))
			.map(i -> new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_PROGRESS,
					new ProgressParams("t", (double) i, null, null, null))));

		StepVerifier.withVirtualTime(() -> client.tools().list())
			.expectSubscription()
			.thenAwait(Duration.ofMillis(200))
			.expectError(McpTimeoutException.class)
			.verify(TIMEOUT);
	}

	@Test
	void roundTimeoutOfTheSpecOverridesTheClients() {
		McpAsyncClient client = client(
				request -> Flux.just(JSONRPCResponse.result(request.message().id(), textResult(null)))
					.delayElements(Duration.ofMillis(400))
					.cast(JSONRPCMessage.class));

		StepVerifier.withVirtualTime(() -> client.tool("t").roundTimeout(Duration.ofSeconds(2)).call())
			.expectSubscription()
			.thenAwait(Duration.ofMillis(400))
			.expectNextCount(1)
			.verifyComplete();
	}

	@Test
	void exchangeWithoutAResponseLosesIt() {
		McpAsyncClient client = client(request -> Flux.empty());

		assertThatThrownBy(() -> client.tool("t").call().block(TIMEOUT)).isInstanceOf(McpResponseLostException.class);
	}

	@Test
	void notificationsBeforeTheResponseAreSkipped() {
		McpAsyncClient client = client(request -> Flux.just(
				new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_PROGRESS,
						new ProgressParams("t", 1.0, null, null, null)),
				JSONRPCResponse.result(request.message().id(),
						Map.of("tools", List.of(), "ttlMs", 0, "cacheScope", "private"))));

		ListToolsResult result = client.listTools().block(TIMEOUT);

		assertThat(result.tools()).isEmpty();
	}

	@Test
	void nullInputRequestIsAnInvalidResult() {
		Map<String, Object> inputRequests = new HashMap<>();
		inputRequests.put("a", null);
		McpAsyncClient client = client(
				request -> result(request, Map.of("resultType", "input_required", "inputRequests", inputRequests)));

		assertThatThrownBy(() -> client.tool("t").call().block(TIMEOUT)).isInstanceOf(McpInvalidResultException.class)
			.hasMessageContaining("'a'");
	}

	@Test
	void ttlBeyondWhatTheClockMeasuresKeepsTheResult() {
		long forever = Long.MAX_VALUE;
		McpClientTransport transport = new McpClientTransport() {

			@Override
			public Flux<JSONRPCMessage> exchange(McpTransportRequest request) {
				return Flux.defer(() -> {
					requests.add(request);
					return switch (request.method()) {
						case McpSchema.METHOD_SERVER_DISCOVER ->
							result(request, Map.of("supportedVersions", List.of(McpSchema.LATEST_PROTOCOL_VERSION),
									"capabilities", Map.of(), "ttlMs", forever, "cacheScope", "private"));
						case McpSchema.METHOD_TOOLS_LIST -> result(request,
								Map.of("tools", List.of(Map.of("name", "t", "inputSchema", Map.of("type", "object"))),
										"ttlMs", forever, "cacheScope", "private"));
						default -> result(request, textResult(null));
					};
				});
			}

			@Override
			public boolean mirrorsToolParams() {
				return true;
			}

		};
		McpAsyncClient client = McpClient.async(transport).build();

		client.discover().block(TIMEOUT);
		client.discover().block(TIMEOUT);
		client.tool("t").call().block(TIMEOUT);
		client.tool("t").call().block(TIMEOUT);

		assertThat(this.requests).extracting(McpTransportRequest::method)
			.containsExactly(McpSchema.METHOD_SERVER_DISCOVER, McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_CALL,
					McpSchema.METHOD_TOOLS_CALL);
	}

	@Test
	void syncCallTimeoutBeyondWhatTheClockMeasuresAllowsTheCall() {
		McpSyncClient client = McpClient.sync(request -> result(request, textResult(null)))
			.callTimeout(Duration.ofSeconds(Long.MAX_VALUE))
			.build();

		assertThat(client.tool("t").call()).isNotNull();
	}

	/**
	 * Answers the first request with a URL elicitation and the retry after 400 ms, twice
	 * the client's round timeout.
	 */
	private McpAsyncClient urlElicitingClient(ElicitResult answer) {
		InputRequiredResult login = InputRequiredResult.builder()
			.elicitUrl("login", "Please log in", "https://example.com/login")
			.build();
		Map<?, ?> loginJson = McpJsonDefaults.getMapper().convertValue(login, Map.class);
		McpClientTransport transport = request -> request.withRoundTimeout(Flux.defer(() -> {
			this.requests.add(request);
			if (this.requests.size() == 1) {
				return result(request, loginJson);
			}
			return result(request, textResult(null)).delayElements(Duration.ofMillis(400));
		}));
		return McpClient.async(transport)
			.roundTimeout(Duration.ofMillis(200))
			.urlElicitation(request -> Mono.just(answer))
			.build();
	}

	@Test
	void retryAfterAnAcceptedUrlElicitationHasNoRoundTimeout() {
		McpAsyncClient client = urlElicitingClient(ElicitResult.accept(null));

		StepVerifier.withVirtualTime(() -> client.tool("t").call())
			.expectSubscription()
			.thenAwait(Duration.ofMillis(400))
			.expectNextCount(1)
			.verifyComplete();
		assertThat(this.requests).hasSize(2);
	}

	@Test
	void retryAfterADeclinedUrlElicitationKeepsTheRoundTimeout() {
		McpAsyncClient client = urlElicitingClient(ElicitResult.decline());

		StepVerifier.withVirtualTime(() -> client.tool("t").call())
			.expectSubscription()
			.thenAwait(Duration.ofMillis(200))
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOfSatisfying(McpTimeoutException.class,
					timeout -> assertThat(timeout.kind()).isEqualTo(McpTimeoutException.Kind.ROUND)))
			.verify(TIMEOUT);
	}

	@Test
	void retryAnsweredWithAnUnknownResultTypeIsInvalid() {
		McpAsyncClient client = client(request -> result(request, this.requests.size() == 1
				? Map.of("resultType", ResultType.INPUT_REQUIRED, "requestState", "s") : textResult("task")));

		assertThatThrownBy(() -> client.tool("t").call().block(TIMEOUT)).isInstanceOfSatisfying(
				McpInvalidResultException.class, ex -> assertThat(ex.resultType()).isEqualTo("task"));
	}

}
