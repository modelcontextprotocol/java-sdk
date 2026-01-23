/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.experimental.tasks.InMemoryTaskMessageQueue;
import io.modelcontextprotocol.experimental.tasks.InMemoryTaskStore;
import io.modelcontextprotocol.experimental.tasks.TaskAwareAsyncToolSpecification;
import io.modelcontextprotocol.experimental.tasks.TaskMessageQueue;
import io.modelcontextprotocol.experimental.tasks.TaskStore;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
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
import io.modelcontextprotocol.spec.McpSchema.ResponseMessage;
import io.modelcontextprotocol.spec.McpSchema.ResultMessage;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.ServerTaskCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TaskCreatedMessage;
import io.modelcontextprotocol.spec.McpSchema.TaskMetadata;
import io.modelcontextprotocol.spec.McpSchema.TaskStatus;
import io.modelcontextprotocol.spec.McpSchema.TaskStatusMessage;
import io.modelcontextprotocol.spec.McpSchema.TaskSupportMode;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.util.Utils;
import net.javacrumbs.jsonunit.core.Option;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertWith;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

// KEEP IN SYNC with the class in mcp-core module
public abstract class AbstractMcpClientServerIntegrationTests {

	protected ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	abstract protected void prepareClients(int port, String mcpEndpoint);

	abstract protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder();

	abstract protected McpServer.SyncSpecification<?> prepareSyncServerBuilder();

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
		finally {
			server.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateMessageWithoutSamplingCapabilities(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
			.callHandler((exchange, request) -> {
				return exchange.createMessage(mock(McpSchema.CreateMessageRequest.class))
					.then(Mono.just(mock(CallToolResult.class)));
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
		finally {
			server.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateMessageSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.TextContent("CALL RESPONSE"))
			.build();

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.TextContent("CALL RESPONSE"))
			.build();

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.TextContent("CALL RESPONSE"))
			.build();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
			}).withMessageContaining("1000ms");
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Elicitation Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateElicitationWithoutElicitationCapabilities(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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
		finally {
			server.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateElicitationSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();

			return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
					Map.of("message", request.message()));
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.TextContent("CALL RESPONSE"))
			.build();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateElicitationWithRequestTimeoutSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();
			return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("message", request.message()));
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.TextContent("CALL RESPONSE"))
			.build();

		AtomicReference<ElicitResult> resultRef = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build()) {

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
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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

		CallToolResult callResponse = CallToolResult.builder().addContent(new TextContent("CALL RESPONSE")).build();

		AtomicReference<ElicitResult> resultRef = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
			}).withMessageContaining("within 1000ms");

			ElicitResult elicitResult = resultRef.get();
			assertThat(elicitResult).isNull();
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testRootsWithoutCapability(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testToolCallSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var responseBodyIsNullOrBlank = new AtomicBoolean(false);
		var callResponse = McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.TextContent("CALL RESPONSE; ctx=importantValue"))
			.build();
		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testThrowingToolCallIsCaughtBeforeTimeout(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		McpSyncServer mcpServer = prepareSyncServerBuilder()
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.SyncToolSpecification.builder()
				.tool(Tool.builder()
					.name("tool1")
					.description("tool1 description")
					.inputSchema(EMPTY_JSON_SCHEMA)
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
			// the offending tool instead of getting back a timeout.
			assertThatExceptionOfType(McpError.class)
				.isThrownBy(() -> mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of())))
				.withMessageContaining("Timeout on blocking read");
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testToolCallSuccessWithTranportContextExtraction(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var transportContextIsNull = new AtomicBoolean(false);
		var transportContextIsEmpty = new AtomicBoolean(false);
		var responseBodyIsNullOrBlank = new AtomicBoolean(false);

		var expectedCallResponse = McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.TextContent("CALL RESPONSE; ctx=value"))
			.build();
		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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

				return McpSchema.CallToolResult.builder()
					.addContent(new McpSchema.TextContent("CALL RESPONSE; ctx=" + ctxValue))
					.build();
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testToolListChangeHandlingSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var callResponse = McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.TextContent("CALL RESPONSE"))
			.build();

		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
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
					.inputSchema(EMPTY_JSON_SCHEMA)
					.build())
				.callHandler((exchange, request) -> callResponse)
				.build();

			mcpServer.addTool(tool2);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(toolsRef.get()).containsAll(List.of(tool2.tool()));
			});
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testInitialize(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var mcpServer = prepareSyncServerBuilder().build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
				.inputSchema(EMPTY_JSON_SCHEMA)
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
					.thenReturn(CallToolResult.builder()
						.content(List.of(new McpSchema.TextContent("Logging test completed")))
						.isError(false)
						.build());
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
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Progress Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
				.inputSchema(EMPTY_JSON_SCHEMA)
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
					.thenReturn(CallToolResult.builder()
						.content(List.of(new McpSchema.TextContent("Progress test completed")))
						.isError(false)
						.build());
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
			mcpServer.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Completion Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : Completion call")
	@MethodSource("clientsForTesting")
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
					new McpSchema.PromptReference(PromptReference.TYPE, "code_review", "Code review"),
					completionHandler))
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CompleteRequest request = new CompleteRequest(
					new PromptReference(PromptReference.TYPE, "code_review", "Code review"),
					new CompleteRequest.CompleteArgument("language", "py"));

			CompleteResult result = mcpClient.completeCompletion(request);

			assertThat(result).isNotNull();

			assertThat(samplingRequest.get().argument().name()).isEqualTo("language");
			assertThat(samplingRequest.get().argument().value()).isEqualTo("py");
			assertThat(samplingRequest.get().ref().type()).isEqualTo(PromptReference.TYPE);
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	// ---------------------------------------
	// Ping Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testPingSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		// Create server with a tool that uses ping functionality
		AtomicReference<String> executionOrder = new AtomicReference<>("");

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("ping-async-test")
				.description("Test ping async behavior")
				.inputSchema(EMPTY_JSON_SCHEMA)
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
					return CallToolResult.builder()
						.content(List.of(new McpSchema.TextContent("Async ping test completed")))
						.isError(false)
						.build();
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
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Tool Structured Output Schema Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
				assertThat((Map<String, Object>) response.structuredContent()).containsEntry("result", 5.0)
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputOfObjectArrayValidationSuccess(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema that returns an array of objects
		Map<String, Object> outputSchema = Map
			.of( // @formatter:off
			"type", "array",
			"items", Map.of(
				"type", "object",
				"properties", Map.of(
					"name", Map.of("type", "string"),
					"age", Map.of("type", "number")),					
				"required", List.of("name", "age"))); // @formatter:on

		Tool calculatorTool = Tool.builder()
			.name("getMembers")
			.description("Returns a list of members")
			.outputSchema(outputSchema)
			.build();

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> {
				return CallToolResult.builder()
					.structuredContent(List.of(Map.of("name", "John", "age", 30), Map.of("name", "Peter", "age", 25)))
					.build();
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (var mcpClient = clientBuilder.build()) {
			assertThat(mcpClient.initialize()).isNotNull();

			// Call tool with valid structured output of type array
			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("getMembers", Map.of()));

			assertThat(response).isNotNull();
			assertThat(response.isError()).isFalse();

			assertThat(response.structuredContent()).isNotNull();
			assertThatJson(response.structuredContent()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isArray()
				.hasSize(2)
				.containsExactlyInAnyOrder(json("""
						{"name":"John","age":30}"""), json("""
						{"name":"Peter","age":25}"""));
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputWithInHandlerError(String clientType) {
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

		// Handler that returns an error result
		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> CallToolResult.builder()
				.isError(true)
				.content(List.of(new TextContent("Error calling tool: Simulated in-handler error")))
				.build())
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
			assertThat(response.isError()).isTrue();
			assertThat(response.content()).isNotEmpty();
			assertThat(response.content())
				.containsExactly(new McpSchema.TextContent("Error calling tool: Simulated in-handler error"));
			assertThat(response.structuredContent()).isNull();
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
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
		finally {
			mcpServer.closeGracefully();
		}
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

	// ===================================================================
	// Task Lifecycle Integration Tests
	// ===================================================================

	/** Default server capabilities with tasks enabled for task lifecycle tests. */
	protected static final ServerCapabilities TASK_SERVER_CAPABILITIES = ServerCapabilities.builder()
		.tasks(ServerTaskCapabilities.builder().list().cancel().toolsCall().build())
		.tools(true)
		.build();

	/** Default client capabilities with tasks enabled for task lifecycle tests. */
	protected static final ClientCapabilities TASK_CLIENT_CAPABILITIES = ClientCapabilities.builder()
		.tasks(ClientCapabilities.ClientTaskCapabilities.builder().list().cancel().build())
		.build();

	/** Default task metadata for test calls. */
	protected static final TaskMetadata DEFAULT_TASK_METADATA = TaskMetadata.builder()
		.ttl(Duration.ofMillis(60000L))
		.build();

	/** Default request timeout for task test clients. */
	protected static final Duration TASK_REQUEST_TIMEOUT = Duration.ofSeconds(30);

	/** Creates a server with task capabilities and the given tools. */
	protected McpAsyncServer createTaskServer(TaskStore<McpSchema.ServerTaskPayloadResult> taskStore,
			TaskAwareAsyncToolSpecification... taskTools) {
		return createTaskServer(taskStore, null, taskTools);
	}

	/** Creates a server with task capabilities, message queue, and task-aware tools. */
	protected McpAsyncServer createTaskServer(TaskStore<McpSchema.ServerTaskPayloadResult> taskStore,
			TaskMessageQueue messageQueue, TaskAwareAsyncToolSpecification... taskTools) {
		var builder = prepareAsyncServerBuilder().serverInfo("task-test-server", "1.0.0")
			.capabilities(TASK_SERVER_CAPABILITIES)
			.taskStore(taskStore);

		if (messageQueue != null) {
			builder.taskMessageQueue(messageQueue);
		}

		if (taskTools != null && taskTools.length > 0) {
			builder.taskTools(taskTools);
		}

		return builder.build();
	}

	/** Creates a client with task capabilities. */
	protected McpSyncClient createTaskClient(String clientType, String name) {
		return createTaskClient(clientType, name, TASK_CLIENT_CAPABILITIES, null);
	}

	/** Creates a client with custom capabilities and optional elicitation handler. */
	protected McpSyncClient createTaskClient(String clientType, String name, ClientCapabilities capabilities,
			Function<ElicitRequest, ElicitResult> elicitationHandler) {
		var builder = clientBuilders.get(clientType)
			.clientInfo(new McpSchema.Implementation(name, "0.0.0"))
			.capabilities(capabilities)
			.requestTimeout(TASK_REQUEST_TIMEOUT);

		if (elicitationHandler != null) {
			builder.elicitation(elicitationHandler);
		}

		return builder.build();
	}

	/** Extracts the task ID from a list of response messages. */
	protected String extractTaskId(List<ResponseMessage<CallToolResult>> messages) {
		for (var msg : messages) {
			if (msg instanceof TaskCreatedMessage<CallToolResult> tcm) {
				return tcm.task().taskId();
			}
		}
		return null;
	}

	/** Extracts all task statuses from a list of response messages. */
	protected List<TaskStatus> extractTaskStatuses(List<ResponseMessage<CallToolResult>> messages) {
		List<TaskStatus> statuses = new ArrayList<>();
		for (var msg : messages) {
			if (msg instanceof TaskCreatedMessage<CallToolResult> tcm) {
				statuses.add(tcm.task().status());
			}
			else if (msg instanceof TaskStatusMessage<CallToolResult> tsm) {
				statuses.add(tsm.task().status());
			}
		}
		return statuses;
	}

	/**
	 * Asserts that task status transitions are valid (no transitions from terminal
	 * states).
	 */
	protected void assertValidStateTransitions(List<TaskStatus> statuses) {
		TaskStatus previousState = null;
		for (TaskStatus state : statuses) {
			if (previousState != null) {
				assertThat(previousState).as("Terminal states cannot transition to other states")
					.isNotIn(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED);
			}
			previousState = state;
		}
	}

	// ===== Task-Augmented Tool Call Tests =====

	/**
	 * Demonstrates wrapping an external async API with tasks.
	 *
	 * <p>
	 * Tasks are designed for external services that process jobs asynchronously. Status
	 * checks happen lazily when the client polls - no background threads needed.
	 */
	@ParameterizedTest(name = "{0} : {displayName}")
	@MethodSource("clientsForTesting")
	void testExternalAsyncApiPattern(String clientType) throws InterruptedException {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();

		// Simulates an external async API (e.g., a batch processing service)
		var externalApi = new SimulatedExternalAsyncApi();

		var tool = TaskAwareAsyncToolSpecification.builder()
			.name("external-job")
			.description("Submits work to an external async API")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.taskSupportMode(TaskSupportMode.OPTIONAL)
			.createTaskHandler((args, extra) -> {
				// Submit job to external API and use its ID as the MCP task ID
				String externalJobId = externalApi.submitJob((String) args.get("input"));
				return extra.createTask(opts -> opts.taskId(externalJobId))
					.map(task -> McpSchema.CreateTaskResult.builder().task(task).build());
			})
			.getTaskHandler((exchange, request) -> {
				// request.taskId() IS the external job ID - no mapping needed!
				SimulatedExternalAsyncApi.JobStatus status = externalApi.checkStatus(request.taskId());
				TaskStatus mcpStatus = switch (status) {
					case PENDING, RUNNING -> TaskStatus.WORKING;
					case COMPLETED -> TaskStatus.COMPLETED;
					case FAILED -> TaskStatus.FAILED;
				};

				// Get timestamps from the TaskStore
				return taskStore.getTask(request.taskId(), null)
					.map(storeResult -> McpSchema.GetTaskResult.builder()
						.taskId(request.taskId())
						.status(mcpStatus)
						.statusMessage(status.toString())
						.createdAt(storeResult.task().createdAt())
						.lastUpdatedAt(storeResult.task().lastUpdatedAt())
						.ttl(storeResult.task().ttl())
						.pollInterval(storeResult.task().pollInterval())
						.build());
			})
			.getTaskResultHandler((exchange, request) -> {
				// request.taskId() IS the external job ID
				String result = externalApi.getResult(request.taskId());
				return Mono.just(CallToolResult.builder().addTextContent(result).build());
			})
			.build();

		var server = createTaskServer(taskStore, tool);

		try (var client = createTaskClient(clientType, "External API Client")) {
			client.initialize();

			// Submit job via tool call
			var request = new McpSchema.CallToolRequest("external-job", Map.of("input", "test-data"),
					DEFAULT_TASK_METADATA, null);
			var createResult = client.callToolTask(request);

			assertThat(createResult.task()).isNotNull();
			String taskId = createResult.task().taskId();

			// Poll until external job completes
			await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
				var task = client.getTask(taskId);
				assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
			});

			// Fetch result
			var result = client.getTaskResult(taskId, new TypeRef<CallToolResult>() {
			});

			assertThat(result.content()).hasSize(1);
			assertThat(((TextContent) result.content().get(0)).text()).contains("Processed: test-data");
		}
		finally {
			server.closeGracefully().block();
		}
	}

	/**
	 * Simulates an external async API (e.g., batch processing, ML inference).
	 */
	static class SimulatedExternalAsyncApi {

		enum JobStatus {

			PENDING, RUNNING, COMPLETED, FAILED

		}

		private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

		private record JobState(String input, long completionTime) {
		}

		String submitJob(String input) {
			String jobId = "job-" + UUID.randomUUID().toString().substring(0, 8);
			jobs.put(jobId, new JobState(input, System.currentTimeMillis() + 300));
			return jobId;
		}

		JobStatus checkStatus(String jobId) {
			JobState state = jobs.get(jobId);
			if (state == null) {
				return JobStatus.FAILED;
			}
			return System.currentTimeMillis() >= state.completionTime ? JobStatus.COMPLETED : JobStatus.RUNNING;
		}

		String getResult(String jobId) {
			JobState state = jobs.get(jobId);
			return state != null ? "Processed: " + state.input : "Error: job not found";
		}

	}

	// ===== List Tasks Tests =====

	@ParameterizedTest(name = "{0} : {displayName}")
	@MethodSource("clientsForTesting")
	void testListTasks(String clientType) {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		try (var client = createTaskClient(clientType, "Task Test Client")) {
			client.initialize();

			var result = client.listTasks();
			assertThat(result).isNotNull();
			assertThat(result.tasks()).isNotNull();
		}
		finally {
			server.closeGracefully().block();
		}
	}

	// ===== INPUT_REQUIRED and Elicitation Flow Tests =====

	/**
	 * Test: Elicitation during task execution.
	 *
	 * <p>
	 * This test demonstrates the elicitation flow during task execution:
	 * <ol>
	 * <li>Client calls task-augmented tool
	 * <li>Tool creates task in WORKING state
	 * <li>Tool needs user input → sends elicitation request
	 * <li>Client responds to elicitation
	 * <li>Task continues → COMPLETED
	 * </ol>
	 */
	@ParameterizedTest(name = "{0} : {displayName}")
	@MethodSource("clientsForTesting")
	void testElicitationDuringTaskExecution(String clientType) throws InterruptedException {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		TaskMessageQueue messageQueue = new InMemoryTaskMessageQueue();

		AtomicReference<String> taskIdRef = new AtomicReference<>();
		AtomicReference<String> elicitationResponse = new AtomicReference<>();
		CountDownLatch elicitationReceivedLatch = new CountDownLatch(1);

		// Tool that needs user input during execution
		BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<CallToolResult>> handler = (exchange,
				request) -> {
			String taskId = exchange.getCurrentTaskId();
			if (taskId == null) {
				return Mono.error(new RuntimeException("Task ID not available"));
			}
			taskIdRef.set(taskId);

			return exchange.createElicitation(new ElicitRequest("Please provide a number:", null, null, null))
				.doOnNext(result -> {
					elicitationResponse.set(result.content() != null && !result.content().isEmpty()
							? result.content().get("value").toString() : "no-response");
					elicitationReceivedLatch.countDown();
				})
				.then(Mono.defer(() -> Mono.just(CallToolResult.builder()
					.content(List.of(new TextContent("Got user input: " + elicitationResponse.get())))
					.isError(false)
					.build())));
		};

		var tool = TaskAwareAsyncToolSpecification.builder()
			.name("needs-input-tool")
			.description("Test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.taskSupportMode(TaskSupportMode.REQUIRED)
			.createTaskHandler((args, extra) -> extra.createTask().flatMap(task -> {
				McpSchema.CallToolRequest syntheticRequest = new McpSchema.CallToolRequest("needs-input-tool", args,
						null, null);
				return handler.apply(extra.exchange().withTaskContext(task.taskId()), syntheticRequest)
					.flatMap(result -> extra.taskStore()
						.storeTaskResult(task.taskId(), extra.sessionId(), TaskStatus.COMPLETED, result)
						.thenReturn(task))
					.onErrorResume(error -> extra.taskStore()
						.updateTaskStatus(task.taskId(), extra.sessionId(), TaskStatus.FAILED, error.getMessage())
						.thenReturn(task));
			}).map(task -> McpSchema.CreateTaskResult.builder().task(task).build()))
			.build();

		var server = createTaskServer(taskStore, messageQueue, tool);

		ClientCapabilities elicitationCapabilities = ClientCapabilities.builder()
			.elicitation()
			.tasks(ClientCapabilities.ClientTaskCapabilities.builder().list().cancel().build())
			.build();

		try (var client = createTaskClient(clientType, "Elicitation Test Client", elicitationCapabilities,
				(elicitRequest) -> new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("value", "42"), null))) {
			client.initialize();

			var request = new McpSchema.CallToolRequest("needs-input-tool", Map.of(), DEFAULT_TASK_METADATA, null);
			var messages = client.callToolStream(request).toList();
			var observedStates = extractTaskStatuses(messages);

			if (taskIdRef.get() != null) {
				boolean elicitationCompleted = elicitationReceivedLatch.await(10, TimeUnit.SECONDS);
				assertThat(elicitationCompleted).as("Elicitation should be received and processed").isTrue();

				await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
					var task = client.getTask(McpSchema.GetTaskRequest.builder().taskId(taskIdRef.get()).build());
					assertThat(task.status()).isIn(TaskStatus.COMPLETED, TaskStatus.FAILED);
				});

				assertThat(elicitationResponse.get()).isEqualTo("42");
				assertValidStateTransitions(observedStates);
			}
		}
		finally {
			server.closeGracefully().block();
		}
	}

	// ===== Task Capability Negotiation Tests =====

	@ParameterizedTest(name = "{0} : {displayName}")
	@MethodSource("clientsForTesting")
	void testServerReportsTaskCapabilities(String clientType) {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();
		var server = createTaskServer(taskStore);

		try (var client = createTaskClient(clientType, "Task Test Client")) {
			var initResult = client.initialize();
			assertThat(initResult).isNotNull();
			assertThat(initResult.capabilities()).isNotNull();
			assertThat(initResult.capabilities().tasks()).isNotNull();
		}
		finally {
			server.closeGracefully().block();
		}
	}

	// ===== Automatic Polling Shim Tests =====

	/**
	 * Tests the automatic polling shim: when a tool with createTaskHandler is called
	 * WITHOUT task metadata, the server should automatically create a task, poll until
	 * completion, and return the final result directly.
	 */
	@ParameterizedTest(name = "{0} : {displayName}")
	@MethodSource("clientsForTesting")
	void testAutomaticPollingShimWithCreateTaskHandler(String clientType) {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();

		// The tool creates a task, stores result immediately, and returns
		var tool = TaskAwareAsyncToolSpecification.builder()
			.name("auto-polling-tool")
			.description("A tool that uses createTaskHandler")
			.taskSupportMode(TaskSupportMode.OPTIONAL)
			.createTaskHandler((args, extra) -> extra.createTask(opts -> opts.requestedTtl(60000L).pollInterval(100L))
				.flatMap(task -> {
					// Immediately store result (simulating fast completion)
					CallToolResult result = CallToolResult.builder()
						.addTextContent("Result from createTaskHandler: " + args.getOrDefault("input", "default"))
						.isError(false)
						.build();
					return extra.taskStore()
						.storeTaskResult(task.taskId(), extra.sessionId(), TaskStatus.COMPLETED, result)
						.thenReturn(McpSchema.CreateTaskResult.builder().task(task).build());
				}))
			.build();

		var server = createTaskServer(taskStore, tool);

		try (var client = createTaskClient(clientType, "Auto Polling Test Client", ClientCapabilities.builder().build(),
				null)) {
			client.initialize();

			// Call tool WITHOUT task metadata - should trigger automatic polling shim
			var request = new McpSchema.CallToolRequest("auto-polling-tool", Map.of("input", "test-value"), null, null);
			var messages = client.callToolStream(request).toList();

			// The automatic polling shim should poll and return the final result
			assertThat(messages).as("Should have response messages").isNotEmpty();

			// The last message should be a ResultMessage with the final CallToolResult
			ResponseMessage<CallToolResult> lastMsg = messages.get(messages.size() - 1);
			assertThat(lastMsg).as("Last message should be ResultMessage").isInstanceOf(ResultMessage.class);

			ResultMessage<CallToolResult> resultMsg = (ResultMessage<CallToolResult>) lastMsg;
			assertThat(resultMsg.result()).isNotNull();
			assertThat(resultMsg.result().content()).isNotEmpty();

			// Verify the content came from our createTaskHandler
			TextContent textContent = (TextContent) resultMsg.result().content().get(0);
			assertThat(textContent.text()).contains("Result from createTaskHandler").contains("test-value");
		}
		finally {
			server.closeGracefully().block();
		}
	}

	/**
	 * Tests that a tool with createTaskHandler still works correctly when called WITH
	 * task metadata (the normal task-augmented flow).
	 */
	@ParameterizedTest(name = "{0} : {displayName}")
	@MethodSource("clientsForTesting")
	void testCreateTaskHandlerWithTaskMetadata(String clientType) {
		TaskStore<McpSchema.ServerTaskPayloadResult> taskStore = new InMemoryTaskStore<>();

		// Track if createTaskHandler was invoked
		AtomicBoolean createTaskHandlerInvoked = new AtomicBoolean(false);

		var tool = TaskAwareAsyncToolSpecification.builder()
			.name("create-task-tool")
			.description("A tool that uses createTaskHandler")
			.taskSupportMode(TaskSupportMode.OPTIONAL)
			.createTaskHandler((args, extra) -> {
				createTaskHandlerInvoked.set(true);

				return extra.createTask(opts -> opts.pollInterval(500L)).flatMap(task -> {
					// Store result immediately
					CallToolResult result = CallToolResult.builder()
						.addTextContent("Task created via createTaskHandler!")
						.isError(false)
						.build();
					return extra.taskStore()
						.storeTaskResult(task.taskId(), extra.sessionId(), TaskStatus.COMPLETED, result)
						.thenReturn(McpSchema.CreateTaskResult.builder().task(task).build());
				});
			})
			.build();

		var server = createTaskServer(taskStore, tool);

		try (var client = createTaskClient(clientType, "CreateTask Test Client")) {
			client.initialize();

			// Call with task metadata - should use createTaskHandler directly
			var request = new McpSchema.CallToolRequest("create-task-tool", Map.of(), DEFAULT_TASK_METADATA, null);
			var messages = client.callToolStream(request).toList();

			assertThat(createTaskHandlerInvoked.get()).as("createTaskHandler should have been invoked").isTrue();
			assertThat(messages).as("Should have response messages").isNotEmpty();

			// Should have task creation and result messages
			String taskId = extractTaskId(messages);
			assertThat(taskId).as("Should have created a task").isNotNull();
		}
		finally {
			server.closeGracefully().block();
		}
	}

	// ===== Client-Side Task Hosting Tests =====

	/**
	 * Test: Client-side task hosting for sampling requests.
	 *
	 * <p>
	 * This test verifies that when a server sends a task-augmented sampling request to a
	 * client that has a taskStore configured, the client correctly:
	 * <ol>
	 * <li>Creates a task in its local taskStore
	 * <li>Returns CreateTaskResult immediately
	 * <li>Executes the sampling handler in the background
	 * <li>Stores the result when complete
	 * </ol>
	 */
	@ParameterizedTest(name = "{0} : {displayName}")
	@MethodSource("clientsForTesting")
	void testClientSideTaskHostingForSampling(String clientType) throws InterruptedException {
		CountDownLatch samplingHandlerInvoked = new CountDownLatch(1);
		AtomicReference<String> receivedPrompt = new AtomicReference<>();

		// Create a server with a tool that sends task-augmented sampling to client
		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("trigger-sampling")
				.description("Triggers a task-augmented sampling request to client")
				.inputSchema(EMPTY_JSON_SCHEMA)
				.build())
			.callHandler((exchange, request) -> {
				// Send task-augmented sampling request to client
				CreateMessageRequest samplingRequest = McpSchema.CreateMessageRequest.builder()
					.messages(List.of(new McpSchema.SamplingMessage(Role.USER, new TextContent("Test prompt"))))
					.systemPrompt("system-prompt")
					.maxTokens(100)
					.task(TaskMetadata.builder().ttl(Duration.ofMillis(30000L)).build())
					.build();

				return exchange.createMessageTask(samplingRequest).flatMap(createTaskResult -> {
					// Poll for task completion
					String taskId = createTaskResult.task().taskId();
					return pollForTaskCompletion(exchange, taskId, new TypeRef<CreateMessageResult>() {
					}).map(result -> CallToolResult.builder()
						.content(List.of(new TextContent("Sampling task completed: " + taskId)))
						.build());
				});
			})
			.build();

		var server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		// Create client with taskStore for hosting tasks
		TaskStore<McpSchema.ClientTaskPayloadResult> clientTaskStore = new InMemoryTaskStore<>();
		var clientBuilder = clientBuilders.get(clientType)
			.clientInfo(new McpSchema.Implementation("Task-Hosting Client", "1.0.0"))
			.capabilities(ClientCapabilities.builder()
				.sampling()
				.tasks(ClientCapabilities.ClientTaskCapabilities.builder()
					.list()
					.cancel()
					.samplingCreateMessage()
					.build())
				.build())
			.taskStore(clientTaskStore)
			.sampling(request -> {
				receivedPrompt.set(request.systemPrompt());
				samplingHandlerInvoked.countDown();
				return new CreateMessageResult(Role.ASSISTANT, new TextContent("Test response"), "model-id",
						CreateMessageResult.StopReason.END_TURN);
			});

		try (var client = clientBuilder.build()) {
			client.initialize();

			// Trigger the tool which will send task-augmented sampling to client
			var result = client.callTool(new McpSchema.CallToolRequest("trigger-sampling", Map.of()));

			// Verify sampling handler was invoked
			boolean handlerInvoked = samplingHandlerInvoked.await(10, TimeUnit.SECONDS);
			assertThat(handlerInvoked).as("Sampling handler should have been invoked").isTrue();
			assertThat(receivedPrompt.get()).isEqualTo("system-prompt");

			// Verify the tool completed successfully
			assertThat(result.content()).isNotEmpty();
		}
		finally {
			server.closeGracefully().block();
		}
	}

	/**
	 * Test: Client-side task hosting for elicitation requests.
	 *
	 * <p>
	 * Similar to sampling, verifies task-augmented elicitation works correctly when the
	 * client has a taskStore configured.
	 */
	@ParameterizedTest(name = "{0} : {displayName}")
	@MethodSource("clientsForTesting")
	void testClientSideTaskHostingForElicitation(String clientType) throws InterruptedException {
		CountDownLatch elicitationHandlerInvoked = new CountDownLatch(1);
		AtomicReference<String> receivedMessage = new AtomicReference<>();

		// Create a server with a tool that sends task-augmented elicitation to client
		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("trigger-elicitation")
				.description("Triggers a task-augmented elicitation request to client")
				.inputSchema(EMPTY_JSON_SCHEMA)
				.build())
			.callHandler((exchange, request) -> {
				// Send task-augmented elicitation request to client
				ElicitRequest elicitRequest = McpSchema.ElicitRequest.builder()
					.message("Please enter your name:")
					.task(TaskMetadata.builder().ttl(Duration.ofMillis(30000L)).build())
					.build();

				return exchange.createElicitationTask(elicitRequest).flatMap(createTaskResult -> {
					// Poll for task completion
					String taskId = createTaskResult.task().taskId();
					return pollForTaskCompletion(exchange, taskId, new TypeRef<ElicitResult>() {
					}).map(result -> CallToolResult.builder()
						.content(List.of(new TextContent("Elicitation task completed: " + taskId)))
						.build());
				});
			})
			.build();

		var server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		// Create client with taskStore for hosting tasks
		TaskStore<McpSchema.ClientTaskPayloadResult> clientTaskStore = new InMemoryTaskStore<>();
		var clientBuilder = clientBuilders.get(clientType)
			.clientInfo(new McpSchema.Implementation("Task-Hosting Client", "1.0.0"))
			.capabilities(ClientCapabilities.builder()
				.elicitation()
				.tasks(ClientCapabilities.ClientTaskCapabilities.builder().list().cancel().elicitationCreate().build())
				.build())
			.taskStore(clientTaskStore)
			.elicitation(request -> {
				receivedMessage.set(request.message());
				elicitationHandlerInvoked.countDown();
				return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("name", "Test User"), null);
			});

		try (var client = clientBuilder.build()) {
			client.initialize();

			// Trigger the tool which will send task-augmented elicitation to client
			var result = client.callTool(new McpSchema.CallToolRequest("trigger-elicitation", Map.of()));

			// Verify elicitation handler was invoked
			boolean handlerInvoked = elicitationHandlerInvoked.await(10, TimeUnit.SECONDS);
			assertThat(handlerInvoked).as("Elicitation handler should have been invoked").isTrue();
			assertThat(receivedMessage.get()).isEqualTo("Please enter your name:");

			// Verify the tool completed successfully
			assertThat(result.content()).isNotEmpty();
		}
		finally {
			server.closeGracefully().block();
		}
	}

	/**
	 * Helper to poll for task completion on client-hosted tasks.
	 * @param <T> The expected result type (e.g., CreateMessageResult, ElicitResult)
	 */
	private <T extends McpSchema.ClientTaskPayloadResult> Mono<T> pollForTaskCompletion(McpAsyncServerExchange exchange,
			String taskId, TypeRef<T> resultTypeRef) {
		return Mono.defer(() -> exchange.getTask(McpSchema.GetTaskRequest.builder().taskId(taskId).build()))
			.flatMap(task -> {
				if (task.status().isTerminal()) {
					return exchange.getTaskResult(McpSchema.GetTaskPayloadRequest.builder().taskId(taskId).build(),
							resultTypeRef);
				}
				return Mono.delay(Duration.ofMillis(100)).then(pollForTaskCompletion(exchange, taskId, resultTypeRef));
			})
			.timeout(Duration.ofSeconds(30));
	}

}
