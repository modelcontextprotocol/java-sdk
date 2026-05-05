/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
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
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.util.McpJsonMapperUtils;
import io.modelcontextprotocol.util.Utils;
import net.javacrumbs.jsonunit.core.Option;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertWith;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

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
				var client = clientBuilder
					.clientInfo(McpSchema.Implementation.builder("Sample " + "client", "0.0.0").build())
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
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
			.callHandler((exchange, request) -> {
				return exchange.createMessage(mock(McpSchema.CreateMessageRequest.class))
					.then(Mono.just(mock(CallToolResult.class)));
			})
			.build();

		var server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

		try (
				// Create client without sampling capabilities
				var client = clientBuilder
					.clientInfo(McpSchema.Implementation.builder("Sample " + "client", "0.0.0").build())
					.build()) {

			assertThat(client.initialize()).isNotNull();

			try {
				client.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());
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

			return CreateMessageResult
				.builder(Role.USER, McpSchema.TextContent.builder("Test message").build(), "MockModelName")
				.stopReason(CreateMessageResult.StopReason.STOP_SEQUENCE)
				.build();
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(McpSchema.TextContent.builder("CALL RESPONSE").build())
			.build();

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
			.callHandler((exchange, request) -> {

				var createMessageRequest = McpSchema.CreateMessageRequest
					.builder(List.of(McpSchema.SamplingMessage
						.builder(McpSchema.Role.USER, McpSchema.TextContent.builder("Test message").build())
						.build()), 1000)
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

		try (var mcpClient = clientBuilder
			.clientInfo(McpSchema.Implementation.builder("Sample client", "0.0.0").build())
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());

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
			return CreateMessageResult
				.builder(Role.USER, McpSchema.TextContent.builder("Test message").build(), "MockModelName")
				.stopReason(CreateMessageResult.StopReason.STOP_SEQUENCE)
				.build();
		};

		// Server

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(McpSchema.TextContent.builder("CALL RESPONSE").build())
			.build();

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
			.callHandler((exchange, request) -> {

				var createMessageRequest = McpSchema.CreateMessageRequest
					.builder(List.of(McpSchema.SamplingMessage
						.builder(McpSchema.Role.USER, McpSchema.TextContent.builder("Test message").build())
						.build()), 1000)
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
		try (var mcpClient = clientBuilder
			.clientInfo(McpSchema.Implementation.builder("Sample client", "0.0.0").build())
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());

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
			return CreateMessageResult
				.builder(Role.USER, McpSchema.TextContent.builder("Test message").build(), "MockModelName")
				.stopReason(CreateMessageResult.StopReason.STOP_SEQUENCE)
				.build();
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(McpSchema.TextContent.builder("CALL RESPONSE").build())
			.build();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
			.callHandler((exchange, request) -> {

				var createMessageRequest = McpSchema.CreateMessageRequest
					.builder(List.of(McpSchema.SamplingMessage
						.builder(McpSchema.Role.USER, McpSchema.TextContent.builder("Test message").build())
						.build()), 1000)
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

		try (var mcpClient = clientBuilder
			.clientInfo(McpSchema.Implementation.builder("Sample client", "0.0.0").build())
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
				mcpClient.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());
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
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
			.callHandler((exchange, request) -> exchange.createElicitation(mock(ElicitRequest.class))
				.then(Mono.just(mock(CallToolResult.class))))
			.build();

		var server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

		// Create client without elicitation capabilities
		try (var client = clientBuilder.clientInfo(McpSchema.Implementation.builder("Sample client", "0.0.0").build())
			.build()) {

			assertThat(client.initialize()).isNotNull();

			try {
				client.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());
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

			return McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
				.content(Map.of("message", request.message()))
				.build();
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(McpSchema.TextContent.builder("CALL RESPONSE").build())
			.build();

		AtomicReference<McpSchema.ElicitResult> elicitResultRef = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
			.callHandler((exchange, request) -> {

				var elicitationRequest = McpSchema.ElicitRequest
					.builder("Test message",
							Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))))
					.build();

				return exchange.createElicitation(elicitationRequest)
					.doOnNext(elicitResultRef::set)
					.thenReturn(callResponse);
			})
			.build();

		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

		try (var mcpClient = clientBuilder
			.clientInfo(McpSchema.Implementation.builder("Sample client", "0.0.0").build())
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
			assertWith(elicitResultRef.get(), result -> {
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
	void testCreateElicitationWithRequestTimeoutSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();
			return ElicitResult.builder(ElicitResult.Action.ACCEPT)
				.content(Map.of("message", request.message()))
				.build();
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
			.addContent(McpSchema.TextContent.builder("CALL RESPONSE").build())
			.build();

		AtomicReference<ElicitResult> resultRef = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
			.callHandler((exchange, request) -> {

				var elicitationRequest = McpSchema.ElicitRequest
					.builder("Test message",
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

		try (var mcpClient = clientBuilder
			.clientInfo(McpSchema.Implementation.builder("Sample client", "0.0.0").build())
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());

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
			return ElicitResult.builder(ElicitResult.Action.ACCEPT)
				.content(Map.of("message", request.message()))
				.build();
		};

		CallToolResult callResponse = CallToolResult.builder()
			.addContent(TextContent.builder("CALL RESPONSE").build())
			.build();

		AtomicReference<ElicitResult> resultRef = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
			.callHandler((exchange, request) -> {

				var elicitationRequest = ElicitRequest
					.builder("Test message",
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

		try (var mcpClient = clientBuilder
			.clientInfo(McpSchema.Implementation.builder("Sample client", "0.0.0").build())
			.capabilities(ClientCapabilities.builder().elicitation().build())
			.elicitation(elicitationHandler)
			.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
				mcpClient.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());
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

		List<Root> roots = List.of(Root.builder("uri1://").name("root1").build(),
				Root.builder("uri2://").name("root2").build());

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
			var root3 = Root.builder("uri3://").name("root3").build();
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
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
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
				mcpClient.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());
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

		List<Root> roots = List.of(Root.builder("uri1://").name("root1").build());

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

		List<Root> roots = List.of(Root.builder("uri1://").name("root1").build());

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
			.addContent(McpSchema.TextContent.builder("CALL RESPONSE; ctx=importantValue").build())
			.build();
		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
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

			CallToolResult response = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());

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
				.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
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
			assertThatExceptionOfType(McpError.class).isThrownBy(
					() -> mcpClient.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build()))
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
			.addContent(McpSchema.TextContent.builder("CALL RESPONSE; ctx=value").build())
			.build();
		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
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
					.addContent(McpSchema.TextContent.builder("CALL RESPONSE; ctx=" + ctxValue).build())
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

			CallToolResult response = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("tool1").arguments(Map.of()).build());

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
	void testToolWithNonAsciiCharacters(String clientType) {
		var clientBuilder = clientBuilders.get(clientType);

		String inputSchema = """
					{
						"type": "object",
						"properties": {
							"username": { "type": "string" }
						},
						"required": ["username"]
					}
				""";

		McpServerFeatures.SyncToolSpecification nonAsciiTool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder("greeter", McpJsonDefaults.getMapper(), inputSchema).description("打招呼").build())
			.callHandler((exchange, request) -> {
				String username = (String) request.arguments().get("username");
				return McpSchema.CallToolResult.builder()
					.addContent(McpSchema.TextContent.builder("Hello " + username).build())
					.build();
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(nonAsciiTool)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			var tools = mcpClient.listTools().tools();
			assertThat(tools).hasSize(1);
			assertThat(tools.get(0).name()).isEqualTo("greeter");
			assertThat(tools.get(0).description()).isEqualTo("打招呼");

			CallToolResult response = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("greeter").arguments(Map.of("username", "测试用户")).build());

			assertThat(response).isNotNull();
			assertThat(response.isError()).isFalse();
			assertThat(response.content()).hasSize(1);
			assertThat(((McpSchema.TextContent) response.content().get(0)).text()).isEqualTo("Hello 测试用户");
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
			.addContent(McpSchema.TextContent.builder("CALL RESPONSE").build())
			.build();

		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder("tool1", EMPTY_JSON_SCHEMA).description("tool1 description").build())
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
				.tool(Tool.builder("tool2", EMPTY_JSON_SCHEMA).description("tool2 description").build())
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
	void testBulkToolListChangeHandlingSuccess(String clientType) {

		var clientBuilder = clientBuilders.get(clientType);

		var callResponse = McpSchema.CallToolResult.builder()
			.addContent(new McpSchema.TextContent("CALL RESPONSE"))
			.build();

		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("bulk-tool-1").description("bulk tool 1").inputSchema(EMPTY_JSON_SCHEMA).build())
			.callHandler((exchange, request) -> callResponse)
			.build();
		McpServerFeatures.SyncToolSpecification tool2 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("bulk-tool-2").description("bulk tool 2").inputSchema(EMPTY_JSON_SCHEMA).build())
			.callHandler((exchange, request) -> callResponse)
			.build();
		McpServerFeatures.SyncToolSpecification replacementTool2 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("bulk-tool-2")
				.title("Replacement Bulk Tool 2")
				.description("replacement bulk tool 2")
				.inputSchema(EMPTY_JSON_SCHEMA)
				.build())
			.callHandler((exchange, request) -> callResponse)
			.build();
		McpServerFeatures.SyncToolSpecification tool3 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder().name("bulk-tool-3").description("bulk tool 3").inputSchema(EMPTY_JSON_SCHEMA).build())
			.callHandler((exchange, request) -> callResponse)
			.build();

		AtomicInteger notificationCount = new AtomicInteger();
		AtomicReference<List<Tool>> toolsRef = new AtomicReference<>();

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		try (var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			toolsRef.set(toolsUpdate);
			notificationCount.incrementAndGet();
		}).build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
			assertThat(toolsRef.get()).isNull();
			assertThat(notificationCount.get()).isZero();
			assertThat(mcpClient.listTools().tools()).isEmpty();

			mcpServer.addTools(List.of(tool1, tool2));

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(notificationCount.get()).isEqualTo(1);
				assertThat(toolsRef.get()).extracting(Tool::name).containsExactly("bulk-tool-1", "bulk-tool-2");
			});
			assertThat(mcpClient.listTools().tools()).extracting(Tool::name)
				.containsExactly("bulk-tool-1", "bulk-tool-2");

			mcpServer.addTools(List.of(replacementTool2, tool3));

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(notificationCount.get()).isEqualTo(2);
				List<Tool> tools = toolsRef.get();
				assertThat(tools).extracting(Tool::name).containsExactly("bulk-tool-1", "bulk-tool-2", "bulk-tool-3");
				Tool replacedTool = tools.stream()
					.filter(tool -> "bulk-tool-2".equals(tool.name()))
					.findFirst()
					.orElseThrow();
				assertThat(replacedTool.title()).isEqualTo("Replacement Bulk Tool 2");
			});

			mcpServer.removeTools(List.of("bulk-tool-1", "missing-tool"));

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(notificationCount.get()).isEqualTo(3);
				assertThat(toolsRef.get()).extracting(Tool::name).containsExactly("bulk-tool-2", "bulk-tool-3");
			});
			assertThat(mcpClient.listTools().tools()).extracting(Tool::name)
				.containsExactly("bulk-tool-2", "bulk-tool-3");
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
			.tool(Tool.builder("logging-test", EMPTY_JSON_SCHEMA).description("Test logging notifications").build())
			.callHandler((exchange, request) -> {

				// Create and send notifications with different levels

			//@formatter:off
					return exchange // This should be filtered out (DEBUG < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification
								.builder(McpSchema.LoggingLevel.DEBUG, "Debug message")
								.logger("test-logger")
								.build())
					.then(exchange // This should be sent (NOTICE >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification
								.builder(McpSchema.LoggingLevel.NOTICE, "Notice message")
								.logger("test-logger")
								.build()))
					.then(exchange // This should be sent (ERROR > NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification
							.builder(McpSchema.LoggingLevel.ERROR, "Error message")
							.logger("test-logger")
							.build()))
					.then(exchange // This should be filtered out (INFO < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification
								.builder(McpSchema.LoggingLevel.INFO, "Another info message")
								.logger("test-logger")
								.build()))
					.then(exchange // This should be sent (ERROR >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification
								.builder(McpSchema.LoggingLevel.ERROR, "Another error message")
								.logger("test-logger")
								.build()))
					.thenReturn(CallToolResult.builder()
						.content(List.of(McpSchema.TextContent.builder("Logging test completed").build()))
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
			CallToolResult result = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("logging-test").arguments(Map.of()).build());
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
			.tool(McpSchema.Tool.builder("progress-test", EMPTY_JSON_SCHEMA)
				.description("Test progress notifications")
				.build())
			.callHandler((exchange, request) -> {

				// Create and send notifications
				var progressToken = (String) request.meta().get("progressToken");

				return exchange
					.progressNotification(McpSchema.ProgressNotification.builder(progressToken, 0.0)
						.total(1.0)
						.message("Processing started")
						.build())
					.then(exchange.progressNotification(McpSchema.ProgressNotification.builder(progressToken, 0.5)
						.total(1.0)
						.message("Processing data")
						.build()))
					.then(exchange
						.progressNotification(McpSchema.ProgressNotification.builder("another-progress-token", 0.0)
							.total(1.0)
							.message("Another processing started")
							.build()))
					.then(exchange.progressNotification(McpSchema.ProgressNotification.builder(progressToken, 1.0)
						.total(1.0)
						.message("Processing completed")
						.build()))
					.thenReturn(CallToolResult.builder()
						.content(List.of(McpSchema.TextContent.builder("Progress test completed").build()))
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
		var completionResponse = new McpSchema.CompleteResult(
				new CompleteResult.CompleteCompletion(expectedValues, 10, true));

		AtomicReference<CompleteRequest> samplingRequest = new AtomicReference<>();
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (mcpSyncServerExchange,
				request) -> {
			samplingRequest.set(request);
			return completionResponse;
		};

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().completions().build())
			.prompts(new McpServerFeatures.SyncPromptSpecification(Prompt.builder("code_review")
				.title("Code review")
				.description("this is code review prompt")
				.arguments(List.of(PromptArgument.builder("language")
					.title("Language")
					.description("string")
					.required(false)
					.build()))
				.build(), (mcpSyncServerExchange, getPromptRequest) -> null))
			.completions(new McpServerFeatures.SyncCompletionSpecification(
					McpSchema.PromptReference.builder("code_review").title("Code review").build(), completionHandler))
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CompleteRequest request = CompleteRequest
				.builder(PromptReference.builder("code_review").title("Code review").build(),
						new CompleteRequest.CompleteArgument("language", "py"))
				.build();

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
			.tool(Tool.builder("ping-async-test", EMPTY_JSON_SCHEMA).description("Test ping async behavior").build())
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
						.content(List.of(McpSchema.TextContent.builder("Async ping test completed").build()))
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
			CallToolResult result = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("ping-async-test").arguments(Map.of()).build());
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

		Tool calculatorTool = Tool.builder("calculator")
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
			CallToolResult response = mcpClient.callTool(
					McpSchema.CallToolRequest.builder("calculator").arguments(Map.of("expression", "2 + 3")).build());

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

		Tool calculatorTool = Tool.builder("getMembers")
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
			CallToolResult response = mcpClient
				.callTool(McpSchema.CallToolRequest.builder("getMembers").arguments(Map.of()).build());

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

		Tool calculatorTool = Tool.builder("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		// Handler that returns an error result
		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> CallToolResult.builder()
				.isError(true)
				.content(List.of(TextContent.builder("Error calling tool: Simulated in-handler error").build()))
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
			CallToolResult response = mcpClient.callTool(
					McpSchema.CallToolRequest.builder("calculator").arguments(Map.of("expression", "2 + 3")).build());

			assertThat(response).isNotNull();
			assertThat(response.isError()).isTrue();
			assertThat(response.content()).isNotEmpty();
			assertThat(response.content()).containsExactly(
					McpSchema.TextContent.builder("Error calling tool: Simulated in-handler error").build());
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

		Tool calculatorTool = Tool.builder("calculator")
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
			CallToolResult response = mcpClient.callTool(
					McpSchema.CallToolRequest.builder("calculator").arguments(Map.of("expression", "2 + 3")).build());

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

		Tool calculatorTool = Tool.builder("calculator")
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
			CallToolResult response = mcpClient.callTool(
					McpSchema.CallToolRequest.builder("calculator").arguments(Map.of("expression", "2 + 3")).build());

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

			Tool dynamicTool = Tool.builder("dynamic-tool")
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
				.callTool(McpSchema.CallToolRequest.builder("dynamic-tool").arguments(Map.of("count", 3)).build());

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

	// ---------------------------------------
	// Resource Subscription Tests
	// ---------------------------------------

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testResourceSubscription(String clientType) throws InterruptedException {

		var clientBuilder = clientBuilders.get(clientType);

		String resourceUri = "test://subscribable-resource";
		var receivedContents = new AtomicReference<List<McpSchema.ResourceContents>>();
		var latch = new CountDownLatch(1);

		McpServerFeatures.SyncResourceSpecification resourceSpec = new McpServerFeatures.SyncResourceSpecification(
				McpSchema.Resource.builder(resourceUri, "Subscribable Resource").mimeType("text/plain").build(),
				(exchange, req) -> McpSchema.ReadResourceResult
					.builder(List.of(McpSchema.TextResourceContents.builder(resourceUri, "initial content")
						.mimeType("text/plain")
						.build()))
					.build());

		McpSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().resources(true, false).build())
			.resources(resourceSpec)
			.build();

		try (var mcpClient = clientBuilder.resourcesUpdateConsumer(contents -> {
			receivedContents.set(contents);
			latch.countDown();
		}).build()) {

			mcpClient.initialize();

			mcpClient.subscribeResource(McpSchema.SubscribeRequest.builder(resourceUri).build());

			mcpServer.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(resourceUri));

			assertThat(latch.await(5, TimeUnit.SECONDS))
				.as("client should receive the resources/updated notification within 5 seconds")
				.isTrue();
			assertThat(receivedContents.get()).isNotEmpty();
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testResourceSubscription_afterUnsubscribe_noNotification(String clientType) throws InterruptedException {

		var clientBuilder = clientBuilders.get(clientType);

		String resourceUri = "test://subscribable-resource-unsub";
		var notificationCount = new java.util.concurrent.atomic.AtomicInteger(0);

		McpServerFeatures.SyncResourceSpecification resourceSpec = new McpServerFeatures.SyncResourceSpecification(
				McpSchema.Resource.builder(resourceUri, "Subscribable Resource").mimeType("text/plain").build(),
				(exchange, req) -> McpSchema.ReadResourceResult.builder(List
					.of(McpSchema.TextResourceContents.builder(resourceUri, "content").mimeType("text/plain").build()))
					.build());

		McpSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().resources(true, false).build())
			.resources(resourceSpec)
			.build();

		try (var mcpClient = clientBuilder.resourcesUpdateConsumer(contents -> notificationCount.incrementAndGet())
			.build()) {

			mcpClient.initialize();

			mcpClient.subscribeResource(McpSchema.SubscribeRequest.builder(resourceUri).build());
			mcpClient.unsubscribeResource(McpSchema.UnsubscribeRequest.builder(resourceUri).build());

			mcpServer.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(resourceUri));

			assertThat(notificationCount.get()).as("no notification should be received after unsubscribing").isZero();
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

}
