/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpAsyncStreamableHttpServer;
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
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for @link{StreamableHttpServerTransportProvider} with
 *
 * @link{WebClientStreamableHttpTransport}.
 */
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
					request -> reactor.core.publisher.Mono.just(new McpSchema.InitializeResult("2025-06-18",
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

	@Test
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
	void shouldCallImmediateToolSuccessfully() {
		var callResponse = new CallToolResult(List.of(new McpSchema.TextContent("Tool executed successfully")), null);
		String emptyJsonSchema = """
				{
					"$schema": "http://json-schema.org/draft-07/schema#",
					"type": "object",
					"properties": {}
				}
				""";
		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("test-tool", "Test tool description", emptyJsonSchema),
				(exchange, request) -> Mono.just(callResponse));

		// Configure session factory with tool handler
		McpSchema.ServerCapabilities serverCapabilities = new McpSchema.ServerCapabilities(null, null, null, null, null,
				new McpSchema.ServerCapabilities.ToolCapabilities(true));
		serverTransportProvider
			.setStreamableHttpSessionFactory(sessionId -> new io.modelcontextprotocol.spec.McpServerSession(sessionId,
					java.time.Duration.ofSeconds(30),
					request -> reactor.core.publisher.Mono.just(new McpSchema.InitializeResult("2025-06-18",
							serverCapabilities, new McpSchema.Implementation("Test Server", "1.0.0"), null)),
					() -> reactor.core.publisher.Mono.empty(),
					java.util.Map.of("tools/call",
							(io.modelcontextprotocol.spec.McpServerSession.RequestHandler<CallToolResult>) (exchange,
									params) -> tool.call().apply(exchange, (Map<String, Object>) params)),
					java.util.Map.of()));

		var mcpClient = clientBuilder.build();
		try {
			mcpClient.initialize().block();
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of())).block();
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text())
				.isEqualTo("Tool executed successfully");
		}
		finally {
			mcpClient.close();
		}
	}

	@Test
	void shouldCallStreamingToolSuccessfully() {
		String emptyJsonSchema = """
				{
					"$schema": "http://json-schema.org/draft-07/schema#",
					"type": "object",
					"properties": {}
				}
				""";
		McpServerFeatures.AsyncStreamingToolSpecification streamingTool = new McpServerFeatures.AsyncStreamingToolSpecification(
				new McpSchema.Tool("streaming-tool", "Streaming test tool", emptyJsonSchema),
				(exchange, request) -> Flux.range(1, 3)
					.map(i -> new CallToolResult(List.of(new McpSchema.TextContent("Step " + i)), null)));

		// Configure session factory with streaming tool handler
		McpSchema.ServerCapabilities serverCapabilities = new McpSchema.ServerCapabilities(null, null, null, null, null,
				new McpSchema.ServerCapabilities.ToolCapabilities(true));
		serverTransportProvider
			.setStreamableHttpSessionFactory(sessionId -> new io.modelcontextprotocol.spec.McpServerSession(sessionId,
					java.time.Duration.ofSeconds(30),
					request -> reactor.core.publisher.Mono.just(new McpSchema.InitializeResult("2025-06-18",
							serverCapabilities, new McpSchema.Implementation("Test Server", "1.0.0"), null)),
					() -> reactor.core.publisher.Mono.empty(), java.util.Map.of("tools/call",
							(io.modelcontextprotocol.spec.McpServerSession.StreamingRequestHandler<CallToolResult>) new io.modelcontextprotocol.spec.McpServerSession.StreamingRequestHandler<CallToolResult>() {
								@Override
								public Mono<CallToolResult> handle(
										io.modelcontextprotocol.server.McpAsyncServerExchange exchange, Object params) {
									return streamingTool.call().apply(exchange, (Map<String, Object>) params).next();
								}

								@Override
								public Flux<CallToolResult> handleStreaming(
										io.modelcontextprotocol.server.McpAsyncServerExchange exchange, Object params) {
									return streamingTool.call().apply(exchange, (Map<String, Object>) params);
								}
							}),
					java.util.Map.of()));

		var mcpClient = clientBuilder.build();
		try {
			mcpClient.initialize().block();
			CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("streaming-tool", Map.of()))
				.block();
			assertThat(result).isNotNull();
			assertThat(result.content()).hasSize(1);
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).startsWith("Step");
		}
		finally {
			mcpClient.close();
		}
	}

	@Test
	void shouldReceiveNotificationThroughGetStream() throws InterruptedException {
		CountDownLatch notificationLatch = new CountDownLatch(1);
		AtomicReference<String> receivedEvent = new AtomicReference<>();
		AtomicReference<String> sessionId = new AtomicReference<>();

		WebClient webClient = WebClient.create("http://localhost:" + PORT);
		String initMessage = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"Test\",\"version\":\"1.0\"}}}";

		// Initialize and get session ID
		webClient.post()
			.uri(ENDPOINT)
			.header("Accept", "application/json, text/event-stream")
			.header("Content-Type", "application/json")
			.bodyValue(initMessage)
			.retrieve()
			.toBodilessEntity()
			.doOnNext(response -> sessionId.set(response.getHeaders().getFirst("Mcp-Session-Id")))
			.block();

		// Establish SSE stream
		webClient.get()
			.uri(ENDPOINT)
			.header("Accept", "text/event-stream")
			.header("Mcp-Session-Id", sessionId.get())
			.retrieve()
			.bodyToFlux(String.class)
			.filter(line -> line.contains("test/notification"))
			.doOnNext(event -> {
				receivedEvent.set(event);
				notificationLatch.countDown();
			})
			.subscribe();

		// Send notification after delay
		Mono.delay(Duration.ofMillis(200))
			.then(serverTransportProvider.notifyClients("test/notification", "test message"))
			.subscribe();

		assertThat(notificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedEvent.get()).isNotNull();
		assertThat(receivedEvent.get()).contains("test/notification");
	}

}