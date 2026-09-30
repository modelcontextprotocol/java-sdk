/*
 * Copyright 2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class HttpClientStreamableHttpTransportJsonErrorTests {

	private HttpServer server;

	private ExecutorService executor;

	private HttpClientStreamableHttpTransport transport;

	private volatile String responseBody = "{broken";

	private volatile int responseStatus = 200;

	private volatile boolean unresponsive;

	private final CountDownLatch releaseResponse = new CountDownLatch(1);

	private final ConcurrentLinkedQueue<String> methods = new ConcurrentLinkedQueue<>();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		executor = Executors.newCachedThreadPool();
		server.setExecutor(executor);
		server.createContext("/mcp", this::respond);
		server.start();
		transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + server.getAddress().getPort())
			.openConnectionOnStartup(false)
			.resumableStreams(false)
			.build();
	}

	@AfterEach
	void stopServer() {
		releaseResponse.countDown();
		transport.closeGracefully().block(Duration.ofSeconds(2));
		server.stop(0);
		executor.shutdownNow();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{broken", "", " " })
	void malformedJsonFailsSendMessageWithParsingCause(String body) {
		responseBody = body;
		transport.connect(message -> message).block();
		StepVerifier.create(transport.sendMessage(new McpSchema.JSONRPCRequest("ping", "test-id", null)))
			.expectErrorSatisfies(HttpClientStreamableHttpTransportJsonErrorTests::assertParsingFailure)
			.verify(Duration.ofSeconds(3));
	}

	@Test
	void malformedJsonFailsPendingRequestAndRemovesItBeforeTimeout() {
		var session = new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of(),
				connection -> connection);
		try {
			StepVerifier.create(session.sendRequest("ping", null, new TypeRef<Map<String, Object>>() {
			}))
				.expectErrorSatisfies(HttpClientStreamableHttpTransportJsonErrorTests::assertParsingFailure)
				.verify(Duration.ofSeconds(3));
			assertThat((Map<?, ?>) ReflectionTestUtils.getField(session, "pendingResponses")).isEmpty();
		}
		finally {
			session.close();
		}
	}

	@Test
	void malformedInitializeFailsWithoutCallingTools() {
		var client = McpClient.async(transport)
			.initializationTimeout(Duration.ofSeconds(5))
			.requestTimeout(Duration.ofSeconds(5))
			.build();
		try {
			StepVerifier.create(client.initialize())
				.expectErrorSatisfies(error -> assertParsingFailure(error.getCause()))
				.verify(Duration.ofSeconds(3));
			assertThat(methods).containsExactly("initialize");
		}
		finally {
			client.close();
		}
	}

	@Test
	void notificationStillAcceptsNonCompliantJsonResponse() {
		transport.connect(message -> message).block();
		StepVerifier.create(transport.sendMessage(new McpSchema.JSONRPCNotification("notifications/initialized")))
			.verifyComplete();
	}

	@Test
	void validInitializeListAndCallStillSucceed() {
		responseBody = null;
		var client = McpClient.async(transport).build();
		try {
			StepVerifier.create(client.initialize().then(client.listTools()))
				.assertNext(
						result -> assertThat(result.tools()).extracting(McpSchema.Tool::name).containsExactly("probe"))
				.verifyComplete();
			StepVerifier.create(client.callTool(McpSchema.CallToolRequest.builder("probe").build()))
				.assertNext(result -> {
					assertThat(result.isError()).isFalse();
					assertThat(result.content()).containsExactly(new McpSchema.TextContent("ok"));
				})
				.verifyComplete();
			assertThat(methods).containsExactly("initialize", "notifications/initialized", "tools/list", "tools/call");
		}
		finally {
			client.close();
		}
	}

	@Test
	void unresponsiveServerStillTimesOut() {
		unresponsive = true;
		var session = new McpClientSession(Duration.ofMillis(300), transport, Map.of(), Map.of(),
				connection -> connection);
		try {
			StepVerifier.create(session.sendRequest("ping", null, new TypeRef<Map<String, Object>>() {
			})).expectError(TimeoutException.class).verify(Duration.ofSeconds(3));
		}
		finally {
			session.close();
		}
	}

	@Test
	void serviceUnavailableStillReportsHttpFailure() {
		responseStatus = 503;
		transport.connect(message -> message).block();
		StepVerifier.create(transport.sendMessage(new McpSchema.JSONRPCRequest("ping", "test-id", null)))
			.expectErrorSatisfies(error -> assertThat(error).isNotInstanceOf(TimeoutException.class)
				.hasMessageContaining("Failed to send message"))
			.verify(Duration.ofSeconds(3));
	}

	@Test
	void connectionRefusalStillReportsConnectionFailure() {
		server.stop(0);
		transport.connect(message -> message).block();
		StepVerifier.create(transport.sendMessage(new McpSchema.JSONRPCRequest("ping", "test-id", null)))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ConnectException.class))
			.verify(Duration.ofSeconds(3));
	}

	private static void assertParsingFailure(Throwable error) {
		assertThat(error).isInstanceOf(McpTransportException.class).hasCauseInstanceOf(IOException.class);
	}

	private void respond(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				return;
			}
			var request = McpJsonDefaults.getMapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class);
			String method = (String) request.get("method");
			methods.add(method);
			if (unresponsive) {
				try {
					releaseResponse.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return;
			}
			String body = responseBody;
			if (body == null) {
				if (!request.containsKey("id")) {
					exchange.sendResponseHeaders(202, -1);
					return;
				}
				Object result = switch (method) {
					case "initialize" -> Map.of("protocolVersion", "2025-11-25", "capabilities",
							Map.of("tools", Map.of()), "serverInfo", Map.of("name", "test", "version", "1"));
					case "tools/list" -> Map.of("tools",
							java.util.List.of(Map.of("name", "probe", "inputSchema", Map.of("type", "object"))));
					case "tools/call" ->
						Map.of("content", java.util.List.of(Map.of("type", "text", "text", "ok")), "isError", false);
					default -> Map.of();
				};
				body = McpJsonDefaults.getMapper()
					.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(responseStatus, bytes.length);
			exchange.getResponseBody().write(bytes);
		}
	}

}
