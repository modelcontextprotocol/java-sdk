/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import static io.modelcontextprotocol.spec.McpSchema.ErrorCodes.INVALID_PARAMS;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.McpTransportContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ModelPreferences;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.util.Utils;
import net.javacrumbs.jsonunit.core.Option;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public abstract class AbstractMcpClientServerIntegrationTests {

	protected ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	abstract protected void prepareClients(int port, String mcpEndpoint);

	abstract protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder();

	abstract protected McpServer.SyncSpecification<?> prepareSyncServerBuilder();

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void simple(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(1000))
			.build();

		try (
				// Create client without sampling capabilities
				var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
					.requestTimeout(Duration.ofSeconds(1000))
					.build()) {

			assertThat(client.initialize()).isNotNull();

		}
		server.closeGracefully();
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithoutSamplingCapabilities(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {
				exchange.createMessage(mock(McpSchema.CreateMessageRequest.class)).block();
				return Mono.just(mock(CallToolResult.class));
			})
			.build();

		var server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

		try (
				// Create client without sampling capabilities
				var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
					.build()) {

			assertThat(client.initialize()).isNotNull();

			try {
				client.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class)
					.hasMessage("Client must be configured with sampling capabilities");
			}
		}
		server.closeGracefully();
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

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {

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
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

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
		mcpServer.close();
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

		var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {

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
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(4))
			.tools(tool)
			.build();

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

		mcpClient.close();
		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithRequestTimeoutFail(String clientType) throws InterruptedException {

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

		var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {

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

				return exchange.createMessage(createMessageRequest).thenReturn(callResponse);
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(1))
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
			mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
		}).withMessageContaining("1000ms");

		mcpClient.close();
		mcpServer.close();
	}

	// ---------------------------------------
	// Elicitation Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateElicitationWithoutElicitationCapabilities(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> exchange.createElicitation(mock(ElicitRequest.class))
				.then(Mono.just(mock(CallToolResult.class))))
			.build();

		var server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

		// Create client without elicitation capabilities
		try (var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0")).build()) {

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

		Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();

			return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
					Map.of("message", request.message()));
		};

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {

				var elicitationRequest = McpSchema.ElicitRequest.builder()
					.message("Test message")
					.requestedSchema(
							Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
					.build();

				StepVerifier.create(exchange.createElicitation(elicitationRequest)).consumeNextWith(result -> {
					assertThat(result).isNotNull();
					assertThat(result.action()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
					assertThat(result.content().get("message")).isEqualTo("Test message");
				}).verifyComplete();

				return Mono.just(callResponse);
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

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

		var clientBuilder = clientBuilders.get(clientType);

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();
			return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("message", request.message()));
		};

		var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build();

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		AtomicReference<ElicitResult> resultRef = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {

				var elicitationRequest = McpSchema.ElicitRequest.builder()
					.message("Test message")
					.requestedSchema(
							Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
					.build();

				return exchange.createElicitation(elicitationRequest)
					.doOnNext(resultRef::set)
					.then(Mono.just(callResponse));
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(3))
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo(callResponse);
		assertWith(resultRef.get(), result -> {
			assertThat(result).isNotNull();
			assertThat(result.action()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
			assertThat(result.content().get("message")).isEqualTo("Test message");
		});

		mcpClient.closeGracefully();
		mcpServer.closeGracefully().block();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateElicitationWithRequestTimeoutFail(String clientType) {

		var latch = new CountDownLatch(1);

		var clientBuilder = clientBuilders.get(clientType);

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();

			try {
				if (!latch.await(2, TimeUnit.SECONDS)) {
					throw new RuntimeException("Timeout waiting for elicitation processing");
				}
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

		CallToolResult callResponse = new CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);

		AtomicReference<ElicitResult> resultRef = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {

				var elicitationRequest = ElicitRequest.builder()
					.message("Test message")
					.requestedSchema(
							Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
					.build();

				return exchange.createElicitation(elicitationRequest)
					.doOnNext(resultRef::set)
					.then(Mono.just(callResponse));
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(1)) // 1 second.
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
			mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
		}).withMessageContaining("within 1000ms");

		ElicitResult elicitResult = resultRef.get();
		assertThat(elicitResult).isNull();

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

		var mcpServer = prepareSyncServerBuilder()
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

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {

				exchange.listRoots(); // try to list roots

				return mock(CallToolResult.class);
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

		try (
				// Create client without roots capability
				// No roots capability
				var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()).build()) {

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

		var mcpServer = prepareSyncServerBuilder()
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		try (var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(List.of()) // Empty roots list
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

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

		var mcpServer = prepareSyncServerBuilder()
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef1.set(rootsUpdate))
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef2.set(rootsUpdate))
			.build();

		try (var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			assertThat(mcpClient.initialize()).isNotNull();

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

		var mcpServer = prepareSyncServerBuilder()
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

		var responseBodyIsNullOrBlank = new AtomicBoolean(false);
		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {

				try {
					HttpResponse<String> response = HttpClient.newHttpClient()
						.send(HttpRequest.newBuilder()
							.uri(URI.create(
									"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md"))
							.GET()
							.build(), HttpResponse.BodyHandlers.ofString());
					String responseBody = response.body();
					responseBodyIsNullOrBlank.set(!Utils.hasText(responseBody));
				}
				catch (Exception e) {
					e.printStackTrace();
				}

				return callResponse;
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

			assertThat(responseBodyIsNullOrBlank.get()).isFalse();
			assertThat(response).isNotNull().isEqualTo(callResponse);
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testThrowingToolCallIsCaughtBeforeTimeout(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpSyncServer mcpServer = prepareSyncServerBuilder()
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.SyncToolSpecification.builder()
				.tool(Tool.builder()
					.name("tool1")
					.description("tool1 description")
					.inputSchema(emptyJsonSchema)
					.build())
				.callHandler((exchange, request) -> {
					// We trigger a timeout on blocking read, raising an exception
					Mono.never().block(Duration.ofSeconds(1));
					return null;
				})
				.build())
			.build();

		try (var mcpClient = clientBuilder.requestTimeout(Duration.ofMillis(6666)).build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// We expect the tool call to fail immediately with the exception raised by
			// the offending tool
			// instead of getting back a timeout.
			assertThatExceptionOfType(McpError.class)
				.isThrownBy(() -> mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of())))
				.withMessageContaining("Timeout on blocking read");
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolCallSuccessWithTranportContextExtraction(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var transportContextIsNull = new AtomicBoolean(false);
		var transportContextIsEmpty = new AtomicBoolean(false);
		var responseBodyIsNullOrBlank = new AtomicBoolean(false);

		var expectedCallResponse = new McpSchema.CallToolResult(
				List.of(new McpSchema.TextContent("CALL RESPONSE; ctx=value")), null);
		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {

				McpTransportContext transportContext = exchange.transportContext();
				transportContextIsNull.set(transportContext == null);
				transportContextIsEmpty.set(transportContext.equals(McpTransportContext.EMPTY));
				String ctxValue = (String) transportContext.get("important");

				try {
					String responseBody = "TOOL RESPONSE";
					responseBodyIsNullOrBlank.set(!Utils.hasText(responseBody));
				}
				catch (Exception e) {
					e.printStackTrace();
				}

				return new McpSchema.CallToolResult(
						List.of(new McpSchema.TextContent("CALL RESPONSE; ctx=" + ctxValue)), null);
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

			assertThat(transportContextIsNull.get()).isFalse();
			assertThat(transportContextIsEmpty.get()).isFalse();
			assertThat(responseBodyIsNullOrBlank.get()).isFalse();
			assertThat(response).isNotNull().isEqualTo(expectedCallResponse);
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolListChangeHandlingSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(emptyJsonSchema).build())
			.callHandler((exchange, request) -> {
				// perform a blocking call to a remote service
				try {
					HttpResponse<String> response = HttpClient.newHttpClient()
						.send(HttpRequest.newBuilder()
							.uri(URI.create(
									"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md"))
							.GET()
							.build(), HttpResponse.BodyHandlers.ofString());
					String responseBody = response.body();
					assertThat(responseBody).isNotBlank();
				}
				catch (Exception e) {
					e.printStackTrace();
				}
				return callResponse;
			})
			.build();

		AtomicReference<List<Tool>> toolsRef = new AtomicReference<>();

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			try {
				HttpResponse<String> response = HttpClient.newHttpClient()
					.send(HttpRequest.newBuilder()
						.uri(URI.create(
								"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md"))
						.GET()
						.build(), HttpResponse.BodyHandlers.ofString());
				String responseBody = response.body();
				assertThat(responseBody).isNotBlank();
				toolsRef.set(toolsUpdate);
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(toolsRef.get()).isNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

			mcpServer.notifyToolsListChanged();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(toolsRef.get()).containsAll(List.of(tool1.tool()));
			});

			// Remove a tool
			mcpServer.removeTool("tool1");

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(toolsRef.get()).isEmpty();
			});

			// Add a new tool
			McpServerFeatures.SyncToolSpecification tool2 = McpServerFeatures.SyncToolSpecification.builder()
				.tool(Tool.builder()
					.name("tool2")
					.description("tool2 description")
					.inputSchema(emptyJsonSchema)
					.build())
				.callHandler((exchange, request) -> callResponse)
				.build();

			mcpServer.addTool(tool2);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(toolsRef.get()).containsAll(List.of(tool2.tool()));
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

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
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

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
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

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
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

		var mcpServer = prepareSyncServerBuilder().build();

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
		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("logging-test")
				.description("Test logging notifications")
				.inputSchema(emptyJsonSchema)
				.build())
			.callHandler((exchange, request) -> {

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
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
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
	// Progress Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testProgressNotification(String clientType) throws InterruptedException {
		int expectedNotificationsCount = 4; // 3 notifications + 1 for another progress
		// token
		CountDownLatch latch = new CountDownLatch(expectedNotificationsCount);
		// Create a list to store received logging notifications
		List<McpSchema.ProgressNotification> receivedNotifications = new CopyOnWriteArrayList<>();

		var clientBuilder = clientBuilders.get(clientType);

		// Create server with a tool that sends logging notifications
		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(McpSchema.Tool.builder()
				.name("progress-test")
				.description("Test progress notifications")
				.inputSchema(emptyJsonSchema)
				.build())
			.callHandler((exchange, request) -> {

				// Create and send notifications
				var progressToken = (String) request.meta().get("progressToken");

				return exchange
					.progressNotification(
							new McpSchema.ProgressNotification(progressToken, 0.0, 1.0, "Processing started"))
					.then(exchange.progressNotification(
							new McpSchema.ProgressNotification(progressToken, 0.5, 1.0, "Processing data")))
					.then(// Send a progress notification with another progress value
							// should
							exchange.progressNotification(new McpSchema.ProgressNotification("another-progress-token",
									0.0, 1.0, "Another processing started")))
					.then(exchange.progressNotification(
							new McpSchema.ProgressNotification(progressToken, 1.0, 1.0, "Processing completed")))
					.thenReturn(new CallToolResult(("Progress test completed"), false));
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (
				// Create client with progress notification handler
				var mcpClient = clientBuilder.progressConsumer(notification -> {
					receivedNotifications.add(notification);
					latch.countDown();
				}).build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call the tool that sends progress notifications
			McpSchema.CallToolRequest callToolRequest = McpSchema.CallToolRequest.builder()
				.name("progress-test")
				.meta(Map.of("progressToken", "test-progress-token"))
				.build();
			CallToolResult result = mcpClient.callTool(callToolRequest);
			assertThat(result).isNotNull();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Progress test completed");

			assertThat(latch.await(5, TimeUnit.SECONDS)).as("Should receive notifications in reasonable time").isTrue();

			// Should have received 3 notifications
			assertThat(receivedNotifications).hasSize(expectedNotificationsCount);

			Map<String, McpSchema.ProgressNotification> notificationMap = receivedNotifications.stream()
				.collect(Collectors.toMap(n -> n.message(), n -> n));

			// First notification should be 0.0/1.0 progress
			assertThat(notificationMap.get("Processing started").progressToken()).isEqualTo("test-progress-token");
			assertThat(notificationMap.get("Processing started").progress()).isEqualTo(0.0);
			assertThat(notificationMap.get("Processing started").total()).isEqualTo(1.0);
			assertThat(notificationMap.get("Processing started").message()).isEqualTo("Processing started");

			// Second notification should be 0.5/1.0 progress
			assertThat(notificationMap.get("Processing data").progressToken()).isEqualTo("test-progress-token");
			assertThat(notificationMap.get("Processing data").progress()).isEqualTo(0.5);
			assertThat(notificationMap.get("Processing data").total()).isEqualTo(1.0);
			assertThat(notificationMap.get("Processing data").message()).isEqualTo("Processing data");

			// Third notification should be another progress token with 0.0/1.0 progress
			assertThat(notificationMap.get("Another processing started").progressToken())
				.isEqualTo("another-progress-token");
			assertThat(notificationMap.get("Another processing started").progress()).isEqualTo(0.0);
			assertThat(notificationMap.get("Another processing started").total()).isEqualTo(1.0);
			assertThat(notificationMap.get("Another processing started").message())
				.isEqualTo("Another processing started");

			// Fourth notification should be 1.0/1.0 progress
			assertThat(notificationMap.get("Processing completed").progressToken()).isEqualTo("test-progress-token");
			assertThat(notificationMap.get("Processing completed").progress()).isEqualTo(1.0);
			assertThat(notificationMap.get("Processing completed").total()).isEqualTo(1.0);
			assertThat(notificationMap.get("Processing completed").message()).isEqualTo("Processing completed");
		}
		finally {
			mcpServer.close();
		}
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

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().completions().build())
			.prompts(new McpServerFeatures.SyncPromptSpecification(
					new Prompt("code_review", "Code review", "this is code review prompt",
							List.of(new PromptArgument("language", "Language", "string", false))),
					(mcpSyncServerExchange, getPromptRequest) -> null))
			.completions(new McpServerFeatures.SyncCompletionSpecification(
					new McpSchema.PromptReference("ref/prompt", "code_review", "Code review"), completionHandler))
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CompleteRequest request = new CompleteRequest(
					new PromptReference("ref/prompt", "code_review", "Code review"),
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
	// Ping Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testPingSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		// Create server with a tool that uses ping functionality
		AtomicReference<String> executionOrder = new AtomicReference<>("");

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("ping-async-test")
				.description("Test ping async behavior")
				.inputSchema(emptyJsonSchema)
				.build())
			.callHandler((exchange, request) -> {

				executionOrder.set(executionOrder.get() + "1");

				// Test async ping behavior
				return exchange.ping().doOnNext(result -> {

					assertThat(result).isNotNull();
					// Ping should return an empty object or map
					assertThat(result).isInstanceOf(Map.class);

					executionOrder.set(executionOrder.get() + "2");
					assertThat(result).isNotNull();
				}).then(Mono.fromCallable(() -> {
					executionOrder.set(executionOrder.get() + "3");
					return new CallToolResult("Async ping test completed", false);
				}));
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call the tool that tests ping async behavior
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("ping-async-test", Map.of()));
			assertThat(result).isNotNull();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Async ping test completed");

			// Verify execution order
			assertThat(executionOrder.get()).isEqualTo("123");
		}

		mcpServer.close();
	}

	// ---------------------------------------
	// Tool Structured Output Schema Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputValidationSuccess(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> outputSchema = Map.of(
				"type", "object", "properties", Map.of("result", Map.of("type", "number"), "operation",
						Map.of("type", "string"), "timestamp", Map.of("type", "string")),
				"required", List.of("result", "operation"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> {
				String expression = (String) request.arguments().getOrDefault("expression", "2 + 3");
				double result = evaluateExpression(expression);
				return CallToolResult.builder()
					.structuredContent(
							Map.of("result", result, "operation", expression, "timestamp", "2024-01-01T10:00:00Z"))
					.build();
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Verify tool is listed with output schema
			var toolsList = mcpClient.listTools();
			assertThat(toolsList.tools()).hasSize(1);
			assertThat(toolsList.tools().get(0).name()).isEqualTo("calculator");
			// Note: outputSchema might be null in sync server, but validation still works

			// Call tool with valid structured output
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isFalse();

			// In WebMVC, structured content is returned properly
			if (response.structuredContent() != null) {
				assertThat(response.structuredContent()).containsEntry("result", 5.0)
					.containsEntry("operation", "2 + 3")
					.containsEntry("timestamp", "2024-01-01T10:00:00Z");
			}
			else {
				// Fallback to checking content if structured content is not available
				assertThat(response.content()).isNotEmpty();
			}

			assertThat(response.structuredContent()).isNotNull();
			assertThatJson(response.structuredContent()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("""
						{"result":5.0,"operation":"2 + 3","timestamp":"2024-01-01T10:00:00Z"}"""));
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputValidationFailure(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> outputSchema = Map.of("type", "object", "properties",
				Map.of("result", Map.of("type", "number"), "operation", Map.of("type", "string")), "required",
				List.of("result", "operation"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> {
				// Return invalid structured output. Result should be number, missing
				// operation
				return CallToolResult.builder()
					.addTextContent("Invalid calculation")
					.structuredContent(Map.of("result", "not-a-number", "extra", "field"))
					.build();
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call tool with invalid structured output
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isTrue();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);

			String errorMessage = ((McpSchema.TextContent) response.content().get(0)).text();
			assertThat(errorMessage).contains("Validation failed");
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputMissingStructuredContent(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> outputSchema = Map.of("type", "object", "properties",
				Map.of("result", Map.of("type", "number")), "required", List.of("result"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> {
				// Return result without structured content but tool has output schema
				return CallToolResult.builder().addTextContent("Calculation completed").build();
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call tool that should return structured content but doesn't
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isTrue();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);

			String errorMessage = ((McpSchema.TextContent) response.content().get(0)).text();
			assertThat(errorMessage).isEqualTo(
					"Response missing structured content which is expected when calling tool with non-empty outputSchema");
		}

		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testStructuredOutputRuntimeToolAddition(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		// Start server without tools
		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		try (var mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Initially no tools
			assertThat(mcpClient.listTools().tools()).isEmpty();

			// Add tool with output schema at runtime
			Map<String, Object> outputSchema = Map.of("type", "object", "properties",
					Map.of("message", Map.of("type", "string"), "count", Map.of("type", "integer")), "required",
					List.of("message", "count"));

			Tool dynamicTool = Tool.builder()
				.name("dynamic-tool")
				.description("Dynamically added tool")
				.outputSchema(outputSchema)
				.build();

			McpServerFeatures.SyncToolSpecification toolSpec = McpServerFeatures.SyncToolSpecification.builder()
				.tool(dynamicTool)
				.callHandler((exchange, request) -> {
					int count = (Integer) request.arguments().getOrDefault("count", 1);
					return CallToolResult.builder()
						.addTextContent("Dynamic tool executed " + count + " times")
						.structuredContent(Map.of("message", "Dynamic execution", "count", count))
						.build();
				})
				.build();

			// Add tool to server
			mcpServer.addTool(toolSpec);

			// Wait for tool list change notification
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(mcpClient.listTools().tools()).hasSize(1);
			});

			// Verify tool was added with output schema
			var toolsList = mcpClient.listTools();
			assertThat(toolsList.tools()).hasSize(1);
			assertThat(toolsList.tools().get(0).name()).isEqualTo("dynamic-tool");
			// Note: outputSchema might be null in sync server, but validation still works

			// Call dynamically added tool
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("dynamic-tool", Map.of("count", 3)));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isFalse();

			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) response.content().get(0)).text())
				.isEqualTo("Dynamic tool executed 3 times");

			assertThat(response.structuredContent()).isNotNull();
			assertThatJson(response.structuredContent()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("""
						{"count":3,"message":"Dynamic execution"}"""));
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

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().prompts(true).build())
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

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().prompts(true).build())
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

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().prompts(true).build())
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

		var mcpServer = prepareSyncServerBuilder()
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

		var mcpServer = prepareSyncServerBuilder()
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

		var mcpServer = prepareSyncServerBuilder()
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

		var mcpServer = prepareSyncServerBuilder()
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

		var mcpServer = prepareSyncServerBuilder()
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

	private double evaluateExpression(String expression) {
		// Simple expression evaluator for testing
		return switch (expression) {
			case "2 + 3" -> 5.0;
			case "10 * 2" -> 20.0;
			case "7 + 8" -> 15.0;
			case "5 + 3" -> 8.0;
			default -> 0.0;
		};
	}

}
