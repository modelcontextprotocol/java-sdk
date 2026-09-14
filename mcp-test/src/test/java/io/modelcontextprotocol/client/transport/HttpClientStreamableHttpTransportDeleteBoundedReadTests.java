/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link HttpClientStreamableHttpTransport} bounds the response to the
 * session-terminating DELETE, so a server cannot exhaust the client's memory on the way
 * out. Checks both that the failure surfaces to the caller and that the client actually
 * stops reading rather than draining whatever the server sends.
 *
 * @author Daniel Garnier-Moiroux
 */
@Timeout(30)
class HttpClientStreamableHttpTransportDeleteBoundedReadTests {

	private static final int MAX_SIZE = 1024;

	private static final int FLOOD_BYTES = 32 * 1024 * 1024;

	private HttpServer server;

	private String host;

	private final AtomicLong deleteBytesWritten = new AtomicLong();

	private final CountDownLatch deleteFinished = new CountDownLatch(1);

	@BeforeEach
	void setUp() throws IOException {
		int port = TomcatTestUtil.findAvailablePort();
		this.host = "http://localhost:" + port;
		this.server = HttpServer.create(new InetSocketAddress(port), 0);
		this.server.createContext("/mcp", exchange -> {
			if ("DELETE".equals(exchange.getRequestMethod())) {
				floodDeleteResponse(exchange);
				return;
			}
			// Hand out a session id so the transport issues a DELETE on close.
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.getResponseHeaders().set("Mcp-Session-Id", "test-session");
			byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":\"test-id\",\"result\":{}}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		this.server.setExecutor(Executors.newCachedThreadPool());
		this.server.start();
	}

	private void floodDeleteResponse(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream body = exchange.getResponseBody()) {
			byte[] chunk = new byte[64 * 1024];
			java.util.Arrays.fill(chunk, (byte) 'a'); // no line terminator, ever
			while (this.deleteBytesWritten.get() < FLOOD_BYTES) {
				body.write(chunk);
				body.flush();
				this.deleteBytesWritten.addAndGet(chunk.length);
			}
		}
		catch (IOException ignored) {
			// Expected: the client aborts the response once the limit is exceeded.
		}
		finally {
			exchange.close();
			this.deleteFinished.countDown();
		}
	}

	@AfterEach
	void tearDown() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@Test
	void shouldStopReadingDeleteResponseOnceLimitExceeded() throws Exception {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(this.host)
			.maxResponseSize(MAX_SIZE)
			.build();
		transport.connect(m -> m).block(Duration.ofSeconds(5));
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));
		transport.sendMessage(request).block(Duration.ofSeconds(5));

		assertThatThrownBy(() -> transport.closeGracefully().block(Duration.ofSeconds(10)))
			.isInstanceOf(McpTransportException.class)
			.hasMessageContaining("Inbound response body exceeds the maximum allowed size");

		assertThat(this.deleteFinished.await(15, TimeUnit.SECONDS))
			.as("the server's DELETE response should be cut short by the client")
			.isTrue();
		assertThat(this.deleteBytesWritten.get()).as("bytes the client let the server stream on DELETE")
			.isLessThan(FLOOD_BYTES);
	}

}
