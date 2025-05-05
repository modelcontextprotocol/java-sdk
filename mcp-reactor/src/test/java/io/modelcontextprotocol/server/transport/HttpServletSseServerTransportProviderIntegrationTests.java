/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClientFactory;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.schema.McpJacksonCodec;
import io.modelcontextprotocol.server.McpServerFactory;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.schema.McpSchema;
import io.modelcontextprotocol.schema.McpSchema.CallToolResult;
import io.modelcontextprotocol.schema.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.schema.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.schema.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.schema.McpSchema.InitializeResult;
import io.modelcontextprotocol.schema.McpSchema.ModelPreferences;
import io.modelcontextprotocol.schema.McpSchema.Role;
import io.modelcontextprotocol.schema.McpSchema.Root;
import io.modelcontextprotocol.schema.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.schema.McpSchema.Tool;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

class HttpServletSseServerTransportProviderIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private final McpJacksonCodec mcpJacksonCodec = new McpJacksonCodec();

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

	McpClientFactory.SyncSpec clientBuilder;

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

		this.clientBuilder = McpClientFactory.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
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
	@Disabled
	void testCreateMessageWithoutSamplingCapabilities() throws Exception {
		McpSchema.JsonSchema jsonSchema = mcpJacksonCodec.getMapper()
			.readValue(emptyJsonSchema, McpSchema.JsonSchema.class);
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", jsonSchema), (exchange, request) -> {

					Mono.from(exchange.createMessage(mock(McpSchema.CreateMessageRequest.class))).block();

					return Mono.just(mock(CallToolResult.class));
				});

		var server = McpServerFactory.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

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
	void testCreateMessageSuccess() throws Exception {

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpSchema.JsonSchema jsonSchema = mcpJacksonCodec.getMapper()
			.readValue(emptyJsonSchema, McpSchema.JsonSchema.class);
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", jsonSchema), (exchange, request) -> {

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
				});

		var mcpServer = McpServerFactory.async(mcpServerTransportProvider)
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
	void testCreateMessageWithRequestTimeoutSuccess() throws Exception {

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

		McpSchema.JsonSchema jsonSchema = mcpJacksonCodec.getMapper()
			.readValue(emptyJsonSchema, McpSchema.JsonSchema.class);
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", jsonSchema), (exchange, request) -> {

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
				});

		var mcpServer = McpServerFactory.async(mcpServerTransportProvider)
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
	void testCreateMessageWithRequestTimeoutFail() throws Exception {

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
		McpSchema.JsonSchema jsonSchema = mcpJacksonCodec.getMapper()
			.readValue(emptyJsonSchema, McpSchema.JsonSchema.class);
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", jsonSchema), (exchange, request) -> {

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
				});

		var mcpServer = McpServerFactory.async(mcpServerTransportProvider)
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
	// Roots Tests
	// ---------------------------------------
	@Test
	void testRootsSuccess() {
		List<Root> roots = List.of(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		var mcpServer = McpServerFactory.sync(mcpServerTransportProvider)
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
	void testRootsWithoutCapability() throws Exception {
		McpSchema.JsonSchema jsonSchema = mcpJacksonCodec.getMapper()
			.readValue(emptyJsonSchema, McpSchema.JsonSchema.class);
		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", jsonSchema), (exchange, request) -> {

					exchange.listRoots(); // try to list roots

					return mock(CallToolResult.class);
				});

		var mcpServer = McpServerFactory.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> {
			})
			.tools(tool)
			.build();

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

		var mcpServer = McpServerFactory.sync(mcpServerTransportProvider)
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

		var mcpServer = McpServerFactory.sync(mcpServerTransportProvider)
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

		var mcpServer = McpServerFactory.sync(mcpServerTransportProvider)
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
	void testToolCallSuccess() throws Exception {
		McpSchema.JsonSchema jsonSchema = mcpJacksonCodec.getMapper()
			.readValue(emptyJsonSchema, McpSchema.JsonSchema.class);
		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", jsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					try {
						HttpClient client = HttpClient.newHttpClient();
						HttpRequest rq = HttpRequest.newBuilder()
							.uri(URI.create(
									"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md"))
							.header("Content-Type", "application/json")
							.GET()
							.build();
						String response = client.send(rq, HttpResponse.BodyHandlers.ofString()).body();
						assertThat(response).isNotBlank();
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}

					return callResponse;
				});

		var mcpServer = McpServerFactory.sync(mcpServerTransportProvider)
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
	void testToolListChangeHandlingSuccess() throws Exception {
		McpSchema.JsonSchema jsonSchema = mcpJacksonCodec.getMapper()
			.readValue(emptyJsonSchema, McpSchema.JsonSchema.class);
		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", jsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					try {
						HttpClient client = HttpClient.newHttpClient();
						HttpRequest rq = HttpRequest.newBuilder()
							.uri(URI.create(
									"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md"))
							.header("Content-Type", "application/json")
							.GET()
							.build();
						String response = client.send(rq, HttpResponse.BodyHandlers.ofString()).body();
						assertThat(response).isNotBlank();
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}
					return callResponse;
				});

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();

		var mcpServer = McpServerFactory.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			try {
				HttpClient client = HttpClient.newHttpClient();
				HttpRequest rq = HttpRequest.newBuilder()
					.uri(URI.create(
							"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md"))
					.header("Content-Type", "application/json")
					.GET()
					.build();
				String response = client.send(rq, HttpResponse.BodyHandlers.ofString()).body();
				assertThat(response).isNotBlank();
				rootsRef.set(toolsUpdate);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
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
					new McpSchema.Tool("tool2", "tool2 description", jsonSchema), (exchange, request) -> callResponse);

			mcpServer.addTool(tool2);

			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(List.of(tool2.tool()));
			});
		}

		mcpServer.close();
	}

	@Test
	void testInitialize() {
		var mcpServer = McpServerFactory.sync(mcpServerTransportProvider).build();

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
	void testLoggingNotification() throws Exception {
		// Create a list to store received logging notifications
		List<McpSchema.LoggingMessageNotification> receivedNotifications = new ArrayList<>();
		McpSchema.JsonSchema jsonSchema = mcpJacksonCodec.getMapper()
			.readValue(emptyJsonSchema, McpSchema.JsonSchema.class);
		// Create server with a tool that sends logging notifications
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("logging-test", "Test logging notifications", jsonSchema), (exchange, request) -> {

					// Create and send notifications with different levels

					// This should be filtered out (DEBUG < NOTICE)
					Mono.from(exchange.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.DEBUG)
						.logger("test-logger")
						.data("Debug message")
						.build())).block();

					// This should be sent (NOTICE >= NOTICE)
					Mono.from(exchange.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.NOTICE)
						.logger("test-logger")
						.data("Notice message")
						.build())).block();

					// This should be sent (ERROR > NOTICE)
					Mono.from(exchange.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.ERROR)
						.logger("test-logger")
						.data("Error message")
						.build())).block();

					// This should be filtered out (INFO < NOTICE)
					Mono.from(exchange.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.INFO)
						.logger("test-logger")
						.data("Another info message")
						.build())).block();

					// This should be sent (ERROR >= NOTICE)
					Mono.from(exchange.loggingNotification(McpSchema.LoggingMessageNotification.builder()
						.level(McpSchema.LoggingLevel.ERROR)
						.logger("test-logger")
						.data("Another error message")
						.build())).block();

					return Mono.just(new CallToolResult("Logging test completed", false));
				});

		var mcpServer = McpServerFactory.async(mcpServerTransportProvider)
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

}
