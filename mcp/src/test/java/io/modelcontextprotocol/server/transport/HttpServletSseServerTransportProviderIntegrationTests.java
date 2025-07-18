/*
 * Copyright 2024 - 2025 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ModelPreferences;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.javacrumbs.jsonunit.core.Option;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;

class HttpServletSseServerTransportProviderIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

	McpClient.SyncSpec clientBuilder;

	private Tomcat tomcat;

	@BeforeEach
	public void before() {
		// Create and configure the transport provider
		mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		this.clientBuilder = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.build());
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@Test
	// @Disabled
	void testCreateMessageWithoutSamplingCapabilities() {

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {

				exchange.createMessage(mock(McpSchema.CreateMessageRequest.class)).block();

				return Mono.just(mock(CallToolResult.class));
			})
			.build();

		var server = McpServer.async(mcpServerTransportProvider).serverInfo("test-server", "1.0.0").tools(tool).build();

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
		server.close();
	}

	@Test
	void testCreateMessageSuccess() {

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
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

				StepVerifier.create(exchange.createMessage(createMessageRequest)).consumeNextWith(result -> {
					assertThat(result).isNotNull();
					assertThat(result.role()).isEqualTo(Role.USER);
					assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
					assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
					assertThat(result.model()).isEqualTo("MockModelName");
					assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
				}).verifyComplete();

				return Mono.just(callResponse);
			})
			.build();

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
		}
		mcpServer.close();
	}

	@Test
	void testCreateMessageWithRequestTimeoutSuccess() throws InterruptedException {

		// Client

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

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {

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

				StepVerifier.create(exchange.createMessage(craeteMessageRequest)).consumeNextWith(result -> {
					assertThat(result).isNotNull();
					assertThat(result.role()).isEqualTo(Role.USER);
					assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
					assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
					assertThat(result.model()).isEqualTo("MockModelName");
					assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
				}).verifyComplete();

				return Mono.just(callResponse);
			})
			.build();

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

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testCreateMessageWithRequestTimeoutFail() throws InterruptedException {

		// Client

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

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {

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

				StepVerifier.create(exchange.createMessage(craeteMessageRequest)).consumeNextWith(result -> {
					assertThat(result).isNotNull();
					assertThat(result.role()).isEqualTo(Role.USER);
					assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
					assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
					assertThat(result.model()).isEqualTo("MockModelName");
					assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
				}).verifyComplete();

				return Mono.just(callResponse);
			})
			.build();

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(1))
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
			mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
		}).withMessageContaining("Timeout");

		mcpClient.close();
		mcpServer.close();
	}

	// ---------------------------------------
	// Elicitation Tests
	// ---------------------------------------
	@Test
	// @Disabled
	void testCreateElicitationWithoutElicitationCapabilities() {

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {

				exchange.createElicitation(mock(ElicitRequest.class)).block();

				return Mono.just(mock(CallToolResult.class));
			})
			.build();

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

	@Test
	void testCreateElicitationSuccess() {

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.message()).isNotEmpty();
			assertThat(request.requestedSchema()).isNotNull();

			return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("message", request.message()));
		};

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {

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
			})
			.build();

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

	@Test
	void testCreateElicitationWithRequestTimeoutSuccess() {

		// Client

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

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {

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
			})
			.build();

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

	@Test
	void testCreateElicitationWithRequestTimeoutFail() {

		// Client

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

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {

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
			})
			.build();

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(1))
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
			mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
		}).withMessageContaining("Timeout");

		mcpClient.closeGracefully();
		mcpServer.closeGracefully().block();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@Test
	void testRootsSuccess() {
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

			mcpServer.close();
		}
	}

	@Test
	void testRootsWithoutCapability() {

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {

				exchange.listRoots(); // try to list roots

				return mock(CallToolResult.class);
			})
			.build();

		var mcpServer = McpServer.sync(mcpServerTransportProvider).rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

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

	@Test
	void testRootsNotificationWithEmptyRootsList() {
		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
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

	@Test
	void testRootsWithMultipleHandlers() {
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

			assertThat(mcpClient.initialize()).isNotNull();

			mcpClient.rootsListChangedNotification();

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef1.get()).containsAll(roots);
				assertThat(rootsRef2.get()).containsAll(roots);
			});
		}

		mcpServer.close();
	}

	@Test
	void testRootsServerCloseWithActiveSubscription() {
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

	@Test
	void testToolCallSuccess() {

		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);

		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {
				assertThat(McpTestServletFilter.getThreadLocalValue()).as("blocking code exectuion should be offloaded")
					.isNull();
				// perform a blocking call to a remote service
				String response = RestClient.create()
					.get()
					.uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
					.retrieve()
					.body(String.class);
				assertThat(response).isNotBlank();
				return callResponse;
			})
			.build();

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

	@Test
	void testToolCallImmediateExecution() {
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					var threadLocalValue = McpTestServletFilter.getThreadLocalValue();
					return CallToolResult.builder()
						.addTextContent(threadLocalValue != null ? threadLocalValue : "<unset>")
						.build();
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.immediateExecution(true)
			.build();

		try (var mcpClient = clientBuilder.build()) {
			mcpClient.initialize();

			CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

			assertThat(response).isNotNull();
			assertThat(response.content()).first()
				.asInstanceOf(type(McpSchema.TextContent.class))
				.extracting(McpSchema.TextContent::text)
				.isEqualTo(McpTestServletFilter.THREAD_LOCAL_VALUE);
		}

		mcpServer.close();
	}

	@Test
	void testToolListChangeHandlingSuccess() {

		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema))
			.callHandler((exchange, request) -> {
				// perform a blocking call to a remote service
				String response = RestClient.create()
					.get()
					.uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
					.retrieve()
					.body(String.class);
				assertThat(response).isNotBlank();
				return callResponse;
			})
			.build();

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
			McpServerFeatures.SyncToolSpecification tool2 = McpServerFeatures.SyncToolSpecification.builder()
				.tool(new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema))
				.callHandler((exchange, request) -> callResponse)
				.build();

			mcpServer.addTool(tool2);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(List.of(tool2.tool()));
			});
		}

		mcpServer.close();
	}

	@Test
	void testInitialize() {
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
	@Test
	void testLoggingNotification() {
		// Create a list to store received logging notifications
		List<McpSchema.LoggingMessageNotification> receivedNotifications = new CopyOnWriteArrayList<>();

		// Create server with a tool that sends logging notifications
		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(new McpSchema.Tool("logging-test", "Test logging notifications", emptyJsonSchema))
			.callHandler((exchange, request) -> {

				// Create and send notifications with different levels

				// This should be filtered out (DEBUG < NOTICE)
				exchange
					.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.DEBUG)
						.logger("test-logger")
						.data("Debug message")
						.build())
					.block();

				// This should be sent (NOTICE >= NOTICE)
				exchange
					.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.NOTICE)
						.logger("test-logger")
						.data("Notice message")
						.build())
					.block();

				// This should be sent (ERROR > NOTICE)
				exchange
					.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.ERROR)
						.logger("test-logger")
						.data("Error message")
						.build())
					.block();

				// This should be filtered out (INFO < NOTICE)
				exchange
					.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.INFO)
						.logger("test-logger")
						.data("Another info message")
						.build())
					.block();

				// This should be sent (ERROR >= NOTICE)
				exchange
					.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.ERROR)
						.logger("test-logger")
						.data("Another error message")
						.build())
					.block();

				return Mono.just(new CallToolResult("Logging test completed", false));
			})
			.build();

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().logging().tools(true).build())
			.tools(tool)
			.build();
		try (
				// Create client with logging notification handler
				var mcpClient = clientBuilder.loggingConsumer(notification -> {
					receivedNotifications.add(notification);
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

			// Wait for notifications to be processed
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {

				System.out.println("Received notifications: " + receivedNotifications);

				// Should have received 3 notifications (1 NOTICE and 2 ERROR)
				assertThat(receivedNotifications).hasSize(3);

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
				assertThat(notificationMap.get("Another error message").level())
					.isEqualTo(McpSchema.LoggingLevel.ERROR);
				assertThat(notificationMap.get("Another error message").logger()).isEqualTo("test-logger");
				assertThat(notificationMap.get("Another error message").data()).isEqualTo("Another error message");
			});
		}
		mcpServer.close();
	}

	// ---------------------------------------
	// Progress Tests
	// ---------------------------------------
	@Test
	void testProgressNotification() {
		// Create a list to store received progress notifications
		List<McpSchema.ProgressNotification> receivedNotifications = new CopyOnWriteArrayList<>();

		// Create server with a tool that sends progress notifications
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				McpSchema.Tool.builder()
					.name("progress-test")
					.description("Test progress notifications")
					.inputSchema(emptyJsonSchema)
					.build(),
				null, (exchange, request) -> {

					var progressToken = request.progressToken();

					exchange
						.progressNotification(
								new McpSchema.ProgressNotification(progressToken, 0.1, 1.0, "Test progress 1/10"))
						.block();

					exchange
						.progressNotification(
								new McpSchema.ProgressNotification(progressToken, 0.5, 1.0, "Test progress 5/10"))
						.block();

					exchange
						.progressNotification(
								new McpSchema.ProgressNotification(progressToken, 1.0, 1.0, "Test progress 10/10"))
						.block();

					return Mono.just(new CallToolResult("Progress test completed", false));
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().logging().tools(true).build())
			.tools(tool)
			.build();

		// Create client with progress notification handler
		try (var mcpClient = clientBuilder.progressConsumer(receivedNotifications::add).build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call the tool that sends progress notifications
			CallToolResult result = mcpClient.callTool(
					new McpSchema.CallToolRequest("progress-test", Map.of(), Map.of("progressToken", "test-token")));
			assertThat(result).isNotNull();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Progress test completed");

			// Wait for notifications to be processed
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				// Should have received 3 notifications
				assertThat(receivedNotifications).hasSize(3);

				// Check the progress notifications
				assertThat(receivedNotifications.stream().map(McpSchema.ProgressNotification::progressToken))
					.containsExactlyInAnyOrder("test-token", "test-token", "test-token");
				assertThat(receivedNotifications.stream().map(McpSchema.ProgressNotification::progress))
					.containsExactlyInAnyOrder(0.1, 0.5, 1.0);
			});
		}
		finally {
			mcpServer.close();
		}
	}

	// ---------------------------------------
	// Ping Tests
	// ---------------------------------------
	@Test
	void testPingSuccess() {
		// Create server with a tool that uses ping functionality
		AtomicReference<String> executionOrder = new AtomicReference<>("");

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("ping-async-test", "Test ping async behavior", emptyJsonSchema),
				(exchange, request) -> {

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
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
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
	@Test
	void testStructuredOutputValidationSuccess() {
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

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(calculatorTool,
				(exchange, request) -> {
					String expression = (String) request.getOrDefault("expression", "2 + 3");
					double result = evaluateExpression(expression);
					return CallToolResult.builder()
						.structuredContent(
								Map.of("result", result, "operation", expression, "timestamp", "2024-01-01T10:00:00Z"))
						.build();
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
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
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);

			assertThatJson(((McpSchema.TextContent) response.content().get(0)).text()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("""
						{"result":5.0,"operation":"2 + 3","timestamp":"2024-01-01T10:00:00Z"}"""));

			// Verify structured content (may be null in sync server but validation still
			// works)
			if (response.structuredContent() != null) {
				assertThat(response.structuredContent()).containsEntry("result", 5.0)
					.containsEntry("operation", "2 + 3")
					.containsEntry("timestamp", "2024-01-01T10:00:00Z");
			}
		}

		mcpServer.close();
	}

	@Test
	void testStructuredOutputValidationFailure() {

		// Create a tool with output schema
		Map<String, Object> outputSchema = Map.of("type", "object", "properties",
				Map.of("result", Map.of("type", "number"), "operation", Map.of("type", "string")), "required",
				List.of("result", "operation"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(calculatorTool,
				(exchange, request) -> {
					// Return invalid structured output. Result should be number, missing
					// operation
					return CallToolResult.builder()
						.addTextContent("Invalid calculation")
						.structuredContent(Map.of("result", "not-a-number", "extra", "field"))
						.build();
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
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

	@Test
	void testStructuredOutputMissingStructuredContent() {
		// Create a tool with output schema
		Map<String, Object> outputSchema = Map.of("type", "object", "properties",
				Map.of("result", Map.of("type", "number")), "required", List.of("result"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(calculatorTool,
				(exchange, request) -> {
					// Return result without structured content but tool has output schema
					return CallToolResult.builder().addTextContent("Calculation completed").build();
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
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

	@Test
	void testStructuredOutputRuntimeToolAddition() {
		// Start server without tools
		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
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

			McpServerFeatures.SyncToolSpecification toolSpec = new McpServerFeatures.SyncToolSpecification(dynamicTool,
					(exchange, request) -> {
						int count = (Integer) request.getOrDefault("count", 1);
						return CallToolResult.builder()
							.addTextContent("Dynamic tool executed " + count + " times")
							.structuredContent(Map.of("message", "Dynamic execution", "count", count))
							.build();
					});

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
			assertThat(response.structuredContent()).containsEntry("message", "Dynamic execution")
				.containsEntry("count", 3);
		}

		mcpServer.close();
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
