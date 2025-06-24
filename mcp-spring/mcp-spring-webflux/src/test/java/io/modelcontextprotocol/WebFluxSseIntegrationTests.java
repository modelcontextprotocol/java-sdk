/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.spec.McpSchema.ErrorCodes.INVALID_PARAMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertWith;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

class WebFluxSseIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxSseServerTransportProvider mcpServerTransportProvider;

	ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	@BeforeEach
	public void before() {

		this.mcpServerTransportProvider = new WebFluxSseServerTransportProvider.Builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.build();

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(mcpServerTransportProvider.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		clientBuilders.put("httpclient",
				McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
					.sseEndpoint(CUSTOM_SSE_ENDPOINT)
					.build()));
		clientBuilders.put("webflux",
				McpClient
					.sync(WebFluxSseClientTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
						.sseEndpoint(CUSTOM_SSE_ENDPOINT)
						.build()));

	}

	@AfterEach
	public void after() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithoutSamplingCapabilities(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema),
				(exchange, request) -> exchange.createMessage(mock(CreateMessageRequest.class))
					.thenReturn(mock(CallToolResult.class)));

		var server = McpServer.async(mcpServerTransportProvider).serverInfo("test-server", "1.0.0").tools(tool).build();

		try (var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
			.build();) {

			assertThat(client.initialize()).isNotNull();

			try {
				client.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class)
					.hasMessage("Client must be configured with sampling capabilities");
			}
		}
		server.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					var createMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(List.of())
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					return exchange.createMessage(createMessageRequest)
						.doOnNext(samplingResult::set)
						.thenReturn(callResponse);
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);

			assertWith(samplingResult.get(), result -> {
				assertThat(result).isNotNull();
				assertThat(result.role()).isEqualTo(Role.USER);
				assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
				assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
				assertThat(result.model()).isEqualTo("MockModelName");
				assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
			});
		}
		mcpServer.closeGracefully().block();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithRequestTimeoutSuccess(String clientType) throws InterruptedException {

		// Client
		var clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					var craeteMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(List.of())
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					return exchange.createMessage(craeteMessageRequest)
						.doOnNext(samplingResult::set)
						.thenReturn(callResponse);
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.requestTimeout(Duration.ofSeconds(4))
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);

			assertWith(samplingResult.get(), result -> {
				assertThat(result).isNotNull();
				assertThat(result.role()).isEqualTo(Role.USER);
				assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
				assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
				assertThat(result.model()).isEqualTo("MockModelName");
				assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
			});
		}

		mcpServer.closeGracefully().block();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithRequestTimeoutFail(String clientType) throws InterruptedException {

		// Client
		var clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					var craeteMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.build();

					return exchange.createMessage(craeteMessageRequest).thenReturn(callResponse);
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.requestTimeout(Duration.ofSeconds(1))
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
			}).withMessageContaining("within 1000ms");

		}

		mcpServer.closeGracefully().block();
	}

	// ---------------------------------------
	// Elicitation Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateElicitationWithoutElicitationCapabilities(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.createElicitation(mock(ElicitRequest.class)).block();

					return Mono.just(mock(CallToolResult.class));
				});

		var server = McpServer.async(mcpServerTransportProvider).serverInfo("test-server", "1.0.0").tools(tool).build();

		try (
				// Create client without elicitation capabilities
				var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0")).build()) {

			assertThat(client.initialize()).isNotNull();

			try {
				client.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class)
					.hasMessage("Client must be configured with elicitation capabilities");
			}
		}
		server.closeGracefully().block();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateElicitationSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();

			return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("message", request.message()));
		};

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					var elicitationRequest = ElicitRequest.builder()
						.message("Test message")
						.requestedSchema(
								Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
						.build();

					StepVerifier.create(exchange.createElicitation(elicitationRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.action()).isEqualTo(ElicitResult.Action.ACCEPT);
						assertThat(result.content().get("message")).isEqualTo("Test message");
					}).verifyComplete();

					return Mono.just(callResponse);
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
		}
		mcpServer.closeGracefully().block();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateElicitationWithRequestTimeoutSuccess(String clientType) {

		// Client
		var clientBuilder = clientBuilders.get(clientType);

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("message", request.message()));
		};

		var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build();

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					var elicitationRequest = ElicitRequest.builder()
						.message("Test message")
						.requestedSchema(
								Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
						.build();

					StepVerifier.create(exchange.createElicitation(elicitationRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.action()).isEqualTo(ElicitResult.Action.ACCEPT);
						assertThat(result.content().get("message")).isEqualTo("Test message");
					}).verifyComplete();

					return Mono.just(callResponse);
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(3))
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo(callResponse);

		mcpClient.closeGracefully();
		mcpServer.closeGracefully().block();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateElicitationWithRequestTimeoutFail(String clientType) {

		// Client
		var clientBuilder = clientBuilders.get(clientType);

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("message", request.message()));
		};

		var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build();

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					var elicitationRequest = ElicitRequest.builder()
						.message("Test message")
						.requestedSchema(
								Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
						.build();

					StepVerifier.create(exchange.createElicitation(elicitationRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.action()).isEqualTo(ElicitResult.Action.ACCEPT);
						assertThat(result.content().get("message")).isEqualTo("Test message");
					}).verifyComplete();

					return Mono.just(callResponse);
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(1))
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
			mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
		}).withMessageContaining("within 1000ms");

		mcpClient.closeGracefully();
		mcpServer.closeGracefully().block();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsSuccess(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = List.of(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});

			// Remove a root
			mcpClient.removeRoot(roots.get(0).uri());

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(List.of(roots.get(1)));
			});

			// Add a new root
			var root3 = new Root("uri3://", "root3");
			mcpClient.addRoot(root3);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(List.of(roots.get(1), root3));
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsWithoutCapability(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.listRoots(); // try to list roots

					return mock(CallToolResult.class);
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider).rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

		// Create client without roots capability
		try (var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()).build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			// Attempt to list roots should fail
			try {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class).hasMessage("Roots not supported");
			}
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsNotificationWithEmptyRootsList(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(List.of()) // Empty roots list
			.build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsWithMultipleHandlers(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = List.of(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef1.set(rootsUpdate))
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef2.set(rootsUpdate))
			.build();

		try (var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef1.get()).containsAll(roots);
				assertThat(rootsRef2.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsServerCloseWithActiveSubscription(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = List.of(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

	String emptyJsonSchema = """
			{
			"$schema": "http://json-schema.org/draft-07/schema#",
			"type": "object",
			"properties": {}
			}
			""";

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolCallSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					String response = RestClient.create()
						.get()
						.uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
						.retrieve()
						.body(String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolListChangeHandlingSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					String response = RestClient.create()
						.get()
						.uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
						.retrieve()
						.body(String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			String response = RestClient.create()
				.get()
				.uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
				.retrieve()
				.body(String.class);
			assertThat(response).isNotBlank();
			rootsRef.set(toolsUpdate);
		}).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			mcpServer.notifyToolsListChanged();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(List.of(tool1.tool()));
			});

			// Remove a tool
			mcpServer.removeTool("tool1");

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});

			// Add a new tool
			McpServerFeatures.SyncToolSpecification tool2 = new McpServerFeatures.SyncToolSpecification(
					new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema),
					(exchange, request) -> callResponse);

			mcpServer.addTool(tool2);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(List.of(tool2.tool()));
			});
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tests for Paginated Tool List Results
	// ---------------------------------------

	@ParameterizedTest(name = "{0} ({1}) : {displayName} ")
	@MethodSource("providePaginationTestParams")
	void testListToolsSuccess(String clientType, int availableElements) {

		var clientBuilder = clientBuilders.get(clientType);

		// Setup list of prompts
		List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

		for (int i = 0; i < availableElements; i++) {
			var mock = new McpSchema.Tool("test-tool-" + i, "Test Tool Description", emptyJsonSchema);
			var spec = new McpServerFeatures.SyncToolSpecification(mock, null);

			tools.add(spec);
		}

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tools)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			var returnedElements = new HashSet<String>();

			var hasEntries = true;
			String nextCursor = null;

			while (hasEntries) {
				var res = mcpClient.listTools(nextCursor);

				res.tools().forEach(e -> returnedElements.add(e.name())); // store unique
																			// attribute

				nextCursor = res.nextCursor();

				if (nextCursor == null) {
					hasEntries = false;
				}
			}

			assertThat(returnedElements.size()).isEqualTo(availableElements);
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testListToolsCursorInvalidListChanged(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		// Setup list of prompts
		var pageSize = 10;
		List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

		for (int i = 0; i <= pageSize; i++) {
			var mock = new McpSchema.Tool("test-tool-" + i, "Test Tool Description", emptyJsonSchema);
			var spec = new McpServerFeatures.SyncToolSpecification(mock, null);

			tools.add(spec);
		}

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tools)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			var res = mcpClient.listTools(null);

			// Change list
			var mock = new McpSchema.Tool("test-tool-xyz", "Test Tool Description", emptyJsonSchema);
			mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(mock, null));

			assertThatThrownBy(() -> mcpClient.listTools(res.nextCursor())).isInstanceOf(McpError.class)
				.hasMessage("Invalid cursor")
				.satisfies(exception -> {
					var error = (McpError) exception;
					assertThat(error.getJsonRpcError().code()).isEqualTo(INVALID_PARAMS);
					assertThat(error.getJsonRpcError().message()).isEqualTo("Invalid cursor");
				});

		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testListToolsInvalidCursor(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var mock = new McpSchema.Tool("test-tool", "Test Tool Description", emptyJsonSchema);
		var spec = new McpServerFeatures.SyncToolSpecification(mock, null);

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(spec)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatThrownBy(() -> mcpClient.listTools("INVALID")).isInstanceOf(McpError.class)
				.hasMessage("Invalid cursor")
				.satisfies(exception -> {
					var error = (McpError) exception;
					assertThat(error.getJsonRpcError().code()).isEqualTo(INVALID_PARAMS);
					assertThat(error.getJsonRpcError().message()).isEqualTo("Invalid cursor");
				});

		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testInitialize(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var mcpServer = McpServer.sync(mcpServerTransportProvider).build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testLoggingNotification(String clientType) throws InterruptedException {
		int expectedNotificationsCount = 3;
		CountDownLatch latch = new CountDownLatch(expectedNotificationsCount);
		// Create a list to store received logging notifications
		List<McpSchema.LoggingMessageNotification> receivedNotifications = new CopyOnWriteArrayList<>();

		var clientBuilder = clientBuilders.get(clientType);

		// Create server with a tool that sends logging notifications
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("logging-test", "Test logging notifications", emptyJsonSchema),
				(exchange, request) -> {

					// Create and send notifications with different levels

				//@formatter:off
					return exchange // This should be filtered out (DEBUG < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.DEBUG)
								.logger("test-logger")
								.data("Debug message")
								.build())
					.then(exchange // This should be sent (NOTICE >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.NOTICE)
								.logger("test-logger")
								.data("Notice message")
								.build()))
					.then(exchange // This should be sent (ERROR > NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.ERROR)
							.logger("test-logger")
							.data("Error message")
							.build()))
					.then(exchange // This should be filtered out (INFO < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.INFO)
								.logger("test-logger")
								.data("Another info message")
								.build()))
					.then(exchange // This should be sent (ERROR >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.ERROR)
								.logger("test-logger")
								.data("Another error message")
								.build()))
					.thenReturn(new CallToolResult("Logging test completed", false));
					//@formatter:on
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().logging().tools(true).build())
			.tools(tool)
			.build();

		try (
				// Create client with logging notification handler
				var mcpClient = clientBuilder.loggingConsumer(notification -> {
					receivedNotifications.add(notification);
					latch.countDown();
				}).build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Set minimum logging level to NOTICE
			mcpClient.setLoggingLevel(McpSchema.LoggingLevel.NOTICE);

			// Call the tool that sends logging notifications
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("logging-test", Map.of()));
			assertThat(result).isNotNull();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Logging test completed");

			assertThat(latch.await(5, TimeUnit.SECONDS)).as("Should receive notifications in reasonable time").isTrue();

			// Should have received 3 notifications (1 NOTICE and 2 ERROR)
			assertThat(receivedNotifications).hasSize(expectedNotificationsCount);

			Map<String, McpSchema.LoggingMessageNotification> notificationMap = receivedNotifications.stream()
				.collect(Collectors.toMap(n -> n.data(), n -> n));

			// First notification should be NOTICE level
			assertThat(notificationMap.get("Notice message").level()).isEqualTo(McpSchema.LoggingLevel.NOTICE);
			assertThat(notificationMap.get("Notice message").logger()).isEqualTo("test-logger");
			assertThat(notificationMap.get("Notice message").data()).isEqualTo("Notice message");

			// Second notification should be ERROR level
			assertThat(notificationMap.get("Error message").level()).isEqualTo(McpSchema.LoggingLevel.ERROR);
			assertThat(notificationMap.get("Error message").logger()).isEqualTo("test-logger");
			assertThat(notificationMap.get("Error message").data()).isEqualTo("Error message");

			// Third notification should be ERROR level
			assertThat(notificationMap.get("Another error message").level()).isEqualTo(McpSchema.LoggingLevel.ERROR);
			assertThat(notificationMap.get("Another error message").logger()).isEqualTo("test-logger");
			assertThat(notificationMap.get("Another error message").data()).isEqualTo("Another error message");
		}
		mcpServer.close();
	}

	// ---------------------------------------
	// Completion Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : Completion call")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCompletionShouldReturnExpectedSuggestions(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		var expectedValues = List.of("python", "pytorch", "pyside");
		var completionResponse = new McpSchema.CompleteResult(new CompleteResult.CompleteCompletion(expectedValues, 10, // total
				true // hasMore
		));

		AtomicReference<CompleteRequest> samplingRequest = new AtomicReference<>();
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (mcpSyncServerExchange,
				request) -> {
			samplingRequest.set(request);
			return completionResponse;
		};

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().completions().build())
			.prompts(new McpServerFeatures.SyncPromptSpecification(
					new Prompt("code_review", "this is code review prompt",
							List.of(new PromptArgument("language", "string", false))),
					(mcpSyncServerExchange, getPromptRequest) -> null))
			.completions(new McpServerFeatures.SyncCompletionSpecification(
					new McpSchema.PromptReference("ref/prompt", "code_review"), completionHandler))
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CompleteRequest request = new CompleteRequest(new PromptReference("ref/prompt", "code_review"),
					new CompleteRequest.CompleteArgument("language", "py"));

			CompleteResult result = mcpClient.completeCompletion(request);

			assertThat(result).isNotNull();

			assertThat(samplingRequest.get().argument().name()).isEqualTo("language");
			assertThat(samplingRequest.get().argument().value()).isEqualTo("py");
			assertThat(samplingRequest.get().ref().type()).isEqualTo("ref/prompt");
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tests for Paginated Prompt List Results
	// ---------------------------------------

	@ParameterizedTest(name = "{0} ({1}) : {displayName} ")
	@MethodSource("providePaginationTestParams")
	void testListPromptsSuccess(String clientType, int availableElements) {

		var clientBuilder = clientBuilders.get(clientType);

		// Setup list of prompts
		List<McpServerFeatures.SyncPromptSpecification> prompts = new ArrayList<>();

		for (int i = 0; i < availableElements; i++) {
			var mock = new McpSchema.Prompt("test-prompt-" + i, "Test Prompt Description",
					List.of(new McpSchema.PromptArgument("arg1", "Test argument", true)));
			var spec = new McpServerFeatures.SyncPromptSpecification(mock, null);

			prompts.add(spec);
		}

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.prompts(prompts)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			var returnedElements = new HashSet<String>();

			var hasEntries = true;
			String nextCursor = null;

			while (hasEntries) {
				var res = mcpClient.listPrompts(nextCursor);

				res.prompts().forEach(e -> returnedElements.add(e.name())); // store
																			// unique
																			// attribute

				nextCursor = res.nextCursor();

				if (nextCursor == null) {
					hasEntries = false;
				}
			}

			assertThat(returnedElements.size()).isEqualTo(availableElements);

		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testListPromptsCursorInvalidListChanged(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		// Setup list of prompts
		var pageSize = 10;
		List<McpServerFeatures.SyncPromptSpecification> prompts = new ArrayList<>();

		for (int i = 0; i <= pageSize; i++) {
			var mock = new McpSchema.Prompt("test-prompt-" + i, "Test Prompt Description",
					List.of(new McpSchema.PromptArgument("arg1", "Test argument", true)));
			var spec = new McpServerFeatures.SyncPromptSpecification(mock, null);

			prompts.add(spec);
		}

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.prompts(prompts)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			var res = mcpClient.listPrompts(null);

			// Change list
			var mock = new McpSchema.Prompt("test-prompt-xyz", "Test Prompt Description",
					List.of(new McpSchema.PromptArgument("arg1", "Test argument", true)));

			mcpServer.addPrompt(new McpServerFeatures.SyncPromptSpecification(mock, null));

			assertThatThrownBy(() -> mcpClient.listPrompts(res.nextCursor())).isInstanceOf(McpError.class)
				.hasMessage("Invalid cursor")
				.satisfies(exception -> {
					var error = (McpError) exception;
					assertThat(error.getJsonRpcError().code()).isEqualTo(INVALID_PARAMS);
					assertThat(error.getJsonRpcError().message()).isEqualTo("Invalid cursor");
				});

		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testListPromptsInvalidCursor(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var mock = new McpSchema.Prompt("test-prompt", "Test Prompt Description",
				List.of(new McpSchema.PromptArgument("arg1", "Test argument", true)));

		var spec = new McpServerFeatures.SyncPromptSpecification(mock, null);

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().prompts(true).build())
			.prompts(spec)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatThrownBy(() -> mcpClient.listPrompts("INVALID")).isInstanceOf(McpError.class)
				.hasMessage("Invalid cursor")
				.satisfies(exception -> {
					var error = (McpError) exception;
					assertThat(error.getJsonRpcError().code()).isEqualTo(INVALID_PARAMS);
					assertThat(error.getJsonRpcError().message()).isEqualTo("Invalid cursor");
				});

		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tests for Paginated Resources List Results
	// ---------------------------------------

	@ParameterizedTest(name = "{0} ({1}) : {displayName} ")
	@MethodSource("providePaginationTestParams")
	void testListResourcesSuccess(String clientType, int availableElements) {

		var clientBuilder = clientBuilders.get(clientType);

		// Setup list of prompts
		List<McpServerFeatures.SyncResourceSpecification> resources = new ArrayList<>();

		for (int i = 0; i < availableElements; i++) {
			var mock = new McpSchema.Resource("file://example-" + i + ".txt", "test-resource",
					"Test Resource Description", "application/octet-stream", null);
			var spec = new McpServerFeatures.SyncResourceSpecification(mock, null);

			resources.add(spec);
		}

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().resources(true, true).build())
			.resources(resources)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			var returnedElements = new HashSet<String>();

			var hasEntries = true;
			String nextCursor = null;

			while (hasEntries) {
				var res = mcpClient.listResources(nextCursor);

				res.resources().forEach(e -> returnedElements.add(e.uri())); // store
																				// unique
																				// attribute

				nextCursor = res.nextCursor();

				if (nextCursor == null) {
					hasEntries = false;
				}
			}

			assertThat(returnedElements.size()).isEqualTo(availableElements);
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testListResourcesCursorInvalidListChanged(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		// Setup list of prompts
		var pageSize = 10;
		List<McpServerFeatures.SyncResourceSpecification> resources = new ArrayList<>();

		for (int i = 0; i <= pageSize; i++) {
			var mock = new McpSchema.Resource("file://example-" + i + ".txt", "test-resource",
					"Test Resource Description", "application/octet-stream", null);
			var spec = new McpServerFeatures.SyncResourceSpecification(mock, null);

			resources.add(spec);
		}

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().resources(true, true).build())
			.resources(resources)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			var res = mcpClient.listResources(null);

			// Change list
			var mock = new McpSchema.Resource("file://example-xyz.txt", "test-resource", "Test Resource Description",
					"application/octet-stream", null);
			mcpServer.addResource(new McpServerFeatures.SyncResourceSpecification(mock, null));

			assertThatThrownBy(() -> mcpClient.listResources(res.nextCursor())).isInstanceOf(McpError.class)
				.hasMessage("Invalid cursor")
				.satisfies(exception -> {
					var error = (McpError) exception;
					assertThat(error.getJsonRpcError().code()).isEqualTo(INVALID_PARAMS);
					assertThat(error.getJsonRpcError().message()).isEqualTo("Invalid cursor");
				});

		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testListResourcesInvalidCursor(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var mock = new McpSchema.Resource("file://example.txt", "test-resource", "Test Resource Description",
				"application/octet-stream", null);
		var spec = new McpServerFeatures.SyncResourceSpecification(mock, null);

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().resources(true, true).build())
			.resources(spec)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatThrownBy(() -> mcpClient.listResources("INVALID")).isInstanceOf(McpError.class)
				.hasMessage("Invalid cursor")
				.satisfies(exception -> {
					var error = (McpError) exception;
					assertThat(error.getJsonRpcError().code()).isEqualTo(INVALID_PARAMS);
					assertThat(error.getJsonRpcError().message()).isEqualTo("Invalid cursor");
				});

		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tests for Paginated Resource Templates Results
	// ---------------------------------------

	@ParameterizedTest(name = "{0} ({1}) : {displayName} ")
	@MethodSource("providePaginationTestParams")
	void testListResourceTemplatesSuccess(String clientType, int availableElements) {

		var clientBuilder = clientBuilders.get(clientType);

		// Setup list of prompts
		List<McpSchema.ResourceTemplate> resourceTemplates = new ArrayList<>();

		for (int i = 0; i < availableElements; i++) {
			resourceTemplates.add(new McpSchema.ResourceTemplate("file://{path}-" + i + ".txt", "test-resource",
					"Test Resource Description", "application/octet-stream", null));
		}

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().resources(true, true).build())
			.resourceTemplates(resourceTemplates)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			var returnedElements = new HashSet<String>();

			var hasEntries = true;
			String nextCursor = null;

			while (hasEntries) {
				var res = mcpClient.listResourceTemplates(nextCursor);

				res.resourceTemplates().forEach(e -> returnedElements.add(e.uriTemplate())); // store
																								// unique
																								// attribute

				nextCursor = res.nextCursor();

				if (nextCursor == null) {
					hasEntries = false;
				}
			}

			assertThat(returnedElements.size()).isEqualTo(availableElements);
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testListResourceTemplatesInvalidCursor(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var mock = new McpSchema.ResourceTemplate("file://{path}.txt", "test-resource", "Test Resource Description",
				"application/octet-stream", null);

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().resources(true, true).build())
			.resourceTemplates(mock)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatThrownBy(() -> mcpClient.listResourceTemplates("INVALID")).isInstanceOf(McpError.class)
				.hasMessage("Invalid cursor")
				.satisfies(exception -> {
					var error = (McpError) exception;
					assertThat(error.getJsonRpcError().code()).isEqualTo(INVALID_PARAMS);
					assertThat(error.getJsonRpcError().message()).isEqualTo("Invalid cursor");
				});

		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Helpers for Tests of Paginated Lists
	// ---------------------------------------

	/**
	 * Helper function for pagination tests. This provides a stream of the following
	 * parameters: 1. Client type (e.g. httpclient, webflux) 2. Number of available
	 * elements in the list
	 * @return a stream of arguments with test parameters
	 */
	static Stream<Arguments> providePaginationTestParams() {
		return Stream.of(Arguments.of("httpclient", 0), Arguments.of("httpclient", 1), Arguments.of("httpclient", 21),
				Arguments.of("webflux", 0), Arguments.of("webflux", 1), Arguments.of("webflux", 21));
	}

}
