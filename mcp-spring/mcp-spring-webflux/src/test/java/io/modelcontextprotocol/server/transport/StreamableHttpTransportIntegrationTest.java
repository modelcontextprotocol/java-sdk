/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StreamableHttpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for @link{StreamableHttpServerTransportProvider} with
 *
 * @link{WebClientStreamableHttpTransport}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamableHttpTransportIntegrationTest {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String ENDPOINT = "/mcp";

	private StreamableHttpServerTransportProvider serverTransportProvider;

	private McpClient.AsyncSpec clientBuilder;

	private Tomcat tomcat;

	@BeforeEach
	void setUp() {
		serverTransportProvider = new StreamableHttpServerTransportProvider(new ObjectMapper(), ENDPOINT, null);

		// Set up session factory with proper server capabilities
		McpSchema.ServerCapabilities serverCapabilities = new McpSchema.ServerCapabilities(null, null, null, null, null,
				null);
		serverTransportProvider
			.setStreamableHttpSessionFactory(sessionId -> new io.modelcontextprotocol.spec.McpServerSession(sessionId,
					java.time.Duration.ofSeconds(30),
					request -> reactor.core.publisher.Mono.just(new McpSchema.InitializeResult("2024-11-05",
							serverCapabilities, new McpSchema.Implementation("Test Server", "1.0.0"), null)),
					() -> reactor.core.publisher.Mono.empty(), java.util.Map.of(), java.util.Map.of()));

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, serverTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		WebClientStreamableHttpTransport clientTransport = WebClientStreamableHttpTransport
			.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
			.endpoint(ENDPOINT)
			.objectMapper(new ObjectMapper())
			.build();

		clientBuilder = McpClient.async(clientTransport)
			.clientInfo(new McpSchema.Implementation("Test Client", "1.0.0"));
	}

	@AfterEach
	void tearDown() {
		if (serverTransportProvider != null) {
			serverTransportProvider.closeGracefully().block();
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

	private void setupServerSession(McpServerFeatures.AsyncToolSpecification tool, Duration timeout,
			Map<String, io.modelcontextprotocol.spec.McpServerSession.RequestHandler<?>> additionalHandlers) {
		McpSchema.ServerCapabilities serverCapabilities = new McpSchema.ServerCapabilities(null, null, null, null, null,
				new McpSchema.ServerCapabilities.ToolCapabilities(true));
		Map<String, io.modelcontextprotocol.spec.McpServerSession.RequestHandler<?>> handlers = new java.util.HashMap<>();
		handlers.put("tools/call",
				(io.modelcontextprotocol.spec.McpServerSession.RequestHandler<McpSchema.CallToolResult>) (exchange,
						params) -> tool.call().apply(exchange, (Map<String, Object>) params));
		handlers.putAll(additionalHandlers);
		serverTransportProvider.setStreamableHttpSessionFactory(
				sessionId -> new io.modelcontextprotocol.spec.McpServerSession(sessionId, timeout,
						request -> Mono.just(new McpSchema.InitializeResult("2024-11-05", serverCapabilities,
								new McpSchema.Implementation("Test Server", "1.0.0"), null)),
						() -> Mono.empty(), handlers, Map.of()));
	}

	private void setupServerSession(McpServerFeatures.AsyncToolSpecification tool) {
		setupServerSession(tool, Duration.ofSeconds(30), Map.of());
	}

	private io.modelcontextprotocol.client.McpAsyncClient buildClientWithCapabilities(
			McpSchema.ClientCapabilities capabilities,
			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler,
			Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler) {
		var builder = McpClient
			.async(WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
				.endpoint(ENDPOINT)
				.objectMapper(new ObjectMapper())
				.build())
			.clientInfo(new McpSchema.Implementation("Test Client", "1.0.0"));
		if (capabilities != null) {
			builder = builder.capabilities(capabilities);
		}
		if (samplingHandler != null) {
			builder = builder.sampling(samplingHandler);
		}
		if (elicitationHandler != null) {
			builder = builder.elicitation(elicitationHandler);
		}
		return builder.build();
	}

	private void executeTestWithClient(io.modelcontextprotocol.client.McpAsyncClient mcpClient, Runnable testLogic) {
		try {
			mcpClient.initialize().block();
			testLogic.run();
		}
		finally {
			mcpClient.close();
		}
	}

	@Test
	@Order(1)
	void shouldInitializeSuccessfully() {
		// The server is already configured via the session factory in setUp
		var mcpClient = clientBuilder.build();
		try {
			InitializeResult result = mcpClient.initialize().block();
			assertThat(result).isNotNull();
			assertThat(result.serverInfo().name()).isEqualTo("Test Server");
		}
		finally {
			mcpClient.close();
		}
	}

	@Test
	@Order(2)
	void shouldCallToolSuccessfully() {
		var callResponse = new CallToolResult(List.of(new McpSchema.TextContent("Tool executed successfully")), null);
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool description", "{}"),
				(exchange, request) -> Mono.just(callResponse));

		setupServerSession(tool);
		var mcpClient = clientBuilder.build();
		executeTestWithClient(mcpClient, () -> {
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text())
				.isEqualTo("Tool executed successfully");
		});
	}

	@Test
	@Order(3)
	void shouldCallToolWithUpgradedTransportSuccessfully() {
		var callResponse = new CallToolResult(List.of(new McpSchema.TextContent("Tool executed successfully")), null);
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool description", "{}"), (exchange, request) -> {
					exchange.upgradeTransport();
					return Mono.just(callResponse);
				});

		setupServerSession(tool);
		var mcpClient = clientBuilder.build();
		executeTestWithClient(mcpClient, () -> {
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text())
				.isEqualTo("Tool executed successfully");
		});
	}

	@Test
	@Order(4)
	void shouldReceiveNotificationThroughGetStream() throws InterruptedException {
		CountDownLatch notificationLatch = new CountDownLatch(1);
		AtomicReference<String> receivedNotification = new AtomicReference<>();

		// Build client with logging notification handler
		var mcpClient = McpClient
			.async(WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
				.endpoint(ENDPOINT)
				.objectMapper(new ObjectMapper())
				.openConnectionOnStartup(true)
				.build())
			.clientInfo(new McpSchema.Implementation("Test Client", "1.0.0"))
			.loggingConsumers(List.of(notification -> {
				if ("test message".equals(notification.data())) {
					receivedNotification.set(notification.data());
					notificationLatch.countDown();
				}
				return Mono.empty();
			}))
			.build();

		try {
			mcpClient.initialize().block();

			// Wait for post-initialize GET/listening stream establishment
			Thread.sleep(500);

			// Send logging notification from server
			serverTransportProvider
				.notifyClients(McpSchema.METHOD_NOTIFICATION_MESSAGE,
						new McpSchema.LoggingMessageNotification(McpSchema.LoggingLevel.INFO, "server", "test message"))
				.block();

			// Wait for notification to be received
			assertThat(notificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(receivedNotification.get()).isEqualTo("test message");
		}
		finally {
			mcpClient.close();
		}
	}

	@Test
	@Order(5)
	void shouldCreateMessageSuccessfully() {
		var createMessageResult = new McpSchema.CreateMessageResult(McpSchema.Role.ASSISTANT,
				new McpSchema.TextContent("Test response"), "test-model",
				McpSchema.CreateMessageResult.StopReason.END_TURN);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("create-message-tool", "Tool that creates messages", "{}"), (exchange, request) -> {
					var createRequest = new McpSchema.CreateMessageRequest(
							List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
									new McpSchema.TextContent("Test prompt"))),
							null, null, null, null, 0, null, null, null);
					return exchange.createMessage(createRequest)
						.then(Mono.just(new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent("Message created")), null)));
				});

		setupServerSession(tool, Duration.ofSeconds(30), Map.of(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE,
				(io.modelcontextprotocol.spec.McpServerSession.RequestHandler<McpSchema.CreateMessageResult>) (exchange,
						params) -> Mono.just(createMessageResult)));

		var mcpClient = buildClientWithCapabilities(McpSchema.ClientCapabilities.builder().sampling().build(),
				request -> Mono.just(new McpSchema.CreateMessageResult(McpSchema.Role.ASSISTANT,
						new McpSchema.TextContent("Sampled response"), "test-model",
						McpSchema.CreateMessageResult.StopReason.END_TURN)),
				null);
		executeTestWithClient(mcpClient, () -> {
			var result = mcpClient.callTool(new McpSchema.CallToolRequest("create-message-tool", Map.of())).block();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Message created");
		});
	}

	@Test
	@Order(6)
	void shouldCreateElicitationSuccessfully() {
		var elicitResult = new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
				Map.of("response", "user response"));

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("elicit-tool", "Tool that creates elicitations", "{}"), (exchange, request) -> {
					var elicitRequest = new McpSchema.ElicitRequest("Please provide input", null, null);
					return exchange.createElicitation(elicitRequest)
						.then(Mono.just(new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent("Elicitation created")), null)));
				});

		setupServerSession(tool, Duration.ofSeconds(30), Map.of(McpSchema.METHOD_ELICITATION_CREATE,
				(io.modelcontextprotocol.spec.McpServerSession.RequestHandler<McpSchema.ElicitResult>) (exchange,
						params) -> Mono.just(elicitResult)));

		var mcpClient = buildClientWithCapabilities(McpSchema.ClientCapabilities.builder().elicitation().build(), null,
				request -> Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
						Map.of("response", "elicited response"))));
		executeTestWithClient(mcpClient, () -> {
			var result = mcpClient.callTool(new McpSchema.CallToolRequest("elicit-tool", Map.of())).block();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Elicitation created");
		});
	}

	@Test
	@Order(7)
	void shouldListRootsSuccessfully() {
		var listRootsResult = new McpSchema.ListRootsResult(List.of(new McpSchema.Root("file:///test", "Test root")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("list-roots-tool", "Tool that lists roots", "{}"), (exchange, request) -> {
					return exchange.listRoots()
						.then(Mono.just(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("Roots listed")),
								null)));
				});

		setupServerSession(tool, Duration.ofSeconds(30), Map.of(McpSchema.METHOD_ROOTS_LIST,
				(io.modelcontextprotocol.spec.McpServerSession.RequestHandler<McpSchema.ListRootsResult>) (exchange,
						params) -> Mono.just(listRootsResult)));

		var mcpClient = buildClientWithCapabilities(McpSchema.ClientCapabilities.builder().roots(true).build(), null,
				null);
		executeTestWithClient(mcpClient, () -> {
			var result = mcpClient.callTool(new McpSchema.CallToolRequest("list-roots-tool", Map.of())).block();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Roots listed");
		});
	}

	@Test
	@Order(8)
	void shouldSendLoggingNotificationSuccessfully() {
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("logging-tool", "Tool that sends logging notifications", "{}"),
				(exchange, request) -> {
					var notification = new McpSchema.LoggingMessageNotification(McpSchema.LoggingLevel.INFO,
							"test-logger", "Test log message");
					return exchange.loggingNotification(notification)
						.then(Mono.just(new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent("Notification sent")), null)));
				});

		setupServerSession(tool, Duration.ofSeconds(30),
				Map.of(McpSchema.METHOD_NOTIFICATION_MESSAGE,
						(io.modelcontextprotocol.spec.McpServerSession.RequestHandler<Void>) (exchange, params) -> Mono
							.empty()));

		var mcpClient = clientBuilder.build();
		executeTestWithClient(mcpClient, () -> {
			var result = mcpClient.callTool(new McpSchema.CallToolRequest("logging-tool", Map.of())).block();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Notification sent");
		});
	}

	@Test
	@Order(9)
	void shouldPingSuccessfully() {
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("ping-tool", "Tool that sends ping requests", "{}"), (exchange, request) -> {
					return exchange.ping()
						.then(Mono
							.just(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("Ping sent")), null)));
				});

		setupServerSession(tool, Duration.ofSeconds(30),
				Map.of(McpSchema.METHOD_PING,
						(io.modelcontextprotocol.spec.McpServerSession.RequestHandler<Object>) (exchange,
								params) -> Mono.just(Map.of("pong", true))));

		var mcpClient = clientBuilder.build();
		executeTestWithClient(mcpClient, () -> {
			var result = mcpClient.callTool(new McpSchema.CallToolRequest("ping-tool", Map.of())).block();
			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Ping sent");
		});
	}

	@Test
	@Order(10)
	void shouldHandleGetStreamsAndToolCallWithUpgradedTransport() throws InterruptedException {
		CountDownLatch notificationLatch = new CountDownLatch(1);
		AtomicReference<String> receivedNotification = new AtomicReference<>();

		var callResponse = new CallToolResult(List.of(new McpSchema.TextContent("Tool executed successfully")), null);
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool description", "{}"), (exchange, request) -> {
					exchange.upgradeTransport();
					return Mono.just(callResponse);
				});

		setupServerSession(tool);

		var mcpClient = McpClient
			.async(WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
				.endpoint(ENDPOINT)
				.objectMapper(new ObjectMapper())
				.openConnectionOnStartup(true)
				.build())
			.clientInfo(new McpSchema.Implementation("Test Client", "1.0.0"))
			.loggingConsumers(List.of(notification -> {
				if ("test message".equals(notification.data())) {
					receivedNotification.set(notification.data());
					notificationLatch.countDown();
				}
				return Mono.empty();
			}))
			.build();

		try {
			mcpClient.initialize().block();

			// Wait for GET stream establishment
			Thread.sleep(500);

			// Call tool with upgraded transport
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text())
				.isEqualTo("Tool executed successfully");

			// Send notification through GET stream
			serverTransportProvider
				.notifyClients(McpSchema.METHOD_NOTIFICATION_MESSAGE,
						new McpSchema.LoggingMessageNotification(McpSchema.LoggingLevel.INFO, "server", "test message"))
				.block();

			// Verify notification received
			assertThat(notificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(receivedNotification.get()).isEqualTo("test message");
		}
		finally {
			mcpClient.close();
		}
	}

	@Test
	@Order(11)
	void shouldFailCreateMessageWithoutSamplingCapabilities() {
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool", "{}"), (exchange, request) -> {
					exchange.createMessage(mock(McpSchema.CreateMessageRequest.class)).block();
					return Mono.just(mock(CallToolResult.class));
				});

		setupServerSession(tool);
		var mcpClient = clientBuilder.build();
		executeTestWithClient(mcpClient, () -> {
			assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			}).withMessageContaining("Client must be configured with sampling capabilities");
		});
	}

	@Test
	@Order(12)
	void shouldFailCreateElicitationWithoutElicitationCapabilities() {
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool", "{}"), (exchange, request) -> {
					exchange.createElicitation(mock(McpSchema.ElicitRequest.class)).block();
					return Mono.just(mock(CallToolResult.class));
				});

		setupServerSession(tool);
		var mcpClient = clientBuilder.build();
		executeTestWithClient(mcpClient, () -> {
			assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			}).withMessageContaining("Client must be configured with elicitation capabilities");
		});
	}

	@Test
	@Order(13)
	void shouldFailListRootsWithoutCapability() {
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool", "{}"), (exchange, request) -> {
					return exchange.listRoots().then(Mono.just(mock(CallToolResult.class)));
				});

		setupServerSession(tool);
		var mcpClient = clientBuilder.build();
		executeTestWithClient(mcpClient, () -> {
			assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			}).withMessageContaining("Roots not supported");
		});
	}

	@Test
	@Order(1000)
	void shouldHandleCreateMessageTimeoutSuccess() {
		Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = request -> {
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return Mono
				.just(new McpSchema.CreateMessageResult(McpSchema.Role.USER, new McpSchema.TextContent("Test message"),
						"MockModel", McpSchema.CreateMessageResult.StopReason.STOP_SEQUENCE));
		};

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool", "{}"), (exchange, request) -> {
					var createMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(McpSchema.ModelPreferences.builder().build())
						.build();
					return exchange.createMessage(createMessageRequest)
						.then(Mono.just(new CallToolResult(List.of(new McpSchema.TextContent("Success")), null)));
				});

		setupServerSession(tool, Duration.ofSeconds(3), Map.of());
		var mcpClient = buildClientWithCapabilities(McpSchema.ClientCapabilities.builder().sampling().build(),
				samplingHandler, null);
		executeTestWithClient(mcpClient, () -> {
			var result = mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Success");
		});
	}

	@Test
	@Order(1001)
	void shouldHandleCreateElicitationTimeoutSuccess() {
		Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler = request -> {
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
					Map.of("message", request.message())));
		};

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool", "{}"), (exchange, request) -> {
					var elicitRequest = McpSchema.ElicitRequest.builder()
						.message("Test message")
						.requestedSchema(Map.of("type", "object"))
						.build();
					return exchange.createElicitation(elicitRequest)
						.then(Mono.just(new CallToolResult(List.of(new McpSchema.TextContent("Success")), null)));
				});

		setupServerSession(tool, Duration.ofSeconds(3), Map.of());
		var mcpClient = buildClientWithCapabilities(McpSchema.ClientCapabilities.builder().elicitation().build(), null,
				elicitationHandler);
		executeTestWithClient(mcpClient, () -> {
			var result = mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Success");
		});
	}

	@Test
	@Order(1002)
	void shouldHandleCreateMessageTimeoutFailure() {
		Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = request -> {
			try {
				TimeUnit.SECONDS.sleep(3);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return Mono
				.just(new McpSchema.CreateMessageResult(McpSchema.Role.USER, new McpSchema.TextContent("Test message"),
						"MockModel", McpSchema.CreateMessageResult.StopReason.STOP_SEQUENCE));
		};

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool", "{}"), (exchange, request) -> {
					var createMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(McpSchema.ModelPreferences.builder().build())
						.build();
					return exchange.createMessage(createMessageRequest)
						.then(Mono.just(new CallToolResult(List.of(new McpSchema.TextContent("Success")), null)));
				});

		setupServerSession(tool, Duration.ofSeconds(2), Map.of());
		var mcpClient = buildClientWithCapabilities(McpSchema.ClientCapabilities.builder().sampling().build(),
				samplingHandler, null);
		executeTestWithClient(mcpClient, () -> {
			assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			}).withMessageContaining("Did not observe any item or terminal signal within");
		});
	}

	@Test
	@Order(1003)
	void shouldHandleCreateElicitationTimeoutFailure() {
		Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler = request -> {
			try {
				TimeUnit.SECONDS.sleep(3);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
					Map.of("message", request.message())));
		};

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool", "{}"), (exchange, request) -> {
					var elicitRequest = McpSchema.ElicitRequest.builder()
						.message("Test message")
						.requestedSchema(Map.of("type", "object"))
						.build();
					return exchange.createElicitation(elicitRequest)
						.then(Mono.just(new CallToolResult(List.of(new McpSchema.TextContent("Success")), null)));
				});

		setupServerSession(tool, Duration.ofSeconds(2), Map.of());
		var mcpClient = buildClientWithCapabilities(McpSchema.ClientCapabilities.builder().elicitation().build(), null,
				elicitationHandler);
		executeTestWithClient(mcpClient, () -> {
			assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			}).withMessageContaining("Did not observe any item or terminal signal within");
		});
	}

}