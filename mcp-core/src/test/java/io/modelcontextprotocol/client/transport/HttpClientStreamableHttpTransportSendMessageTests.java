/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link HttpClientStreamableHttpTransport#sendMessage} always resolves,
 * and that it fails when the server's response to it cannot be read.
 */
class HttpClientStreamableHttpTransportSendMessageTests {

	// Only bounds a regression: every test resolves without waiting on it.
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final McpSchema.JSONRPCRequest REQUEST = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
			"ping", "1", null);

	private final ExecutorService executor = Executors.newCachedThreadPool();

	private final CountDownLatch releaseResponse = new CountDownLatch(1);

	private HttpServer server;

	@AfterEach
	void tearDown() {
		this.releaseResponse.countDown();
		if (this.server != null) {
			this.server.stop(0);
		}
		this.executor.shutdownNow();
	}

	@Test
	void sendMessageFailsWhenJsonResponseIsMalformed() throws IOException {
		HttpClientStreamableHttpTransport transport = transport(exchange -> {
			byte[] body = "{broken".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				outputStream.write(body);
			}
		});

		StepVerifier.create(transport.sendMessage(REQUEST))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(McpTransportException.class)
				.hasMessageContaining("Error deserializing JSON-RPC message"))
			.verify(TIMEOUT);
	}

	@Test
	void sendMessageCompletesWhenClosedBeforeAnyEvent() throws IOException {
		CountDownLatch streamOpened = new CountDownLatch(1);
		HttpClientStreamableHttpTransport transport = transport(exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().flush();
			streamOpened.countDown();
			try {
				this.releaseResponse.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});

		StepVerifier.create(transport.sendMessage(REQUEST)).then(() -> {
			try {
				assertThat(streamOpened.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
			}
			catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			transport.closeGracefully().block(TIMEOUT);
		}).expectComplete().verify(TIMEOUT);
	}

	private HttpClientStreamableHttpTransport transport(HttpHandler postHandler) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.setExecutor(this.executor);
		this.server.createContext("/mcp", exchange -> {
			if ("POST".equals(exchange.getRequestMethod())) {
				postHandler.handle(exchange);
			}
			else {
				// No standalone SSE stream, which keeps the POST the only exchange.
				methodNotAllowed(exchange);
			}
		});
		this.server.start();
		return HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.server.getAddress().getPort())
			.jsonMapper(new GsonMcpJsonMapper())
			.build();
	}

	private static void methodNotAllowed(HttpExchange exchange) throws IOException {
		try (exchange) {
			exchange.sendResponseHeaders(405, -1);
		}
	}

}
