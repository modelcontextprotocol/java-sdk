/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.io.PrintWriter;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsListenRequest;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.client.transport.HttpClientMcpTransport;
import io.modelcontextprotocol.modern.client.transport.McpHttpAuthorizationErrorHandler;
import io.modelcontextprotocol.modern.client.transport.McpHttpRequest;
import io.modelcontextprotocol.modern.client.transport.McpHttpRequestCustomizer;
import io.modelcontextprotocol.modern.client.transport.McpHttpResponseInfo;
import io.modelcontextprotocol.modern.client.transport.McpHttpStatusException;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.McpAsyncTool;
import io.modelcontextprotocol.modern.server.feature.McpAsyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.util.ToolsUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HttpClientMcpTransport} against the modern server's servlet transport.
 */
class HttpClientMcpTransportIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String ENDPOINT = "http://localhost:" + PORT + "/mcp";

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final String VERSION = McpSchema.LATEST_PROTOCOL_VERSION;

	/** Requests carrying this header are answered 401 unless they carry the token. */
	private static final String REQUIRE_TOKEN = "X-Test-Require-Token";

	private static final String GOOD_TOKEN = "Bearer good";

	/** Requests carrying this header get a stream that ends without a response. */
	private static final String DROP_RESPONSE = "X-Test-Drop-Response";

	/** Requests carrying this header get a plain-text 500. */
	private static final String FAIL_PLAINLY = "X-Test-Fail-Plainly";

	private static final Tool REGION_TOOL = Tool.builder("region", JSON_MAPPER, """
			{
			  "type": "object",
			  "properties": {
			    "region": { "type": "string", "x-mcp-header": "Region" }
			  }
			}
			""").build();

	private static final Tool STREAMER_TOOL = Tool.builder("streamer", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final TestChangePublisher CHANGES = new TestChangePublisher();

	private static final List<String> SEEN_AUTHORIZATION = new CopyOnWriteArrayList<>();

	private static Tomcat tomcat;

	@BeforeAll
	static void startServer() {
		McpAsyncToolRepository tools = new McpAsyncToolRepository() {
			@Override
			public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ToolsPage.of(List.of(REGION_TOOL, STREAMER_TOOL)));
			}

			@Override
			public Mono<McpAsyncTool> find(McpRequestContext ctx, String name) {
				Tool tool = switch (name) {
					case "region" -> REGION_TOOL;
					case "streamer" -> STREAMER_TOOL;
					default -> Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build();
				};
				return Mono.just(McpAsyncTool.of(tool, this::call));
			}

			private Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
				if ("streamer".equals(request.name())) {
					return Mono.just(McpAsyncResponse
						.streaming(notifier -> notifier.progress(1.0, 2.0, "half").thenReturn(text("streamed"))));
				}
				Object region = request.arguments() == null ? null : request.arguments().get("region");
				return Mono.just(McpAsyncResponse.result(text(request.name() + ":" + region)));
			}
		};
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("modern-test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.feature(ToolsFeature.ofAsync(tools))
			.subscriptions(CHANGES)
			.build();
		HttpServletMcpTransport servlet = HttpServletMcpTransport.builder(server)
			.jsonMapper(JSON_MAPPER)
			.endpoint("/mcp")
			// A failing keep-alive write is how the server notices a closed listen
			// stream.
			.keepAliveInterval(Duration.ofMillis(100))
			.build();

		Filter testBehaviour = (request, response, chain) -> {
			HttpServletRequest req = (HttpServletRequest) request;
			HttpServletResponse resp = (HttpServletResponse) response;
			String authorization = req.getHeader("Authorization");
			if (authorization != null) {
				SEEN_AUTHORIZATION.add(authorization);
			}
			if (req.getHeader(REQUIRE_TOKEN) != null && !GOOD_TOKEN.equals(authorization)) {
				resp.setStatus(401);
				resp.setHeader("WWW-Authenticate", "Bearer scope=\"tools\"");
				return;
			}
			if (req.getHeader(DROP_RESPONSE) != null) {
				resp.setStatus(200);
				resp.setContentType("text/event-stream");
				PrintWriter writer = resp.getWriter();
				writer.write("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
						+ "\"params\":{\"progressToken\":\"t\",\"progress\":1}}\n\n");
				writer.flush();
				return;
			}
			if (req.getHeader(FAIL_PLAINLY) != null) {
				resp.setStatus(500);
				resp.setContentType("text/plain");
				resp.getWriter().write("boom");
				return;
			}
			chain.doFilter(request, response);
		};

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, servlet, testBehaviour);
		try {
			tomcat.start();
		}
		catch (LifecycleException e) {
			throw new RuntimeException(e);
		}
	}

	@AfterAll
	static void stopServer() throws LifecycleException {
		if (tomcat != null) {
			tomcat.stop();
			tomcat.destroy();
		}
	}

	@BeforeEach
	void clearSeenAuthorization() {
		SEEN_AUTHORIZATION.clear();
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	private static Map<String, Object> meta(Object progressToken) {
		return RequestMeta.of(VERSION, ClientCapabilities.NONE, null, progressToken);
	}

	private static McpTransportRequest toolCall(String name, Map<String, Object> arguments,
			Map<String, String> paramHeaders) {
		CallToolRequest params = CallToolRequest.builder(name).arguments(arguments).meta(meta(null)).build();
		return McpTransportRequest.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, 1, params), VERSION)
			.name(name)
			.paramHeaders(paramHeaders)
			.build();
	}

	private static McpTransportRequest toolCall(String name) {
		return toolCall(name, Map.of(), Map.of());
	}

	private static String resultText(JSONRPCMessage message) {
		JSONRPCResponse response = (JSONRPCResponse) message;
		assertThat(response.error()).isNull();
		CallToolResult result = JSON_MAPPER.convertValue(response.result(), CallToolResult.class);
		return ((TextContent) result.content().get(0)).text();
	}

	private static McpHttpRequestCustomizer header(String name, String value) {
		return McpHttpRequestCustomizer.fromSync((request, headers, context) -> headers.set(name, value));
	}

	@Test
	void jsonAnswerIsTheResponse() {
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT).build();

		StepVerifier.create(transport.exchange(toolCall("echo")))
			.assertNext(message -> assertThat(resultText(message)).isEqualTo("echo:null"))
			.verifyComplete();
	}

	@Test
	void streamedAnswerEmitsNotificationsThenTheResponse() {
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT).build();
		CallToolRequest params = CallToolRequest.builder("streamer").meta(meta("tok-1")).build();
		McpTransportRequest request = McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, 1, params), VERSION)
			.name("streamer")
			.build();

		StepVerifier.create(transport.exchange(request))
			.assertNext(message -> assertThat(((JSONRPCNotification) message).method())
				.isEqualTo(McpSchema.METHOD_NOTIFICATION_PROGRESS))
			.assertNext(message -> assertThat(resultText(message)).isEqualTo("streamed"))
			.verifyComplete();
	}

	@Test
	void mirroredArgumentPassesTheServersHeaderCheck() {
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT).build();

		StepVerifier
			.create(transport.exchange(toolCall("region", Map.of("region", " zürich "), Map.of("Region", " zürich "))))
			.assertNext(message -> assertThat(resultText(message)).isEqualTo("region: zürich "))
			.verifyComplete();
	}

	@Test
	void missingMirroredArgumentIsAHeaderMismatchResponse() {
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT).build();

		StepVerifier.create(transport.exchange(toolCall("region", Map.of("region", "eu"), Map.of())))
			.assertNext(message -> assertThat(((JSONRPCResponse) message).error().code())
				.isEqualTo(ErrorCodes.HEADER_MISMATCH))
			.verifyComplete();
	}

	@Test
	void nameOutsideTheHeaderSafeSetIsEncoded() {
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT).build();

		StepVerifier.create(transport.exchange(toolCall("wetter-ü")))
			.assertNext(message -> assertThat(resultText(message)).isEqualTo("wetter-ü:null"))
			.verifyComplete();
	}

	@Test
	void unsupportedVersionIsAnErrorResponse() {
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT).build();
		Map<String, Object> meta = RequestMeta.of("1999-01-01", ClientCapabilities.NONE, null, null);
		McpTransportRequest request = McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, 1, new PaginatedRequest(null, meta)), "1999-01-01")
			.build();

		StepVerifier.create(transport.exchange(request))
			.assertNext(message -> assertThat(((JSONRPCResponse) message).error().code())
				.isEqualTo(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION))
			.verifyComplete();
	}

	@Test
	void customizersRunInOrderWithTheCallersContext() {
		List<String> seen = new ArrayList<>();
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer((request, headers, context) -> Mono.fromRunnable(() -> {
				seen.add(request.mcpMethod() + " " + request.mcpName() + " "
						+ request.headers().firstValue("Mcp-Method").orElseThrow());
				headers.set("Authorization", "Bearer " + context.get("user"));
			}))
			.requestCustomizer(McpHttpRequestCustomizer.fromSync((request, headers, context) -> {
				seen.add(headers.get("Authorization").get(0));
			}))
			.build();
		McpTransportContext identity = McpTransportContext.create(Map.of("user", "alice"));

		StepVerifier
			.create(transport.exchange(toolCall("echo"))
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, identity)))
			.expectNextCount(1)
			.verifyComplete();

		assertThat(seen).containsExactly("tools/call echo tools/call", "Bearer alice");
		assertThat(SEEN_AUTHORIZATION).containsExactly("Bearer alice");
	}

	@Test
	void customizerFailureReachesTheCaller() {
		IllegalStateException failure = new IllegalStateException("no token");
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer((request, headers, context) -> Mono.error(failure))
			.build();

		StepVerifier.create(transport.exchange(toolCall("echo"))).expectErrorMatches(ex -> ex == failure).verify();
	}

	@Test
	void authorizationErrorHandlerRetriesWithRefreshedToken() {
		AtomicReference<String> token = new AtomicReference<>("Bearer stale");
		List<McpHttpResponseInfo> seen = new ArrayList<>();
		List<McpHttpRequest> sent = new ArrayList<>();
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer(header(REQUIRE_TOKEN, "yes"))
			.requestCustomizer(McpHttpRequestCustomizer
				.fromSync((request, headers, context) -> headers.set("Authorization", token.get())))
			.authorizationErrorHandler((request, response, context) -> {
				sent.add(request);
				seen.add(response);
				token.set(GOOD_TOKEN);
				return Mono.just(true);
			})
			.build();

		StepVerifier.create(transport.exchange(toolCall("echo")))
			.assertNext(message -> assertThat(resultText(message)).isEqualTo("echo:null"))
			.verifyComplete();

		assertThat(seen).singleElement().satisfies(response -> {
			assertThat(response.statusCode()).isEqualTo(401);
			assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer scope=\"tools\"");
		});
		assertThat(sent).singleElement()
			.satisfies(request -> assertThat(request.headers().firstValue("Authorization")).hasValue("Bearer stale"));
		assertThat(SEEN_AUTHORIZATION).containsExactly("Bearer stale", GOOD_TOKEN);
	}

	@Test
	void authorizationErrorIsRetriedAtMostMaxRetriesTimes() {
		AtomicInteger calls = new AtomicInteger();
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer(header(REQUIRE_TOKEN, "yes"))
			.authorizationErrorHandler(McpHttpAuthorizationErrorHandler.fromSync((request, response, context) -> {
				calls.incrementAndGet();
				return true;
			}))
			.build();

		StepVerifier.create(transport.exchange(toolCall("echo")))
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOfSatisfying(McpHttpStatusException.class,
					status -> assertThat(status.statusCode()).isEqualTo(401)))
			.verify();
		assertThat(calls).hasValue(1);
	}

	@Test
	void authorizationHandlerSetLaterReplacesTheEarlierOne() {
		List<String> asked = new CopyOnWriteArrayList<>();
		AtomicReference<String> token = new AtomicReference<>("Bearer stale");
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer(header(REQUIRE_TOKEN, "yes"))
			.requestCustomizer(McpHttpRequestCustomizer
				.fromSync((request, headers, context) -> headers.set("Authorization", token.get())))
			.authorizationErrorHandler((request, response, context) -> {
				asked.add("first");
				return Mono.just(false);
			})
			.authorizationErrorHandler((request, response, context) -> {
				asked.add("second");
				token.set(GOOD_TOKEN);
				return Mono.just(true);
			})
			.build();

		StepVerifier.create(transport.exchange(toolCall("echo"))).expectNextCount(1).verifyComplete();
		assertThat(asked).containsExactly("second");
	}

	@Test
	void authorizationHandlerTimeDoesNotCountTowardsTheRoundTimeout() {
		AtomicReference<String> token = new AtomicReference<>("Bearer stale");
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer(header(REQUIRE_TOKEN, "yes"))
			.requestCustomizer(McpHttpRequestCustomizer
				.fromSync((request, headers, context) -> headers.set("Authorization", token.get())))
			.authorizationErrorHandler((request, response, context) -> Mono.delay(Duration.ofMillis(600))
				.doOnNext(ignored -> token.set(GOOD_TOKEN))
				.thenReturn(true))
			.build();
		McpTransportRequest call = toolCall("echo");
		McpTransportRequest timed = McpTransportRequest.builder(call.message(), call.protocolVersion())
			.name(call.name())
			.paramHeaders(call.paramHeaders())
			.roundTimeout(Duration.ofMillis(300))
			.build();

		StepVerifier.create(transport.exchange(timed)).expectNextCount(1).verifyComplete();
	}

	@Test
	void authorizationHandlerFailureReachesTheCallerUnwrapped() {
		IllegalStateException failure = new IllegalStateException("login required");
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer(header(REQUIRE_TOKEN, "yes"))
			.authorizationErrorHandler((request, response, context) -> Mono.error(failure))
			.build();

		StepVerifier.create(transport.exchange(toolCall("echo"))).expectErrorMatches(ex -> ex == failure).verify();
	}

	@Test
	void streamEndingWithoutResponseLosesIt() {
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer(header(DROP_RESPONSE, "yes"))
			.build();

		StepVerifier.create(transport.exchange(toolCall("echo")))
			.expectNextMatches(message -> message instanceof JSONRPCNotification)
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOfSatisfying(McpResponseLostException.class,
					lost -> assertThat(lost.method()).isEqualTo(McpSchema.METHOD_TOOLS_CALL)))
			.verify(Duration.ofSeconds(5));
	}

	@Test
	void errorStatusWithoutJsonRpcErrorIsAStatusException() {
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer(header(FAIL_PLAINLY, "yes"))
			.build();

		StepVerifier.create(transport.exchange(toolCall("echo")))
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOfSatisfying(McpHttpStatusException.class, status -> {
				assertThat(status.statusCode()).isEqualTo(500);
				assertThat(status.body()).isEqualTo("boom");
			}))
			.verify();
	}

	@Test
	void unreachableServerIsAConnectionFailure() {
		HttpClientMcpTransport transport = HttpClientMcpTransport
			.builder("http://localhost:" + TomcatTestUtil.findAvailablePort() + "/mcp")
			.build();

		StepVerifier.create(transport.exchange(toolCall("echo"))).expectError(ConnectException.class).verify();
	}

	@Test
	void cancellingTheExchangeClosesTheStream() {
		TestChangePublisher.Stream stream = CHANGES.nextStream();
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT).build();
		SubscriptionsListenRequest listen = new SubscriptionsListenRequest(
				SubscriptionFilter.of(Map.of(SubscriptionFilter.TOOLS_LIST_CHANGED, true)), meta(null));
		McpTransportRequest request = McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_SUBSCRIPTIONS_LISTEN, 1, listen), VERSION)
			.build();

		StepVerifier.create(transport.exchange(request))
			.assertNext(message -> assertThat(((JSONRPCNotification) message).method())
				.isEqualTo(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED))
			.thenCancel()
			.verify(Duration.ofSeconds(5));

		stream.awaitCancelled();
	}

	@Test
	void transportMirrorsToolParams() {
		assertThat(HttpClientMcpTransport.builder(ENDPOINT).build().mirrorsToolParams()).isTrue();
	}

	@Test
	void asyncClientListsAndCallsTools() {
		McpAsyncClient client = McpClient.async(HttpClientMcpTransport.builder(ENDPOINT).build()).build();

		List<Tool> tools = client.listTools().block(Duration.ofSeconds(5)).tools();
		CallToolResult streamed = client.tool("streamer").call().block(Duration.ofSeconds(5));

		assertThat(tools).extracting(Tool::name).containsExactly("region", "streamer");
		assertThat(((TextContent) streamed.content().get(0)).text()).isEqualTo("streamed");
	}

	@Test
	void asyncClientMirrorsAnnotatedArgumentsIntoHeaders() {
		McpAsyncClient client = McpClient.async(HttpClientMcpTransport.builder(ENDPOINT).build()).build();

		CallToolResult result = client.tool("region")
			.arguments(Map.of("region", " zürich "))
			.call()
			.block(Duration.ofSeconds(5));

		assertThat(((TextContent) result.content().get(0)).text()).isEqualTo("region: zürich ");
	}

	@Test
	void asyncClientListensUntilCancelled() {
		TestChangePublisher.Stream stream = CHANGES.nextStream();
		McpAsyncClient client = McpClient.async(HttpClientMcpTransport.builder(ENDPOINT).build()).build();

		StepVerifier.create(client.listen().toolsChanged().promptsChanged().events())
			.assertNext(event -> assertThat(((SubscriptionEvent.Subscribed) event).missing().fields())
				.containsExactly(Map.entry("promptsListChanged", true)))
			.then(() -> stream.broadcastWhenListening(new ServerChange.ToolsListChanged()))
			.expectNext(new SubscriptionEvent.ToolsListChanged())
			.thenCancel()
			.verify(Duration.ofSeconds(5));

		stream.awaitCancelled();
	}

	@Test
	void syncClientSendsTheProvidedIdentityAndMirrorsHeaders() {
		SEEN_AUTHORIZATION.clear();
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(ENDPOINT)
			.requestCustomizer(McpHttpRequestCustomizer
				.fromSync((request, headers, context) -> headers.set("Authorization", "Bearer " + context.get("user"))))
			.build();
		McpSyncClient client = McpClient.sync(transport)
			.transportContextProvider(() -> McpTransportContext.create(Map.of("user", "alice")))
			.build();

		CallToolResult result = client.tool("region").arguments(Map.of("region", "eu-west1")).call();

		assertThat(((TextContent) result.content().get(0)).text()).isEqualTo("region:eu-west1");
		// The catalog's listing and the call are each sent for the provided identity.
		assertThat(SEEN_AUTHORIZATION).containsOnly("Bearer alice").hasSizeGreaterThanOrEqualTo(2);
	}

	@Test
	void syncClientListensUntilClosed() throws InterruptedException {
		TestChangePublisher.Stream stream = CHANGES.nextStream();
		McpSyncClient client = McpClient.sync(HttpClientMcpTransport.builder(ENDPOINT).build()).build();
		CollectingListener listener = new CollectingListener();

		McpSyncClient.Listening listening = client.listen().toolsChanged().open(listener);
		// A change sent before the acknowledgment would be dropped.
		listener.await(1);
		stream.broadcastWhenListening(new ServerChange.ToolsListChanged());
		List<SubscriptionEvent> events = listener.await(2);
		listening.close();

		assertThat(events.get(1)).isEqualTo(new SubscriptionEvent.ToolsListChanged());
		stream.awaitCancelled();
	}

}
