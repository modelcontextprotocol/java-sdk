/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientSseClientTransportConnectTests {

	// Only bounds a regression: every test resolves without waiting on it.
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

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
	void connectFailsWhenStreamEndsBeforeAnyEvent() throws IOException {
		HttpClientSseClientTransport transport = transport(exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});

		StepVerifier.create(transport.connect(Function.identity()))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(McpTransportException.class)
				.hasMessageContaining("before any event"))
			.verify(TIMEOUT);
	}

	@Test
	void connectFailsWhenStreamErrorsBeforeAnyEventWhileClosing() throws IOException {
		HttpClientSseClientTransport transport = transport(exchange -> {
			// The server drops the connection without responding.
			throw new IOException("dropped");
		});
		transport.closeGracefully().block(TIMEOUT);

		StepVerifier.create(transport.connect(Function.identity())).expectError().verify(TIMEOUT);
	}

	@Test
	void connectCompletesWhenClosedBeforeAnyEvent() throws IOException {
		CountDownLatch streamOpened = new CountDownLatch(1);
		HttpClientSseClientTransport transport = transport(exchange -> {
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

		StepVerifier.create(transport.connect(Function.identity())).then(() -> {
			try {
				assertThat(streamOpened.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
			}
			catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			transport.closeGracefully().block(TIMEOUT);
		}).expectComplete().verify(TIMEOUT);
	}

	private HttpClientSseClientTransport transport(HttpHandler sseHandler) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.setExecutor(this.executor);
		this.server.createContext("/sse", sseHandler);
		this.server.start();
		return HttpClientSseClientTransport.builder("http://127.0.0.1:" + this.server.getAddress().getPort())
			.jsonMapper(new GsonMcpJsonMapper())
			.build();
	}

}
